package com.sujula.service.delivery;

import com.sujula.dto.request.delivery.ServiceabilityRequests;
import com.sujula.dto.response.delivery.ServiceabilityResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.DeliveryMode;
import com.sujula.model.constant.DeliveryScope;
import com.sujula.model.delivery.DeliveryContext;
import com.sujula.model.delivery.PickupPoint;
import com.sujula.model.user.Vendor;
import com.sujula.repository.PickupPointRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.ExchangeRateService;
import com.sujula.service.geo.GeocodingGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Whether goods can get from a seller to a buyer, how, and what it costs.
 *
 * <p>Two questions a shopper asks before there is a basket. "Do you deliver
 * here?" belongs on a product page, long before checkout, and answering it late
 * is how a buyer fills a cart they cannot complete. "What will shipping cost?"
 * is the row a cart has to show next to the subtotal.
 *
 * <p>Both price through {@link DeliveryPricingProperties#priceLeg} — the same
 * rate card the per-product quote uses — so the figure a shopper sees in the
 * cart is the figure checkout charges. A second pricing model here would be the
 * fastest way to make those two disagree.
 */
@Slf4j
@Service
public class ServiceabilityService {

    /** More than this and nobody scrolls; the query is capped to match. */
    private static final int MAX_PICKUP_POINTS = 10;
    private static final int DEFAULT_PICKUP_POINTS = 3;

    /**
     * How long a journey of this many kilometres takes, in whole days.
     *
     * <p>Bands rather than a formula, because delivery time is not linear in
     * distance: a parcel across Serekunda and a parcel to the far bank of the
     * river both wait for the same next run, and what changes between them is
     * which run. Counted from dispatch, not from the order — the vendor's own
     * handling time is theirs and is not known here.
     */
    private static final int[][] ETA_BANDS = {
            //  up to km,  min days, max days
            {   10,  1, 1 },
            {   30,  1, 2 },
            {  100,  2, 3 },
            {  400,  3, 5 },
            { 2000,  5, 10 },
            { Integer.MAX_VALUE, 7, 21 }
    };

    private final DeliveryPricingProperties properties;
    private final PickupPointRepository pickupPoints;
    private final VendorRepository vendors;
    private final GeocodingGateway geocoding;
    private final ExchangeRateService exchangeRates;
    private final DeliveryContextService contexts;

    public ServiceabilityService(DeliveryPricingProperties properties,
                                 PickupPointRepository pickupPoints, VendorRepository vendors,
                                 GeocodingGateway geocoding, ExchangeRateService exchangeRates,
                                 DeliveryContextService contexts) {
        this.properties = properties;
        this.pickupPoints = pickupPoints;
        this.vendors = vendors;
        this.geocoding = geocoding;
        this.exchangeRates = exchangeRates;
        this.contexts = contexts;
    }

    // ── Serviceability ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ServiceabilityResponses.Serviceability check(ServiceabilityRequests.Serviceability request,
                                                        Long userId) {
        Resolved origin = resolve(request.origin());
        Resolved destination = resolveDestination(request.destination(), request.deliveryContextId(), userId);

        Measure measure = measure(origin, destination);
        boolean crossBorder = origin.countryCode != null && destination.countryCode != null
                && !origin.countryCode.equals(destination.countryCode);

        List<ServiceabilityResponses.Option> options = List.of(
                homeDelivery(destination, measure),
                pickupPoint(destination, measure),
                vendorPickup(origin));

        List<ServiceabilityResponses.NearbyPickupPoint> nearby =
                nearestPickupPoints(destination, cap(request.nearestPickupPoints()));

        boolean deliverable = options.stream().anyMatch(ServiceabilityResponses.Option::available);

        return new ServiceabilityResponses.Serviceability(
                deliverable,
                measure.km,
                measure.estimated,
                crossBorder,
                origin.countryCode,
                destination.countryCode,
                options,
                nearby,
                message(deliverable, destination, measure, crossBorder));
    }

    /**
     * Home delivery, which is the only option that genuinely needs to know where
     * the buyer is.
     */
    private ServiceabilityResponses.Option homeDelivery(Resolved destination, Measure measure) {
        if (!destination.known()) {
            return new ServiceabilityResponses.Option(DeliveryMode.HOME_DELIVERY, false, null, null,
                    "Where the parcel should go is not known yet. Give an address or drop a pin.");
        }
        int[] eta = etaFor(measure.km);
        return new ServiceabilityResponses.Option(DeliveryMode.HOME_DELIVERY, true, eta[0], eta[1],
                measure.estimated
                        ? "Estimated: the exact distance could not be measured, so this may change."
                        : null);
    }

    /**
     * Collection from a hub, which needs a hub to exist within reach.
     *
     * <p>Offered whenever the destination's country has one — the buyer picks
     * which, and the one nearest them may still be an hour away, which is the
     * normal arrangement outside the Kombos and not a reason to hide the option.
     */
    private ServiceabilityResponses.Option pickupPoint(Resolved destination, Measure measure) {
        if (destination.countryCode == null) {
            return new ServiceabilityResponses.Option(DeliveryMode.PICKUP_POINT, false, null, null,
                    "No country resolved, so no collection points can be offered.");
        }
        boolean any = !pickupPoints.findCollectableIn(destination.countryCode).isEmpty();
        if (!any) {
            return new ServiceabilityResponses.Option(DeliveryMode.PICKUP_POINT, false, null, null,
                    "No collection points are open in " + destination.countryCode + " yet.");
        }
        // A hub run is a scheduled leg rather than a door-to-door one, so it is
        // no slower than home delivery and often quicker.
        int[] eta = etaFor(measure.km);
        return new ServiceabilityResponses.Option(DeliveryMode.PICKUP_POINT, true, eta[0], eta[1], null);
    }

    /**
     * Collecting from the seller, which is always possible and needs nothing
     * resolved: the buyer goes to the shop.
     */
    private ServiceabilityResponses.Option vendorPickup(Resolved origin) {
        return new ServiceabilityResponses.Option(DeliveryMode.VENDOR_PICKUP, true, 1, 1,
                origin.known() ? null : "Collect from the seller.");
    }

    // ── Quote ────────────────────────────────────────────────────────────────

    /**
     * What each way of receiving the goods costs.
     *
     * <p>Priced from the rate card in its own currency and converted once, so a
     * quote in dalasi and the same quote in sterling are the same journey at one
     * exchange rate rather than two roundings of two different sums.
     */
    @Transactional(readOnly = true)
    public ServiceabilityResponses.Quote quote(ServiceabilityRequests.Quote request, Long userId) {
        Resolved origin = resolve(request.origin());
        Resolved destination = resolveDestination(request.destination(), request.deliveryContextId(), userId);
        Measure measure = measure(origin, destination);

        BigDecimal weight = request.weightKg() == null || request.weightKg().signum() <= 0
                ? properties.getDefaultWeightKg()
                : request.weightKg();

        String target = normaliseCurrency(request.currency(), destination);
        String card = properties.getCurrency();

        BigDecimal rate = BigDecimal.ONE;
        boolean complete = true;
        if (!card.equalsIgnoreCase(target)) {
            Map<String, BigDecimal> rates = exchangeRates.getLatestRates(target, Set.of(card));
            BigDecimal found = rates == null ? null : rates.get(card);
            if (found == null) {
                // No rate: say so rather than quote the rate card's own currency
                // under someone else's symbol, which is how a buyer is charged
                // fifty pounds for a fifty-dalasi delivery.
                complete = false;
                log.warn("[Delivery] No exchange rate from {} to {} — per-mode quote is incomplete",
                        card, target);
            } else {
                rate = found;
            }
        }

        // Scope is not known here: this prices a basket the client has totalled,
        // not a list of products with their own scopes. Regional is the ordinary
        // case and the multiplier's baseline; checkout re-prices per product
        // against each one's real scope, which is what actually gets charged.
        DeliveryScope scope = crossBorder(origin, destination)
                ? DeliveryScope.NATIONAL
                : DeliveryScope.REGIIONAL;

        BigDecimal threshold = properties.getFreeAbove();
        boolean freeDelivery = threshold != null && threshold.signum() > 0
                && request.value() != null
                && request.value().compareTo(convert(threshold, rate)) >= 0;

        List<ServiceabilityResponses.ModePrice> prices = new ArrayList<>();
        for (DeliveryMode mode : DeliveryMode.values()) {
            prices.add(priceMode(mode, measure, weight, scope, rate, destination, freeDelivery, complete));
        }

        return new ServiceabilityResponses.Quote(
                target, measure.km, measure.estimated,
                weight.setScale(3, RoundingMode.HALF_UP), complete, prices);
    }

    private ServiceabilityResponses.ModePrice priceMode(DeliveryMode mode, Measure measure,
                                                        BigDecimal weight, DeliveryScope scope,
                                                        BigDecimal rate, Resolved destination,
                                                        boolean freeDelivery, boolean complete) {

        if (mode == DeliveryMode.HOME_DELIVERY && !destination.known()) {
            return new ServiceabilityResponses.ModePrice(mode, false, null, null, null, null,
                    "Where the parcel should go is not known yet.");
        }
        if (mode == DeliveryMode.VENDOR_PICKUP) {
            return new ServiceabilityResponses.ModePrice(mode, true, BigDecimal.ZERO, 1, 1,
                    "Collected from the store — nothing to deliver", null);
        }
        if (freeDelivery) {
            int[] eta = etaFor(measure.km);
            return new ServiceabilityResponses.ModePrice(mode, true, BigDecimal.ZERO, eta[0], eta[1],
                    "Free delivery on this basket", null);
        }

        BigDecimal cost = convert(properties.priceLeg(measure.km, weight, scope, mode), rate);
        int[] eta = etaFor(measure.km);
        return new ServiceabilityResponses.ModePrice(mode, true, cost, eta[0], eta[1], null,
                complete ? null : "No exchange rate available — this figure is not final.");
    }

    // ── Resolution ───────────────────────────────────────────────────────────

    /**
     * Turns whichever form the caller gave into a point.
     *
     * <p>Coordinates first, then an id worth looking up, then text worth
     * geocoding — most precise to least, so a caller that supplies two gets the
     * better one.
     */
    private Resolved resolve(ServiceabilityRequests.Point point) {
        if (point == null) {
            return Resolved.unknown();
        }
        if (point.hasCoordinates()
                && GeocodingGateway.isValidCoordinate(point.latitude(), point.longitude())) {
            return new Resolved(point.latitude(), point.longitude(), upper(point.countryCode()));
        }
        if (point.vendorId() != null) {
            Vendor vendor = vendors.findById(point.vendorId())
                    .orElseThrow(() -> new ResourceNotFoundException("Vendor", point.vendorId()));
            return new Resolved(vendor.getLatitude(), vendor.getLongitude(),
                    upper(vendor.getAddressCountryCode()));
        }
        if (point.pickupPointId() != null) {
            PickupPoint hub = pickupPoints.findById(point.pickupPointId())
                    .orElseThrow(() -> new ResourceNotFoundException("Pickup point", point.pickupPointId()));
            return new Resolved(hub.getLatitude(), hub.getLongitude(), upper(hub.getCountryCode()));
        }
        if (point.address() != null && !point.address().isBlank()) {
            return geocoding.forward(point.address(), null)
                    .map(found -> new Resolved(found.getLatitude(), found.getLongitude(),
                            upper(found.getCountryCode())))
                    .orElseGet(() -> new Resolved(null, null, upper(point.countryCode())));
        }
        return new Resolved(null, null, upper(point.countryCode()));
    }

    /**
     * The destination, preferring a context when one was named.
     *
     * <p>A context is the destination the cart and checkout already agreed on, so
     * quoting against it rather than against a repeated payload is what keeps the
     * three answers consistent.
     */
    private Resolved resolveDestination(ServiceabilityRequests.Point point, String contextId, Long userId) {
        if (contextId != null && !contextId.isBlank()) {
            DeliveryContext context = contexts.require(contextId, userId);
            return new Resolved(context.getLatitude(), context.getLongitude(),
                    upper(context.getCountryCode()));
        }
        return resolve(point);
    }

    /**
     * How far apart the two ends are.
     *
     * <p>When either end is unknown the answer is a scope fallback rather than
     * nothing: a shopper who has not yet typed an address still wants to see
     * roughly what shipping costs, and refusing to say anything is worse than
     * saying "about this much, and we will confirm".
     */
    private Measure measure(Resolved origin, Resolved destination) {
        if (origin.known() && destination.known()) {
            double km = Distances.haversineKm(origin.latitude, origin.longitude,
                                              destination.latitude, destination.longitude);
            return new Measure(BigDecimal.valueOf(km).setScale(3, RoundingMode.HALF_UP), false);
        }
        DeliveryScope scope = crossBorder(origin, destination)
                ? DeliveryScope.NATIONAL : DeliveryScope.REGIIONAL;
        return new Measure(properties.fallbackKmFor(scope).setScale(3, RoundingMode.HALF_UP), true);
    }

    private List<ServiceabilityResponses.NearbyPickupPoint> nearestPickupPoints(Resolved destination,
                                                                                int limit) {
        if (destination.countryCode == null) {
            return List.of();
        }
        List<PickupPoint> candidates = pickupPoints.findCollectableIn(destination.countryCode);
        if (candidates.isEmpty()) {
            return List.of();
        }

        // Without a destination pin there is nothing to rank by, so the country's
        // hubs are returned unranked rather than in an order pretending to mean
        // something.
        Comparator<PickupPoint> order = destination.known()
                ? Comparator.comparingDouble(hub -> Distances.haversineKm(
                        destination.latitude, destination.longitude, hub.getLatitude(), hub.getLongitude()))
                : Comparator.comparing(PickupPoint::getId);

        return candidates.stream()
                .sorted(order)
                .limit(limit)
                .map(hub -> new ServiceabilityResponses.NearbyPickupPoint(
                        hub.getId(), hub.getName(), hub.getCity(), hub.getAddressStreet(),
                        hub.getOpeningHours(), hub.getLatitude(), hub.getLongitude(),
                        destination.known()
                                ? BigDecimal.valueOf(Distances.haversineKm(
                                        destination.latitude, destination.longitude,
                                        hub.getLatitude(), hub.getLongitude()))
                                    .setScale(2, RoundingMode.HALF_UP)
                                : null))
                .toList();
    }

    // ─────────────────────────────────────────────────────────────────────────

    private static int[] etaFor(BigDecimal distanceKm) {
        double km = distanceKm == null ? 0 : distanceKm.doubleValue();
        for (int[] band : ETA_BANDS) {
            if (km <= band[0]) {
                return new int[]{band[1], band[2]};
            }
        }
        return new int[]{7, 21};
    }

    private static boolean crossBorder(Resolved origin, Resolved destination) {
        return origin.countryCode != null && destination.countryCode != null
                && !origin.countryCode.equals(destination.countryCode);
    }

    private static BigDecimal convert(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    private String normaliseCurrency(String requested, Resolved destination) {
        if (requested != null && requested.length() == 3) {
            return requested.toUpperCase(Locale.ROOT);
        }
        return properties.getCurrency();
    }

    private static int cap(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_PICKUP_POINTS;
        }
        return Math.min(requested, MAX_PICKUP_POINTS);
    }

    private static String message(boolean deliverable, Resolved destination, Measure measure,
                                  boolean crossBorder) {
        if (!deliverable) {
            return "Nothing can be arranged to this destination yet.";
        }
        if (!destination.known()) {
            return "Estimated from the country alone. Give an address or a pin for a firm answer.";
        }
        if (crossBorder) {
            return "This crosses a border — customs may add time that is not counted here.";
        }
        return measure.estimated
                ? "Estimated: the exact distance could not be measured."
                : null;
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    /** One end of the journey, once it has been worked out. */
    private record Resolved(Double latitude, Double longitude, String countryCode) {
        static Resolved unknown() {
            return new Resolved(null, null, null);
        }
        boolean known() {
            return latitude != null && longitude != null;
        }
    }

    /** How far, and whether that was measured or assumed. */
    private record Measure(BigDecimal km, boolean estimated) {}
}
