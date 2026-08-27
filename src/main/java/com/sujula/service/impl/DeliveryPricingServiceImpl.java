package com.sujula.service.impl;

import com.sujula.dto.request.delivery.DeliveryQuoteRequest;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.DeliveryMode;
import com.sujula.model.constant.DeliveryScope;
import com.sujula.model.delivery.PickupPoint;
import com.sujula.model.order.OrderItem;
import com.sujula.model.products.Product;
import com.sujula.model.user.Vendor;
import com.sujula.repository.PickupPointRepository;
import com.sujula.repository.product.ProductRepository;
import com.sujula.service.DeliveryPricingService;
import com.sujula.service.ExchangeRateService;
import com.sujula.service.GoogleMapsService;
import com.sujula.service.cart.RateTable;
import com.sujula.service.delivery.DeliveryDestination;
import com.sujula.service.delivery.DeliveryItem;
import com.sujula.service.delivery.DeliveryPricingProperties;
import com.sujula.service.delivery.DeliveryQuote;
import com.sujula.dto.GeoAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Distance-and-weight delivery pricing, one leg per product.
 *
 * <p>A leg is priced as {@code base + per-km beyond the included distance +
 * per-kg beyond the included weight}, then scaled by the product's delivery
 * scope (a national parcel costs more per identical km than a local one) and by
 * how the buyer receives it (to the door, to a hub, or collected in store), and
 * finally clamped to the configured floor and ceiling.
 *
 * <p>Distance is the great-circle distance from where the product actually
 * ships from — its own coordinates when the vendor recorded them per product,
 * otherwise the vendor's store — to the destination. When either end has no
 * coordinates the destination address is geocoded once per quote; if that is
 * unavailable too, a scope-based fallback distance is used and the leg is
 * flagged as estimated rather than failing the checkout.
 *
 * <p>Everything is computed in the rate card's currency and converted once into
 * the buyer's, so two vendors an equal distance away quote an equal price
 * regardless of the currency each settles in.
 */
@Service
public class DeliveryPricingServiceImpl implements DeliveryPricingService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryPricingServiceImpl.class);

    /** Mean Earth radius, in km. */
    private static final double EARTH_RADIUS_KM = 6371.0088;

    private static final int DISTANCE_SCALE = 3;
    private static final int WEIGHT_SCALE = 3;

    private final DeliveryPricingProperties properties;
    private final ExchangeRateService exchangeRateService;
    private final GoogleMapsService googleMapsService;
    private final ProductRepository productRepository;
    private final PickupPointRepository pickupPointRepository;

    public DeliveryPricingServiceImpl(DeliveryPricingProperties properties,
                                      ExchangeRateService exchangeRateService,
                                      GoogleMapsService googleMapsService,
                                      ProductRepository productRepository,
                                      PickupPointRepository pickupPointRepository) {
        this.properties = properties;
        this.exchangeRateService = exchangeRateService;
        this.googleMapsService = googleMapsService;
        this.productRepository = productRepository;
        this.pickupPointRepository = pickupPointRepository;
    }

    @Override
    public DeliveryQuote quote(List<DeliveryItem> items, DeliveryDestination destination,
                               DeliveryMode mode, String displayCurrency) {
        return price(items, destination, mode, displayCurrency);
    }

    @Override
    public DeliveryQuote quote(DeliveryQuoteRequest request) {
        DeliveryMode mode = request.getMode() != null ? request.getMode() : DeliveryMode.HOME_DELIVERY;

        List<DeliveryItem> items = new ArrayList<>();
        for (DeliveryQuoteRequest.Line line : request.getItems()) {
            Product product = productRepository.findById(line.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product", line.getProductId()));
            items.add(DeliveryItem.of(product, line.getQuantity()));
        }

        DeliveryDestination destination;
        if (mode == DeliveryMode.PICKUP_POINT) {
            if (request.getPickupPointId() == null) {
                throw new BadRequestException("A pickup point must be chosen to quote a pickup-point delivery");
            }
            PickupPoint point = pickupPointRepository.findById(request.getPickupPointId())
                    .orElseThrow(() -> new ResourceNotFoundException("PickupPoint", request.getPickupPointId()));
            destination = DeliveryDestination.of(point);
        } else {
            DeliveryQuoteRequest.Destination requested = request.getDestination();
            if (requested == null && mode != DeliveryMode.VENDOR_PICKUP) {
                throw new BadRequestException("A destination is required to quote a delivery");
            }
            destination = requested == null ? null : new DeliveryDestination(
                    requested.getLatitude(), requested.getLongitude(), requested.getAddress(),
                    requested.getCity(), requested.getState(), requested.getPostalCode(),
                    requested.getCountryCode());
        }

        return price(items, destination, mode, request.getCurrency());
    }

    @Override
    public DeliveryQuote quoteOrderItems(List<OrderItem> items, DeliveryDestination destination,
                                         DeliveryMode mode, String displayCurrency) {
        List<DeliveryItem> lines = new ArrayList<>(items.size());
        for (OrderItem item : items) {
            // totalPriceConverted is this line's value in the order's display
            // currency — the same currency the quote is returned in, which is
            // what the free-delivery threshold has to be compared against.
            lines.add(new DeliveryItem(item.getProduct(), item.getQuantity(), item.getTotalPriceConverted()));
        }
        return price(lines, destination, mode, displayCurrency);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private DeliveryQuote price(List<DeliveryItem> items, DeliveryDestination destination,
                                DeliveryMode mode, String displayCurrency) {
        DeliveryMode effectiveMode = mode != null ? mode : DeliveryMode.HOME_DELIVERY;
        String pricingCurrency = properties.getCurrency().toUpperCase();
        String target = (displayCurrency == null || displayCurrency.isBlank())
                ? pricingCurrency : displayCurrency.trim().toUpperCase();

        if (items == null || items.isEmpty()) {
            return new DeliveryQuote(target, BigDecimal.ZERO, true, List.of());
        }

        RateTable rates = ratesInto(target, pricingCurrency);
        boolean convertible = rates.canConvert(pricingCurrency);

        // Geocoding is a network call: resolve the destination once for the whole
        // basket rather than once per product.
        DeliveryDestination resolved = withCoordinates(destination);

        List<Leg> legs = new ArrayList<>(items.size());
        for (DeliveryItem item : items) {
            legs.add(priceLeg(item, resolved, effectiveMode));
        }
        waiveWhereFreeDeliveryApplies(legs, items, rates, pricingCurrency);

        List<DeliveryQuote.DeliveryLeg> priced = new ArrayList<>(legs.size());
        BigDecimal total = BigDecimal.ZERO;
        for (Leg leg : legs) {
            BigDecimal cost = convertible ? rates.convert(leg.cost, pricingCurrency) : null;
            if (cost != null) {
                total = total.add(cost);
            }
            priced.add(new DeliveryQuote.DeliveryLeg(
                    leg.productId, leg.vendorId, leg.productName, leg.quantity,
                    leg.weightKg, leg.distanceKm, leg.distanceEstimated, effectiveMode,
                    cost, leg.waivedReason));
        }

        if (!convertible) {
            log.warn("[Delivery] No exchange rate from {} to {} — delivery cannot be quoted",
                    pricingCurrency, target);
            return new DeliveryQuote(target, null, false, priced);
        }
        return new DeliveryQuote(target, RateTable.round(total), true, priced);
    }

    /** Prices one product's leg in the rate card's own currency. */
    private Leg priceLeg(DeliveryItem item, DeliveryDestination destination, DeliveryMode mode) {
        Product product = item.product();
        if (product == null) {
            throw new BadRequestException("Cannot price delivery for a missing product");
        }
        Vendor vendor = product.getVendor();
        DeliveryScope scope = product.getDeliveryScope();

        BigDecimal weightKg = billableWeight(product, item.quantity());
        Distance distance = distanceFor(product, vendor, destination, scope);

        Leg leg = new Leg();
        leg.productId = product.getId();
        leg.vendorId = vendor != null ? vendor.getId() : null;
        leg.productName = product.getName();
        leg.quantity = item.quantity();
        leg.weightKg = weightKg;
        leg.distanceKm = distance.km;
        leg.distanceEstimated = distance.estimated;

        if (mode == DeliveryMode.VENDOR_PICKUP) {
            leg.cost = BigDecimal.ZERO;
            leg.waivedReason = "Collected from the store — nothing to deliver";
            return leg;
        }

        BigDecimal chargeableKm = distance.km.subtract(properties.getIncludedKm()).max(BigDecimal.ZERO);
        BigDecimal chargeableKg = weightKg.subtract(properties.getIncludedKg()).max(BigDecimal.ZERO);

        BigDecimal cost = properties.getBaseFee()
                .add(properties.getPerKm().multiply(chargeableKm))
                .add(properties.getPerKg().multiply(chargeableKg))
                .multiply(properties.scopeMultiplierFor(scope))
                .multiply(properties.modeMultiplierFor(mode));

        cost = cost.max(properties.getMinFee());
        if (properties.getMaxFee() != null) {
            cost = cost.min(properties.getMaxFee());
        }
        leg.cost = cost.setScale(RateTable.MONEY_SCALE, RoundingMode.HALF_UP);
        return leg;
    }

    /**
     * Waives whole vendors whose goods in this basket reach the free-delivery
     * threshold. Applied per vendor, not per order: the threshold is the
     * vendor's incentive to sell more, and one vendor's big basket should not
     * pay for another vendor's parcel.
     */
    private void waiveWhereFreeDeliveryApplies(List<Leg> legs, List<DeliveryItem> items,
                                               RateTable rates, String pricingCurrency) {
        BigDecimal threshold = properties.getFreeAbove();
        if (threshold == null || threshold.signum() <= 0) {
            return;
        }
        BigDecimal thresholdInTarget = rates.convert(threshold, pricingCurrency);
        if (thresholdInTarget == null) {
            return;   // no rate: charge normally rather than give delivery away
        }

        Map<Long, BigDecimal> valueByVendor = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            BigDecimal lineValue = items.get(i).lineValue();
            Long vendorId = legs.get(i).vendorId;
            if (lineValue == null || vendorId == null) {
                continue;
            }
            valueByVendor.merge(vendorId, lineValue, BigDecimal::add);
        }

        for (Leg leg : legs) {
            BigDecimal vendorValue = valueByVendor.get(leg.vendorId);
            if (vendorValue != null && vendorValue.compareTo(thresholdInTarget) >= 0 && !leg.isWaived()) {
                leg.cost = BigDecimal.ZERO;
                leg.waivedReason = "Free delivery on orders over "
                        + thresholdInTarget + " " + rates.target() + " from this vendor";
            }
        }
    }

    // ── Weight ────────────────────────────────────────────────────────────────

    /**
     * Unit weight × quantity, falling back to the configured default for a
     * product whose vendor never recorded one — an unweighed product must still
     * be priced, and treating it as weightless would let vendors avoid the
     * weight charge by leaving the field empty.
     */
    private BigDecimal billableWeight(Product product, int quantity) {
        Double unit = product.getWeightKg();
        BigDecimal unitWeight = (unit != null && unit > 0)
                ? BigDecimal.valueOf(unit)
                : properties.getDefaultWeightKg();
        return unitWeight.multiply(BigDecimal.valueOf(Math.max(quantity, 1)))
                .setScale(WEIGHT_SCALE, RoundingMode.HALF_UP);
    }

    // ── Distance ──────────────────────────────────────────────────────────────

    /**
     * Great-circle distance from where this product ships from to the buyer.
     *
     * <p>A product may carry its own coordinates (a vendor with more than one
     * location); otherwise the vendor's store is the origin. With no usable
     * coordinates at either end, the leg falls back to a scope-based distance
     * and says so, because a missing coordinate is not a reason to block a sale.
     */
    private Distance distanceFor(Product product, Vendor vendor,
                                 DeliveryDestination destination, DeliveryScope scope) {
        Double originLat = product.getLatitude();
        Double originLng = product.getLongitude();
        if ((originLat == null || originLng == null) && vendor != null) {
            originLat = vendor.getLatitude();
            originLng = vendor.getLongitude();
        }

        if (originLat != null && originLng != null && destination != null && destination.hasCoordinates()) {
            BigDecimal km = BigDecimal.valueOf(
                            haversineKm(originLat, originLng, destination.latitude(), destination.longitude()))
                    .setScale(DISTANCE_SCALE, RoundingMode.HALF_UP);
            return new Distance(km, false);
        }
        return new Distance(properties.fallbackKmFor(scope).setScale(DISTANCE_SCALE, RoundingMode.HALF_UP), true);
    }

    /** Fills in the destination's coordinates by geocoding its address, when it has none. */
    private DeliveryDestination withCoordinates(DeliveryDestination destination) {
        if (destination == null || destination.hasCoordinates()) {
            return destination;
        }
        String search = destination.toSearchText();
        if (search == null) {
            return destination;
        }
        try {
            GeoAddress geocoded = googleMapsService.getCoordinates(search, "en");
            return new DeliveryDestination(geocoded.getLatitude(), geocoded.getLongitude(),
                    destination.addressLine(), destination.city(), destination.state(),
                    destination.postalCode(), destination.countryCode());
        } catch (RuntimeException ex) {
            // Geocoding is best-effort: an unreachable or unconfigured geocoder
            // must not stop a buyer from checking out.
            log.warn("[Delivery] Could not geocode '{}' — falling back to scope distances: {}",
                    search, ex.getMessage());
            return destination;
        }
    }

    static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    // ── Currency ──────────────────────────────────────────────────────────────

    private RateTable ratesInto(String target, String pricingCurrency) {
        if (target.equals(pricingCurrency)) {
            return new RateTable(target, Map.of());
        }
        Map<String, BigDecimal> rates =
                exchangeRateService.getLatestRates(target, Set.of(pricingCurrency));
        return new RateTable(target, rates);
    }

    // ── Working state ─────────────────────────────────────────────────────────

    /** A leg mid-computation, still denominated in the rate card's currency. */
    private static final class Leg {
        Long productId;
        Long vendorId;
        String productName;
        int quantity;
        BigDecimal weightKg;
        BigDecimal distanceKm;
        boolean distanceEstimated;
        BigDecimal cost;
        String waivedReason;

        boolean isWaived() {
            return waivedReason != null;
        }
    }

    private record Distance(BigDecimal km, boolean estimated) {}
}
