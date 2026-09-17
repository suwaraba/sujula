package com.sujula.service.delivery;

import com.sujula.dto.GeoAddress;
import com.sujula.dto.request.delivery.DeliveryContextRequests;
import com.sujula.dto.response.delivery.DeliveryContextResponse;
import com.sujula.dto.response.geo.GeoResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.Address;
import com.sujula.model.constant.DeliveryMode;
import com.sujula.model.constant.GeocodeConfidence;
import com.sujula.model.delivery.DeliveryContext;
import com.sujula.model.delivery.PickupPoint;
import com.sujula.model.user.User;
import com.sujula.repository.AddressRepository;
import com.sujula.repository.PickupPointRepository;
import com.sujula.repository.delivery.DeliveryContextRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.geo.GeoLookupService;
import com.sujula.service.geo.GeocodingGateway;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

/**
 * Establishes where a basket is going, once, so everything downstream agrees.
 *
 * <p>The cart, the delivery quote, the serviceability check and the checkout
 * preview all need the same destination. Passed the destination separately, they
 * drift — the cart quotes one figure and checkout charges another, which is the
 * kind of discrepancy a shopper notices and never forgives. A context is that
 * destination named once and referred to afterwards.
 *
 * <h2>Guests</h2>
 *
 * <p>Most shopping happens before anyone signs in, so a context does not require
 * an account. For a guest the id <em>is</em> the credential: 256 bits from a
 * secure random, unguessable by construction, and short-lived because a bearer
 * token that never expires is one that survives in a browser history or a shared
 * link. For a signed-in buyer the row is additionally bound to them, and
 * possession stops being enough — so a leaked id cannot be used to read the
 * destination of someone who had an account.
 */
@Slf4j
@Service
public class DeliveryContextService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DeliveryContextRepository contexts;
    private final AddressRepository addresses;
    private final PickupPointRepository pickupPoints;
    private final UserRepository users;
    private final GeocodingGateway geocoding;
    private final GeoLookupService geoLookup;
    private final Duration lifetime;

    public DeliveryContextService(DeliveryContextRepository contexts, AddressRepository addresses,
                                  PickupPointRepository pickupPoints, UserRepository users,
                                  GeocodingGateway geocoding, GeoLookupService geoLookup,
                                  @Value("${sujula.delivery.context-lifetime:PT12H}") Duration lifetime) {
        this.contexts = contexts;
        this.addresses = addresses;
        this.pickupPoints = pickupPoints;
        this.users = users;
        this.geocoding = geocoding;
        this.geoLookup = geoLookup;
        this.lifetime = lifetime;
    }

    /**
     * Resolves a destination and records it.
     *
     * @param userId null for a guest — not an error, but the normal case
     */
    @Transactional
    public DeliveryContextResponse create(Long userId, DeliveryContextRequests.Create request,
                                          HttpServletRequest httpRequest) {

        DeliveryMode mode = request.mode() == null ? DeliveryMode.HOME_DELIVERY : request.mode();
        GeoResponses.ResolvedContext inferred = geoLookup.resolveContext(httpRequest);

        DeliveryContext context = DeliveryContext.builder()
                .id(newId())
                .mode(mode)
                .createdAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plus(lifetime))
                .currency(upper(firstNonBlank(request.currency(), inferred.currency())))
                .language(firstNonBlank(request.language(), inferred.language()))
                .timezone(inferred.timezone())
                .countryCode(upper(firstNonBlank(request.countryCode(), inferred.countryCode())))
                .geocodeConfidence(GeocodeConfidence.NONE)
                .build();

        if (userId != null) {
            // Bound to the account, so possession of the id alone is no longer
            // enough to read it.
            context.setUser(users.findById(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("User", userId)));
        }

        resolveDestination(userId, request, context);
        resolvePickupPoint(mode, request.pickupPointId(), context);

        DeliveryContext saved = contexts.save(context);
        log.debug("[Delivery] Context {} created for {}", saved.getId(),
                userId == null ? "a guest" : "user " + userId);
        return toResponse(saved);
    }

    /**
     * Reads a context back.
     *
     * <p>Two checks, and both matter. The context must still be live — expiry is
     * in the query, so an expired one is indistinguishable from one that never
     * existed and a caller probing ids learns nothing. And a context belonging to
     * an account is readable only by that account: holding the id is how a guest
     * proves ownership, and it must not become a way to read a signed-in
     * shopper's home address.
     *
     * @param userId the caller, or null when nobody is signed in
     */
    @Transactional(readOnly = true)
    public DeliveryContextResponse get(String id, Long userId) {
        DeliveryContext context = contexts.findLive(id)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery context", id));

        if (!context.isAnonymous() && !context.belongsTo(userId)) {
            // Not-found rather than forbidden: "this id exists but is not yours"
            // confirms the id is real, which is the one thing worth withholding
            // from someone who guessed it.
            throw new ResourceNotFoundException("Delivery context", id);
        }
        return toResponse(context);
    }

    /** The entity, for the services that price against a context. */
    @Transactional(readOnly = true)
    public DeliveryContext require(String id, Long userId) {
        return lookup(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery context", id));
    }

    /**
     * The same resolution, for a caller that has something to do either way.
     *
     * <p>Empty rather than thrown, and that is the whole reason this method
     * exists. Reading a cart prices its shipping on a best-effort basis: a
     * context that has expired, or that belongs to somebody else, leaves the
     * cart readable without a shipping figure rather than failing the read — a
     * shopper whose destination lapsed should be asked for it again, not shown
     * an error page with their basket behind it.
     *
     * <p>Catching {@link #require}'s exception looked like it did that, and did
     * not. This method runs in the caller's transaction, so an exception
     * escaping it marks that transaction rollback-only; the caller then caught
     * it, carried on, returned normally, and the commit failed with
     * UnexpectedRollbackException. GET /carts/{token} answered 500 for the one
     * case the catch was written for. REQUIRES_NEW would also have fixed it and
     * would have opened a second transaction on every cart read to report a
     * result that is not exceptional in the first place.
     */
    @Transactional(readOnly = true)
    public Optional<DeliveryContext> lookup(String id, Long userId) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return contexts.findLive(id)
                .filter(context -> context.isAnonymous() || context.belongsTo(userId));
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Works out where this is, from the best thing the client gave.
     *
     * <p>Order matters: a saved address that has already been geocoded and
     * possibly pin-confirmed beats a fresh lookup, and client-supplied
     * coordinates beat geocoding text — a device reporting its own position knows
     * better than any address database.
     */
    private void resolveDestination(Long userId, DeliveryContextRequests.Create request,
                                    DeliveryContext context) {

        if (request.addressId() != null) {
            if (userId == null) {
                throw new BadRequestException(
                        "A saved address belongs to an account. Send the address itself instead.");
            }
            // Ownership is the query — a guest's or a stranger's id resolves to
            // nothing rather than to somebody else's home.
            Address address = addresses.findLiveByIdAndUserId(request.addressId(), userId)
                    .orElseThrow(() -> new ResourceNotFoundException("Address", request.addressId()));
            copyFrom(address, context);
            return;
        }

        if (request.hasCoordinates()
                && GeocodingGateway.isValidCoordinate(request.latitude(), request.longitude())) {
            context.setLatitude(request.latitude());
            context.setLongitude(request.longitude());
            context.setGeocodeConfidence(GeocodeConfidence.EXACT);
            copyWritten(request, context);
            return;
        }

        copyWritten(request, context);

        if (request.hasWrittenAddress()) {
            geocoding.forward(searchText(request), context.getLanguage())
                    .ifPresent(located -> applyGeocode(located, context));
        }
        // Nothing usable given: the context still carries a country and a
        // currency, which is enough to price a catalogue. Delivery falls back to
        // a scope distance until a destination arrives.
    }

    private void applyGeocode(GeoAddress located, DeliveryContext context) {
        context.setLatitude(located.getLatitude());
        context.setLongitude(located.getLongitude());
        context.setGeocodeConfidence(located.getConfidence());
        if (blank(context.getCity())) {
            context.setCity(located.getCity());
        }
        if (blank(context.getCountryCode())) {
            context.setCountryCode(upper(located.getCountryCode()));
        }
    }

    private void resolvePickupPoint(DeliveryMode mode, Long pickupPointId, DeliveryContext context) {
        if (mode != DeliveryMode.PICKUP_POINT) {
            // Silently ignored rather than rejected: a client switching from
            // collection to home delivery often leaves the old id in the payload,
            // and failing the request over a field that no longer applies helps
            // nobody.
            return;
        }
        if (pickupPointId == null) {
            throw new BadRequestException(
                    "Collecting from a pickup point needs pickupPointId — the hub is where the "
                            + "parcel is priced to.");
        }
        PickupPoint point = pickupPoints.findById(pickupPointId)
                .orElseThrow(() -> new ResourceNotFoundException("Pickup point", pickupPointId));

        if (!point.isActive() || point.getStatus() == null || !point.getStatus().canTrade()) {
            throw new BadRequestException(
                    "That pickup point is not accepting parcels at the moment.");
        }
        context.setPickupPointId(pickupPointId);
    }

    private static void copyFrom(Address address, DeliveryContext context) {
        context.setAddressId(address.getId());
        context.setLatitude(address.getLatitude());
        context.setLongitude(address.getLongitude());
        context.setAddressLine(joinStreet(address.getStreet(), address.getApartmentSuite()));
        context.setCity(address.getCity());
        context.setState(address.getState());
        context.setPostalCode(address.getPostalCode());
        context.setCountryCode(address.getCountryCode());
        context.setGeocodeConfidence(address.getGeocodeConfidence() == null
                ? GeocodeConfidence.NONE : address.getGeocodeConfidence());
    }

    private static void copyWritten(DeliveryContextRequests.Create request, DeliveryContext context) {
        context.setAddressLine(trimToNull(request.addressLine()));
        context.setCity(trimToNull(request.city()));
        context.setState(trimToNull(request.state()));
        context.setPostalCode(trimToNull(request.postalCode()));
        if (!blank(request.countryCode())) {
            context.setCountryCode(upper(request.countryCode()));
        }
    }

    private static String searchText(DeliveryContextRequests.Create request) {
        StringBuilder text = new StringBuilder();
        for (String part : new String[]{request.addressLine(), request.city(), request.state(),
                                        request.postalCode(), request.countryCode()}) {
            if (!blank(part)) {
                if (!text.isEmpty()) {
                    text.append(", ");
                }
                text.append(part.trim());
            }
        }
        return text.toString();
    }

    /**
     * A handle nobody can guess.
     *
     * <p>256 bits, url-safe, unpadded. Sequential would be catastrophic: the next
     * shopper's destination would be one increment away, and for a guest the id
     * is the only thing standing between a stranger and their home address.
     */
    private static String newId() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private DeliveryContextResponse toResponse(DeliveryContext context) {
        GeocodeConfidence confidence = context.getGeocodeConfidence() == null
                ? GeocodeConfidence.NONE : context.getGeocodeConfidence();

        return new DeliveryContextResponse(
                context.getId(),
                context.isAnonymous(),
                context.getLatitude(),
                context.getLongitude(),
                context.getAddressLine(),
                context.getCity(),
                context.getState(),
                context.getPostalCode(),
                context.getCountryCode(),
                confidence,
                context.hasCoordinates() && confidence.needsConfirmation(),
                // Collection needs no destination at all — the parcel goes to a
                // hub or stays at the vendor's shop.
                context.getMode() != DeliveryMode.HOME_DELIVERY || context.hasCoordinates(),
                context.getMode(),
                context.getPickupPointId(),
                context.getAddressId(),
                context.getCurrency(),
                context.getLanguage(),
                context.getTimezone(),
                context.getCreatedAt(),
                context.getExpiresAt());
    }

    private static String joinStreet(String street, String apartment) {
        if (blank(street)) {
            return trimToNull(apartment);
        }
        return blank(apartment) ? street.trim() : street.trim() + " " + apartment.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToNull(String value) {
        return blank(value) ? null : value.trim();
    }

    private static String upper(String value) {
        return blank(value) ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String firstNonBlank(String first, String second) {
        return blank(first) ? second : first;
    }

    /** For the housekeeping job. */
    @Transactional
    public int purgeExpired() {
        return contexts.deleteExpiredBefore(LocalDateTime.now());
    }
}
