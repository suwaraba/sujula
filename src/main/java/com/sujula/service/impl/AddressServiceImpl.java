package com.sujula.service.impl;

import com.sujula.dto.GeoAddress;
import com.sujula.dto.request.address.AddressRequests;
import com.sujula.dto.request.user.AddressRequest;
import com.sujula.dto.response.address.AddressResponses;
import com.sujula.dto.response.user.AddressResponse;
import com.sujula.model.constant.GeocodeConfidence;
import com.sujula.service.geo.GeocodingGateway;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.Address;
import com.sujula.model.user.User;
import com.sujula.repository.AddressRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.AddressService;
import com.sujula.service.GoogleMapsService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Saved delivery addresses, geocoded on the way in.
 *
 * <p>Ownership is checked on every read and write, and a stranger's address
 * reads as not-found rather than forbidden: confirming that address 41 exists
 * tells someone something about a person they have no business knowing.
 */
@Service
@RequiredArgsConstructor
public class AddressServiceImpl implements AddressService {

    private static final Logger log = LoggerFactory.getLogger(AddressServiceImpl.class);

    /** Beyond this a buyer is collecting addresses, not using them. */
    private static final int MAX_ADDRESSES_PER_USER = 20;

    private final AddressRepository addressRepository;
    private final UserRepository userRepository;
    private final GoogleMapsService googleMapsService;
    private final GeocodingGateway geocoding;

    @Value("${sujula.geocoding.default-language:en}")
    private String geocodingLanguage;

    @Override
    @Transactional(readOnly = true)
    public List<AddressResponse> findMine(Long userId) {
        // findLiveByUserId, not findByUserId: soft deletion arrived with the
        // /me/addresses surface, and the unfiltered query now returns tombstones
        // too. Every read a buyer makes has to exclude them, on the old path as
        // much as the new one — an address they deleted reappearing in their
        // list is the same bug whichever URL asked.
        return addressRepository.findLiveByUserId(requireUserId(userId)).stream()
                .map(AddressResponse::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AddressResponse findOne(Long addressId, Long userId) {
        return AddressResponse.from(requireOwnAddress(addressId, userId));
    }

    @Override
    @Transactional(readOnly = true)
    public AddressResponse findDefault(Long userId) {
        return addressRepository.findLiveDefault(requireUserId(userId))
                .map(AddressResponse::from)
                .orElse(null);
    }

    @Override
    @Transactional
    public AddressResponse create(Long userId, AddressRequest request) {
        requireUserId(userId);
        if (addressRepository.countLiveByUserId(userId) >= MAX_ADDRESSES_PER_USER) {
            throw new BadRequestException(
                    "You can save up to " + MAX_ADDRESSES_PER_USER + " addresses. Remove one to add another.");
        }
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        Address address = new Address();
        address.setUser(owner);
        apply(address, request);

        // The first address is the default whatever was asked for: a buyer with
        // exactly one address and no default would reach checkout with nothing
        // selected.
        boolean first = addressRepository.countLiveByUserId(userId) == 0;
        if (first || request.isMakeDefault()) {
            addressRepository.clearDefaultsByUserId(userId);
            address.setDefault(true);
        }
        return AddressResponse.from(addressRepository.save(address));
    }

    @Override
    @Transactional
    public AddressResponse update(Long addressId, Long userId, AddressRequest request) {
        Address address = requireOwnAddress(addressId, userId);
        apply(address, request);

        if (request.isMakeDefault() && !address.isDefault()) {
            addressRepository.clearDefaultsByUserId(userId);
            address.setDefault(true);
        }
        return AddressResponse.from(addressRepository.save(address));
    }

    @Override
    @Transactional
    public AddressResponse makeDefault(Long addressId, Long userId) {
        Address address = requireOwnAddress(addressId, userId);
        if (address.isDefault()) {
            return AddressResponse.from(address);
        }
        addressRepository.clearDefaultsByUserId(userId);
        address.setDefault(true);
        return AddressResponse.from(addressRepository.save(address));
    }

    @Override
    @Transactional
    public void delete(Long addressId, Long userId) {
        // Delegated so both surfaces delete by one rule. The older path threw
        // the address away unconditionally, which would now orphan an order that
        // named it.
        remove(userId, addressId);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  The /me/addresses surface
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public List<AddressResponses.Address> listMine(Long userId) {
        // Ordered by the database. Sorting in Java a list the query could have
        // returned sorted is work done twice, and the order is not incidental —
        // it is the order a chooser has to offer them in.
        return addressRepository.findLiveByUserId(requireUserId(userId)).stream()
                .map(AddressServiceImpl::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AddressResponses.Address getMine(Long userId, Long addressId) {
        return toResponse(requireLiveOwned(userId, addressId));
    }

    @Override
    @Transactional
    public AddressResponses.Address add(Long userId, AddressRequests.Create request) {
        requireUserId(userId);
        long existing = addressRepository.countLiveByUserId(userId);
        if (existing >= MAX_ADDRESSES_PER_USER) {
            throw new BadRequestException(
                    "You can save up to " + MAX_ADDRESSES_PER_USER + " addresses. Remove one to add another.");
        }
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        Address address = new Address();
        address.setUser(owner);
        address.setLabel(request.label().trim());
        address.setFullName(request.fullName().trim());
        address.setPhone(request.phone().trim());
        address.setStreet(request.street().trim());
        address.setApartmentSuite(blankToNull(request.apartmentSuite()));
        address.setCity(request.city().trim());
        address.setState(blankToNull(request.state()));
        address.setPostalCode(blankToNull(request.postalCode()));
        address.setCountryCode(request.countryCode().trim().toUpperCase(Locale.ROOT));

        if (GeocodingGateway.isValidCoordinate(request.latitude(), request.longitude())) {
            // A device that offered "use my current location" knows better than
            // any geocoder where this person is standing.
            setPin(address, request.latitude(), request.longitude(), GeocodeConfidence.EXACT);
        } else {
            geocode(address);
        }

        // The first address is the default whatever was asked for: a buyer with
        // exactly one address and no default reaches checkout with nothing
        // selected.
        if (existing == 0 || request.makeDefault()) {
            addressRepository.clearDefaultsByUserId(userId);
            address.setDefault(true);
        }
        return toResponse(addressRepository.save(address));
    }

    @Override
    @Transactional
    public AddressResponses.Address patch(Long userId, Long addressId, AddressRequests.Patch request) {
        Address address = requireLiveOwned(userId, addressId);

        if (present(request.label()))          address.setLabel(request.label().trim());
        if (present(request.fullName()))       address.setFullName(request.fullName().trim());
        if (present(request.phone()))          address.setPhone(request.phone().trim());
        if (present(request.street()))         address.setStreet(request.street().trim());
        if (present(request.city()))           address.setCity(request.city().trim());
        if (present(request.countryCode()))    address.setCountryCode(request.countryCode().trim().toUpperCase(Locale.ROOT));

        // Nullable by intent: an apartment number, a region and a postcode are
        // all things an address can stop having, and an empty string is how a
        // form says so.
        if (request.apartmentSuite() != null) address.setApartmentSuite(blankToNull(request.apartmentSuite()));
        if (request.state() != null)         address.setState(blankToNull(request.state()));
        if (request.postalCode() != null)    address.setPostalCode(blankToNull(request.postalCode()));

        if (request.hasCoordinates()
                && GeocodingGateway.isValidCoordinate(request.latitude(), request.longitude())) {
            setPin(address, request.latitude(), request.longitude(), GeocodeConfidence.EXACT);
        } else if (request.touchesLocation() && address.getPinConfirmedAt() == null) {
            // Re-geocode only when the edit actually moved the address, and never
            // over a pin its owner placed. Correcting a phone number must not
            // cost a lookup, and must certainly not relocate someone's home.
            geocode(address);
        }

        if (Boolean.TRUE.equals(request.makeDefault()) && !address.isDefault()) {
            addressRepository.clearDefaultsByUserId(userId);
            address.setDefault(true);
        }
        return toResponse(addressRepository.save(address));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Two paths, decided by one question: did an order name this row? If it
     * did, the row stays as a tombstone so that order still resolves — its own
     * shipping snapshot is what actually addresses the parcel, but a dangling
     * reference is worse than a kept row. If it did not, the address is deleted
     * outright, because keeping a name, a phone number and a location for its own
     * sake — particularly for someone who just asked for it to be gone — is not
     * something to do by default.
     */
    @Override
    @Transactional
    public AddressResponses.Deletion remove(Long userId, Long addressId) {
        Address address = requireLiveOwned(userId, addressId);
        boolean wasDefault = address.isDefault();
        boolean referenced = addressRepository.isReferencedByAnyOrder(addressId);

        if (referenced) {
            address.setDeletedAt(LocalDateTime.now());
            address.setDefault(false);
            addressRepository.save(address);
        } else {
            addressRepository.delete(address);
        }

        if (wasDefault) {
            // Never leave a buyer with addresses but no default — checkout would
            // open with nothing selected.
            addressRepository.findNextDefaultCandidate(userId, addressId).ifPresent(next -> {
                next.setDefault(true);
                addressRepository.save(next);
            });
        }

        log.info("[Address] {} address {} for user {}",
                referenced ? "Retired" : "Deleted", addressId, userId);

        return new AddressResponses.Deletion(addressId, referenced,
                referenced
                        ? "Kept as a record because an order was placed against it. It no longer "
                          + "appears in your address book."
                        : "Deleted.");
    }

    @Override
    @Transactional
    public AddressResponses.Address confirmPin(Long userId, Long addressId,
                                               AddressRequests.ConfirmPin request) {
        Address address = requireLiveOwned(userId, addressId);

        if (!GeocodingGateway.isValidCoordinate(request.latitude(), request.longitude())) {
            throw new BadRequestException(
                    "Those coordinates are not a place on Earth. Check that latitude and longitude "
                            + "have not been swapped.");
        }

        setPin(address, request.latitude(), request.longitude(), GeocodeConfidence.USER_CONFIRMED);
        address.setPinConfirmedAt(LocalDateTime.now());

        log.info("[Address] User {} confirmed the pin on address {}", userId, addressId);
        return toResponse(addressRepository.save(address));
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Places the address on a map, and records how well.
     *
     * <p>Best effort throughout: a geocoder that is unconfigured, unreachable or
     * simply does not know a rural compound must not stop someone saving where
     * they live. Most of the country has no street numbering, so failing here
     * would make the address book unusable for the buyers it is mainly for.
     */
    private void geocode(Address address) {
        String search = searchText(address);
        Optional<GeoAddress> located = geocoding.forward(search, geocodingLanguage);

        if (located.isEmpty()) {
            setPin(address, null, null, GeocodeConfidence.NONE);
            log.info("[Address] Could not place '{}' on a map; delivery will price from a fallback "
                    + "distance until the buyer confirms a pin", search);
            return;
        }

        GeoAddress result = located.get();
        setPin(address, result.getLatitude(), result.getLongitude(), result.getConfidence());
    }

    private static void setPin(Address address, Double latitude, Double longitude,
                               GeocodeConfidence confidence) {
        address.setLatitude(latitude);
        address.setLongitude(longitude);
        address.setGeocodeConfidence(confidence == null ? GeocodeConfidence.NONE : confidence);
        address.setGeocodedAt(latitude == null ? null : LocalDateTime.now());
    }

    /** Everything the geocoder can use, in the order it reads best. */
    private static String searchText(Address address) {
        return Stream.of(address.getStreet(), address.getCity(), address.getState(),
                         address.getPostalCode(), address.getCountryCode())
                .filter(part -> part != null && !part.isBlank())
                .map(String::trim)
                .collect(Collectors.joining(", "));
    }

    /**
     * One address, belonging to this owner, still in their book.
     *
     * <p>The ownership check is the query. A stranger's address comes back empty
     * and therefore reads as not-found rather than forbidden — confirming that
     * address 41 exists tells someone something about a person they have no
     * business knowing.
     */
    private Address requireLiveOwned(Long userId, Long addressId) {
        requireUserId(userId);
        return addressRepository.findLiveByIdAndUserId(addressId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Address", addressId));
    }

    private static AddressResponses.Address toResponse(Address address) {
        GeocodeConfidence confidence = address.getGeocodeConfidence() == null
                ? GeocodeConfidence.NONE
                : address.getGeocodeConfidence();
        boolean located = address.getLatitude() != null && address.getLongitude() != null;

        return new AddressResponses.Address(
                address.getId(),
                address.getLabel(),
                address.getFullName(),
                address.getPhone(),
                address.getStreet(),
                address.getApartmentSuite(),
                address.getCity(),
                address.getState(),
                address.getPostalCode(),
                address.getCountryCode(),
                address.getLatitude(),
                address.getLongitude(),
                located,
                confidence,
                located && confidence.needsConfirmation(),
                located && confidence.isDispatchable(),
                address.getPinConfirmedAt() != null,
                address.isDefault(),
                address.getGeocodedAt(),
                address.getCreatedAt());
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void apply(Address address, AddressRequest request) {
        address.setLabel(request.getLabel().trim());
        address.setFullName(request.getFullName().trim());
        address.setPhone(request.getPhone().trim());
        address.setStreet(request.getStreet().trim());
        address.setApartmentSuite(blankToNull(request.getApartmentSuite()));
        address.setCity(request.getCity().trim());
        address.setState(blankToNull(request.getState()));
        address.setPostalCode(blankToNull(request.getPostalCode()));
        address.setCountryCode(request.getCountryCode().trim().toUpperCase());

        if (request.getLatitude() != null && request.getLongitude() != null) {
            address.setLatitude(request.getLatitude());
            address.setLongitude(request.getLongitude());
            return;
        }
        locate(address);
    }

    /**
     * Places the address on a map from what the buyer typed.
     *
     * <p>Best-effort: a geocoder that is unreachable, unconfigured or simply
     * cannot find a rural address must not stop someone saving where they live.
     * The address is stored without coordinates and delivery falls back to a
     * scope distance, which the response flags so the client can ask again.
     */
    private void locate(Address address) {
        String search = String.join(", ",
                List.of(address.getStreet(), address.getCity(), address.getCountryCode()));
        // Defaulted here as well as on the property: the geocoder rejects a null
        // language, and a misconfigured property would otherwise look exactly
        // like an address nobody can find.
        String language = (geocodingLanguage == null || geocodingLanguage.isBlank()) ? "en" : geocodingLanguage;
        try {
            GeoAddress located = googleMapsService.getCoordinates(search, language);
            address.setLatitude(located.getLatitude());
            address.setLongitude(located.getLongitude());
        } catch (RuntimeException ex) {
            address.setLatitude(null);
            address.setLongitude(null);
            log.info("[Address] Could not place '{}' on a map; delivery will use a fallback distance: {}",
                    search, ex.getMessage());
        }
    }

    /**
     * A stranger's address reads as not-found rather than forbidden: an address
     * carries a name, a phone number and a location, and confirming that one
     * exists is itself worth withholding.
     */
    private Address requireOwnAddress(Long addressId, Long userId) {
        // One resolution for both surfaces, and it puts the ownership check in
        // the query rather than after it. Loading by id and comparing the owner
        // afterwards is the shape that eventually ships with the comparison
        // missing; it also could not see the deletedAt column, so it would have
        // gone on serving addresses their owner had removed.
        return requireLiveOwned(userId, addressId);
    }

    private Long requireUserId(Long userId) {
        if (userId == null) {
            throw new BadRequestException("An address belongs to a signed-in buyer");
        }
        return userId;
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
