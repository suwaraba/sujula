package com.sujula.service;

import com.sujula.dto.request.ExchangeRateRequest;
import com.sujula.dto.response.ExchangeRateResponse;
import com.sujula.model.constant.DeliveryMode;
import com.sujula.model.constant.DeliveryScope;
import com.sujula.model.products.Product;
import com.sujula.model.user.Vendor;
import com.sujula.service.delivery.DeliveryDestination;
import com.sujula.service.delivery.DeliveryItem;
import com.sujula.service.delivery.DeliveryPricingProperties;
import com.sujula.service.delivery.DeliveryQuote;
import com.sujula.service.impl.DeliveryPricingServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pricing arithmetic, without Spring or a database — the parts a checkout
 * total depends on and a regression would silently mis-charge for.
 */
class DeliveryPricingServiceImplTest {

    /** Banjul-ish and Serekunda-ish: about 12 km apart. */
    private static final double ORIGIN_LAT = 13.4549;
    private static final double ORIGIN_LNG = -16.5790;
    private static final double DEST_LAT = 13.4383;
    private static final double DEST_LNG = -16.6781;

    private DeliveryPricingProperties properties;
    private DeliveryPricingServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new DeliveryPricingProperties();
        properties.setCurrency("GMD");
        properties.setBaseFee(new BigDecimal("50.00"));
        properties.setIncludedKm(new BigDecimal("3"));
        properties.setPerKm(new BigDecimal("10.00"));
        properties.setIncludedKg(new BigDecimal("1"));
        properties.setPerKg(new BigDecimal("20.00"));
        properties.setMinFee(new BigDecimal("50.00"));
        properties.setDefaultWeightKg(new BigDecimal("0.50"));
        service = new DeliveryPricingServiceImpl(properties, new FixedRates(), null, null, null);
    }

    @Test
    void pricesEachProductOnItsOwnDistanceAndWeight() {
        Vendor near = vendor(1L, ORIGIN_LAT, ORIGIN_LNG);
        Vendor far = vendor(2L, 13.2870, -16.6500);   // ~19 km from the destination

        Product light = product(10L, near, 0.5, DeliveryScope.REGIIONAL);
        Product heavy = product(20L, far, 8.0, DeliveryScope.REGIIONAL);

        DeliveryQuote quote = service.quote(
                List.of(DeliveryItem.of(light, 1), DeliveryItem.of(heavy, 1)),
                destination(), DeliveryMode.HOME_DELIVERY, "GMD");

        assertTrue(quote.complete());
        assertEquals(2, quote.legs().size());

        DeliveryQuote.DeliveryLeg lightLeg = quote.legs().get(0);
        DeliveryQuote.DeliveryLeg heavyLeg = quote.legs().get(1);

        // Each leg carries its own distance: two vendors, two origins.
        assertFalse(lightLeg.distanceEstimated());
        assertTrue(heavyLeg.distanceKm().compareTo(lightLeg.distanceKm()) > 0,
                "the further vendor's leg must be measured as longer");

        // The heavier parcel from further away costs more, and the legs sum to the total.
        assertTrue(heavyLeg.cost().compareTo(lightLeg.cost()) > 0);
        assertEquals(lightLeg.cost().add(heavyLeg.cost()), quote.total());
    }

    @Test
    void chargesForWeightBeyondTheIncludedAllowance() {
        Vendor vendor = vendor(1L, ORIGIN_LAT, ORIGIN_LNG);
        Product product = product(10L, vendor, 3.0, DeliveryScope.REGIIONAL);

        BigDecimal one = service.quote(List.of(DeliveryItem.of(product, 1)),
                destination(), DeliveryMode.HOME_DELIVERY, "GMD").legs().get(0).cost();
        BigDecimal two = service.quote(List.of(DeliveryItem.of(product, 2)),
                destination(), DeliveryMode.HOME_DELIVERY, "GMD").legs().get(0).cost();

        // Two units weigh 6 kg against 3 kg: three extra chargeable kg at 20.00.
        assertEquals(new BigDecimal("60.00"), two.subtract(one));
    }

    @Test
    void scalesWithDeliveryScope() {
        Vendor vendor = vendor(1L, ORIGIN_LAT, ORIGIN_LNG);
        properties.getScopeMultiplier().put(DeliveryScope.NATIONAL, new BigDecimal("2.00"));

        BigDecimal regional = service.quote(
                List.of(DeliveryItem.of(product(10L, vendor, 2.0, DeliveryScope.REGIIONAL), 1)),
                destination(), DeliveryMode.HOME_DELIVERY, "GMD").legs().get(0).cost();
        BigDecimal national = service.quote(
                List.of(DeliveryItem.of(product(11L, vendor, 2.0, DeliveryScope.NATIONAL), 1)),
                destination(), DeliveryMode.HOME_DELIVERY, "GMD").legs().get(0).cost();

        assertEquals(regional.multiply(new BigDecimal("2")).stripTrailingZeros(),
                national.stripTrailingZeros());
    }

    @Test
    void collectingFromTheStoreCostsNothing() {
        Vendor vendor = vendor(1L, ORIGIN_LAT, ORIGIN_LNG);
        DeliveryQuote quote = service.quote(
                List.of(DeliveryItem.of(product(10L, vendor, 2.0, DeliveryScope.REGIIONAL), 1)),
                destination(), DeliveryMode.VENDOR_PICKUP, "GMD");

        assertEquals(0, quote.total().compareTo(BigDecimal.ZERO));
        assertTrue(quote.legs().get(0).isWaived());
    }

    @Test
    void fallsBackToScopeDistanceWhenNothingCanBeLocated() {
        Vendor vendor = vendor(1L, null, null);
        DeliveryQuote quote = service.quote(
                List.of(DeliveryItem.of(product(10L, vendor, 1.0, DeliveryScope.NATIONAL), 1)),
                new DeliveryDestination(null, null, null, null, null, null, null),
                DeliveryMode.HOME_DELIVERY, "GMD");

        DeliveryQuote.DeliveryLeg leg = quote.legs().get(0);
        assertTrue(leg.distanceEstimated(), "a leg priced without coordinates must say so");
        assertEquals(0, leg.distanceKm().compareTo(properties.fallbackKmFor(DeliveryScope.NATIONAL)));
        assertTrue(leg.cost().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void waivesDeliveryPerVendorOnceThatVendorsGoodsReachTheThreshold() {
        properties.setFreeAbove(new BigDecimal("1000.00"));
        Vendor generous = vendor(1L, ORIGIN_LAT, ORIGIN_LNG);
        Vendor other = vendor(2L, ORIGIN_LAT, ORIGIN_LNG);

        DeliveryQuote quote = service.quote(List.of(
                        new DeliveryItem(product(10L, generous, 1.0, DeliveryScope.REGIIONAL), 1,
                                new BigDecimal("1500.00")),
                        new DeliveryItem(product(20L, other, 1.0, DeliveryScope.REGIIONAL), 1,
                                new BigDecimal("200.00"))),
                destination(), DeliveryMode.HOME_DELIVERY, "GMD");

        assertTrue(quote.legs().get(0).isWaived(), "the vendor over the threshold delivers free");
        assertFalse(quote.legs().get(1).isWaived(), "the other vendor still charges");
        assertEquals(0, quote.legs().get(0).cost().compareTo(BigDecimal.ZERO));
        assertEquals(quote.legs().get(1).cost(), quote.total());
    }

    @Test
    void convertsIntoTheBuyersCurrency() {
        Vendor vendor = vendor(1L, ORIGIN_LAT, ORIGIN_LNG);
        List<DeliveryItem> items = List.of(DeliveryItem.of(product(10L, vendor, 2.0, DeliveryScope.REGIIONAL), 1));

        BigDecimal gmd = service.quote(items, destination(), DeliveryMode.HOME_DELIVERY, "GMD").total();
        BigDecimal usd = service.quote(items, destination(), DeliveryMode.HOME_DELIVERY, "USD").total();

        // FixedRates prices 1 GMD at 0.0140 USD.
        assertEquals(gmd.multiply(new BigDecimal("0.0140")).setScale(2, java.math.RoundingMode.HALF_UP), usd);
    }

    @Test
    void reportsAnIncompleteQuoteWhenNoRateExists() {
        Vendor vendor = vendor(1L, ORIGIN_LAT, ORIGIN_LNG);
        DeliveryQuote quote = service.quote(
                List.of(DeliveryItem.of(product(10L, vendor, 2.0, DeliveryScope.REGIIONAL), 1)),
                destination(), DeliveryMode.HOME_DELIVERY, "XOF");

        assertFalse(quote.complete(), "an unconvertible quote must not present a total");
        assertEquals(null, quote.total());
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static DeliveryDestination destination() {
        return new DeliveryDestination(DEST_LAT, DEST_LNG, "Kairaba Avenue", "Serekunda", null, null, "GM");
    }

    private static Vendor vendor(Long id, Double latitude, Double longitude) {
        Vendor vendor = new Vendor();
        vendor.setId(id);
        vendor.setStoreName("Store " + id);
        vendor.setLatitude(latitude);
        vendor.setLongitude(longitude);
        return vendor;
    }

    private static Product product(Long id, Vendor vendor, Double weightKg, DeliveryScope scope) {
        Product product = new Product();
        product.setId(id);
        product.setName("Product " + id);
        product.setVendor(vendor);
        product.setWeightKg(weightKg);
        product.setDeliveryScope(scope);
        return product;
    }

    /** Knows one rate: GMD → USD. Anything else is unquotable, which is the point of one test. */
    private static final class FixedRates implements ExchangeRateService {

        @Override
        public Map<String, BigDecimal> getLatestRates(String targetCurrency, Collection<String> fromCurrencies) {
            if ("USD".equals(targetCurrency) && fromCurrencies.contains("GMD")) {
                return Map.of("GMD", new BigDecimal("0.0140"));
            }
            return Map.of();
        }

        @Override
        public Page<ExchangeRateResponse> findAll(Pageable pageable) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ExchangeRateResponse findById(Long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ExchangeRateResponse create(ExchangeRateRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ExchangeRateResponse update(Long id, ExchangeRateRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(Long id) {
            throw new UnsupportedOperationException();
        }
    }
}
