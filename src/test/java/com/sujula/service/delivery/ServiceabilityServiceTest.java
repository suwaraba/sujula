package com.sujula.service.delivery;

import com.sujula.dto.request.delivery.ServiceabilityRequests;
import com.sujula.dto.response.delivery.ServiceabilityResponses;
import com.sujula.model.constant.DeliveryMode;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.delivery.PickupPoint;
import com.sujula.repository.PickupPointRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.ExchangeRateService;
import com.sujula.service.GoogleMapsService;
import com.sujula.service.geo.GeocodingGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Whether goods can get there, and what that costs — the two questions a
 * shopper asks before there is a basket.
 */
class ServiceabilityServiceTest {

    // Serekunda and Brikama: about 20 km apart, which is a real journey on a
    // real road and lands in a different ETA band from a cross-town one.
    private static final double SEREKUNDA_LAT = 13.4383, SEREKUNDA_LNG = -16.6781;
    private static final double BRIKAMA_LAT = 13.2714, BRIKAMA_LNG = -16.6492;

    private PickupPointRepository pickupPoints;
    private ExchangeRateService exchangeRates;
    private DeliveryPricingProperties properties;
    private ServiceabilityService service;

    @BeforeEach
    void setUp() {
        pickupPoints = mock(PickupPointRepository.class);
        VendorRepository vendors = mock(VendorRepository.class);
        exchangeRates = mock(ExchangeRateService.class);
        DeliveryContextService contexts = mock(DeliveryContextService.class);
        properties = new DeliveryPricingProperties();

        GoogleMapsService maps = mock(GoogleMapsService.class);
        // Unconfigured on purpose: most of these answers must hold without a
        // geocoder, because most deployments will not have one on day one.
        GeocodingGateway geocoding = new GeocodingGateway(maps, "en", "");

        when(pickupPoints.findCollectableIn(any())).thenReturn(List.of());

        service = new ServiceabilityService(properties, pickupPoints, vendors, geocoding,
                exchangeRates, contexts);
    }

    private static ServiceabilityRequests.Point at(double lat, double lng, String country) {
        return new ServiceabilityRequests.Point(lat, lng, null, null, null, country);
    }

    private static PickupPoint hub(Long id, String name, double lat, double lng) {
        PickupPoint point = new PickupPoint();
        point.setId(id);
        point.setName(name);
        point.setCity("Serekunda");
        point.setAddressStreet("Kairaba Avenue");
        point.setCountryCode("GM");
        point.setLatitude(lat);
        point.setLongitude(lng);
        point.setActive(true);
        point.setStatus(PartnerStatus.APPROVED);
        return point;
    }

    private ServiceabilityResponses.Serviceability check(ServiceabilityRequests.Point origin,
                                                         ServiceabilityRequests.Point destination) {
        return service.check(
                new ServiceabilityRequests.Serviceability(origin, destination, null, null), null);
    }

    // ── Serviceability ───────────────────────────────────────────────────────

    @Test
    void aJourneyWithinTheCountryIsDeliverable() {
        ServiceabilityResponses.Serviceability result = check(
                at(SEREKUNDA_LAT, SEREKUNDA_LNG, "GM"), at(BRIKAMA_LAT, BRIKAMA_LNG, "GM"));

        assertTrue(result.deliverable());
        assertFalse(result.crossBorder());
        assertFalse(result.distanceEstimated());
        assertTrue(result.distanceKm().doubleValue() > 15 && result.distanceKm().doubleValue() < 25,
                "Serekunda to Brikama is about 20 km, got " + result.distanceKm());
    }

    /**
     * Collecting from the seller needs nothing resolved — the buyer walks to the
     * shop — so it must stay available even when nothing else is known.
     */
    @Test
    void collectingFromTheSellerIsAlwaysAvailable() {
        ServiceabilityResponses.Serviceability result = check(null, null);

        ServiceabilityResponses.Option pickup = optionFor(result, DeliveryMode.VENDOR_PICKUP);
        assertTrue(pickup.available());
        assertTrue(result.deliverable(), "something can always be arranged");
    }

    /** A disabled option with no explanation is the worst thing a checkout can show. */
    @Test
    void anUnavailableModeSaysWhy() {
        ServiceabilityResponses.Serviceability result = check(
                at(SEREKUNDA_LAT, SEREKUNDA_LNG, "GM"), null);

        ServiceabilityResponses.Option home = optionFor(result, DeliveryMode.HOME_DELIVERY);
        assertFalse(home.available());
        assertNotNull(home.reason());
    }

    @Test
    void collectionIsOfferedOnlyWhereAHubIsOpen() {
        when(pickupPoints.findCollectableIn("GM")).thenReturn(List.of(hub(1L, "Westfield", 13.44, -16.68)));

        ServiceabilityResponses.Serviceability withHub = check(
                at(SEREKUNDA_LAT, SEREKUNDA_LNG, "GM"), at(BRIKAMA_LAT, BRIKAMA_LNG, "GM"));
        assertTrue(optionFor(withHub, DeliveryMode.PICKUP_POINT).available());

        when(pickupPoints.findCollectableIn("SN")).thenReturn(List.of());
        ServiceabilityResponses.Serviceability without = check(
                at(SEREKUNDA_LAT, SEREKUNDA_LNG, "GM"), at(14.69, -17.44, "SN"));
        assertFalse(optionFor(without, DeliveryMode.PICKUP_POINT).available());
    }

    @Test
    void hubsComeBackNearestFirst() {
        when(pickupPoints.findCollectableIn("GM")).thenReturn(List.of(
                hub(1L, "Far", BRIKAMA_LAT, BRIKAMA_LNG),
                hub(2L, "Near", SEREKUNDA_LAT + 0.002, SEREKUNDA_LNG)));

        ServiceabilityResponses.Serviceability result = service.check(
                new ServiceabilityRequests.Serviceability(
                        at(SEREKUNDA_LAT, SEREKUNDA_LNG, "GM"),
                        at(SEREKUNDA_LAT, SEREKUNDA_LNG, "GM"), null, 5), null);

        assertEquals(2, result.nearestPickupPoints().size());
        assertEquals("Near", result.nearestPickupPoints().get(0).name());
        assertTrue(result.nearestPickupPoints().get(0).distanceKm()
                .compareTo(result.nearestPickupPoints().get(1).distanceKm()) < 0);
    }

    @Test
    void crossingABorderIsFlagged() {
        ServiceabilityResponses.Serviceability result = check(
                at(SEREKUNDA_LAT, SEREKUNDA_LNG, "GM"), at(14.69, -17.44, "SN"));

        assertTrue(result.crossBorder());
        assertTrue(result.message().contains("customs"));
    }

    /**
     * A longer journey takes longer. Not a formula — bands — but the ordering has
     * to hold or the ETA means nothing.
     */
    @Test
    void aFurtherDestinationTakesLonger() {
        ServiceabilityResponses.Option near = optionFor(
                check(at(SEREKUNDA_LAT, SEREKUNDA_LNG, "GM"),
                      at(SEREKUNDA_LAT + 0.01, SEREKUNDA_LNG, "GM")),
                DeliveryMode.HOME_DELIVERY);

        ServiceabilityResponses.Option far = optionFor(
                check(at(SEREKUNDA_LAT, SEREKUNDA_LNG, "GM"), at(51.52, -0.16, "GB")),
                DeliveryMode.HOME_DELIVERY);

        assertTrue(far.etaMaxDays() > near.etaMaxDays(),
                "London must not arrive as fast as across Serekunda");
    }

    /** A shopper who has not typed an address still wants a rough figure. */
    @Test
    void anUnknownDestinationFallsBackRatherThanRefusing() {
        ServiceabilityResponses.Serviceability result = check(
                at(SEREKUNDA_LAT, SEREKUNDA_LNG, "GM"), null);

        assertTrue(result.distanceEstimated());
        assertNotNull(result.distanceKm());
    }

    // ── Quote ────────────────────────────────────────────────────────────────

    private ServiceabilityResponses.Quote quote(BigDecimal weight, BigDecimal value, String currency) {
        return service.quote(new ServiceabilityRequests.Quote(
                at(SEREKUNDA_LAT, SEREKUNDA_LNG, "GM"), at(BRIKAMA_LAT, BRIKAMA_LNG, "GM"),
                null, weight, value, currency), null);
    }

    @Test
    void everyModeIsPriced() {
        ServiceabilityResponses.Quote result = quote(new BigDecimal("2.0"), null, "GMD");

        assertEquals(DeliveryMode.values().length, result.prices().size());
        assertTrue(result.complete());
        assertEquals("GMD", result.currency());
    }

    @Test
    void collectingFromTheSellerCostsNothing() {
        ServiceabilityResponses.ModePrice vendorPickup =
                priceFor(quote(new BigDecimal("2.0"), null, "GMD"), DeliveryMode.VENDOR_PICKUP);

        assertEquals(0, vendorPickup.cost().compareTo(BigDecimal.ZERO));
        assertNotNull(vendorPickup.waivedReason());
    }

    /** A hub run is a scheduled leg, so it undercuts a door-to-door one. */
    @Test
    void collectingAtAHubCostsLessThanDeliveryToTheDoor() {
        ServiceabilityResponses.Quote result = quote(new BigDecimal("2.0"), null, "GMD");

        assertTrue(priceFor(result, DeliveryMode.PICKUP_POINT).cost()
                .compareTo(priceFor(result, DeliveryMode.HOME_DELIVERY).cost()) < 0);
    }

    @Test
    void aHeavierBasketCostsMore() {
        BigDecimal light = priceFor(quote(new BigDecimal("0.5"), null, "GMD"),
                DeliveryMode.HOME_DELIVERY).cost();
        BigDecimal heavy = priceFor(quote(new BigDecimal("25.0"), null, "GMD"),
                DeliveryMode.HOME_DELIVERY).cost();

        assertTrue(heavy.compareTo(light) > 0);
    }

    /**
     * Without a rate, saying nothing is right. Quoting the rate card's own
     * currency under someone else's symbol is how a buyer is charged fifty pounds
     * for a fifty-dalasi delivery.
     */
    @Test
    void aMissingExchangeRateIsDeclaredRatherThanGuessed() {
        when(exchangeRates.getLatestRates(any(), any())).thenReturn(Map.of());

        ServiceabilityResponses.Quote result = quote(new BigDecimal("2.0"), null, "GBP");

        assertFalse(result.complete(), "an unconvertible quote must not be presented as final");
        assertNotNull(priceFor(result, DeliveryMode.HOME_DELIVERY).reason());
    }

    @Test
    void aRateIsAppliedWhenThereIsOne() {
        when(exchangeRates.getLatestRates(any(), any()))
                .thenReturn(Map.of(properties.getCurrency(), new BigDecimal("0.012")));

        ServiceabilityResponses.Quote converted = quote(new BigDecimal("2.0"), null, "GBP");
        ServiceabilityResponses.Quote home = quote(new BigDecimal("2.0"), null, "GMD");

        assertTrue(converted.complete());
        assertTrue(priceFor(converted, DeliveryMode.HOME_DELIVERY).cost()
                .compareTo(priceFor(home, DeliveryMode.HOME_DELIVERY).cost()) < 0);
    }

    @Test
    void aBasketOverTheThresholdShipsFree() {
        properties.setFreeAbove(new BigDecimal("1000"));

        ServiceabilityResponses.ModePrice home = priceFor(
                quote(new BigDecimal("2.0"), new BigDecimal("5000"), "GMD"),
                DeliveryMode.HOME_DELIVERY);

        assertEquals(0, home.cost().compareTo(BigDecimal.ZERO));
        assertNotNull(home.waivedReason());
    }

    @Test
    void anAbsentWeightUsesTheConfiguredDefault() {
        ServiceabilityResponses.Quote result = quote(null, null, "GMD");

        assertEquals(0, result.weightKg().compareTo(properties.getDefaultWeightKg()));
    }

    /**
     * The property this layer exists to guarantee: the cart's shipping row and
     * the price checkout works out come from one formula, so they cannot drift.
     */
    @Test
    void theQuoteUsesTheSameRateCardAsCheckout() {
        ServiceabilityResponses.Quote result = quote(new BigDecimal("2.0"), null, "GMD");

        BigDecimal direct = properties.priceLeg(result.distanceKm(), new BigDecimal("2.0"),
                com.sujula.model.constant.DeliveryScope.REGIIONAL, DeliveryMode.HOME_DELIVERY);

        assertEquals(0, priceFor(result, DeliveryMode.HOME_DELIVERY).cost().compareTo(direct));
    }

    // ─────────────────────────────────────────────────────────────────────────

    private static ServiceabilityResponses.Option optionFor(
            ServiceabilityResponses.Serviceability result, DeliveryMode mode) {
        return result.options().stream()
                .filter(option -> option.mode() == mode)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no option for " + mode));
    }

    private static ServiceabilityResponses.ModePrice priceFor(
            ServiceabilityResponses.Quote quote, DeliveryMode mode) {
        return quote.prices().stream()
                .filter(price -> price.mode() == mode)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no price for " + mode));
    }
}
