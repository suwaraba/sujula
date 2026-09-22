package com.sujula.service.catalogue;

import com.sujula.dto.response.geo.GeoResponses;
import com.sujula.model.constant.GeocodeConfidence;
import com.sujula.model.delivery.DeliveryContext;
import com.sujula.service.delivery.DeliveryContextService;
import com.sujula.service.geo.GeoLookupService;
import com.sujula.service.reference.CurrencyCatalogue;
import com.sujula.service.reference.ReferenceDataProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C1, proved rather than asserted in a comment.
 *
 * <p>The scenario the marketplace exists for: a buyer in Madrid, paying in euro
 * with a European card, sending a phone to their sister in Serrekunda. Two
 * locations, two answers, and every test here is a way of getting them crossed.
 *
 * <p>Each failure below is a real product failure, not a style violation.
 * Currency from the delivery country quotes a Spanish cardholder in dalasi.
 * Ranking from the payer's IP buries the phone that is two miles from the
 * recipient beneath one that can never reach her.
 */
class C1SeparationTest {

    // Serrekunda: where the goods go.
    private static final double SERREKUNDA_LAT = 13.4383, SERREKUNDA_LNG = -16.6781;
    // Madrid: where the person paying is.
    private static final double MADRID_LAT = 40.4168, MADRID_LNG = -3.7038;

    private DeliveryContextService deliveryContexts;
    private GeoLookupService geoLookup;
    private BrowsingContextResolver resolver;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        deliveryContexts = mock(DeliveryContextService.class);
        geoLookup = mock(GeoLookupService.class);
        request = mock(HttpServletRequest.class);

        ReferenceDataProperties properties = new ReferenceDataProperties();
        CurrencyCatalogue catalogue = CurrencyCatalogue.of(properties);

        resolver = new BrowsingContextResolver(deliveryContexts, geoLookup, catalogue);
    }

    /** The payer is in Spain. This is all the IP ever tells us. */
    private void payerIsInMadrid() {
        when(geoLookup.resolveContext(any())).thenReturn(new GeoResponses.ResolvedContext(
                true, "ES", "EUR", "es", "Europe/Madrid", "ip"));
    }

    /** The parcel is going to Serrekunda. This is all the context ever tells us. */
    private void deliveringToSerrekunda(String contextId) {
        DeliveryContext context = DeliveryContext.builder()
                .id(contextId)
                .latitude(SERREKUNDA_LAT)
                .longitude(SERREKUNDA_LNG)
                .countryCode("GM")
                .geocodeConfidence(GeocodeConfidence.USER_CONFIRMED)
                .build();
        when(deliveryContexts.require(contextId, null)).thenReturn(context);
    }

    private static CatalogueLocationParams deliverableTo(String contextId) {
        return CatalogueLocationParams.of(contextId, null, null, null, null, null);
    }

    // ── The case this marketplace exists for ─────────────────────────────────

    @Test
    void aMadridBuyerSendingToSerrekundaGetsEuroPricesAndGambianRanking() {
        payerIsInMadrid();
        deliveringToSerrekunda("ctx-1");

        BrowsingContext context = resolver.resolve(deliverableTo("ctx-1"), request, null);

        assertEquals("EUR", context.displayCurrency(),
                "the person paying holds a European card; dalasi is not payable with it");
        assertEquals(SERREKUNDA_LAT, context.deliveryLatitude(),
                "the catalogue ranks against where the parcel goes, not where the payer sits");
        assertEquals("GM", context.deliveryCountry());
    }

    // ── Currency must never come from the delivery country ───────────────────

    @Test
    void shippingToGambiaDoesNotQuoteTheBuyerInDalasi() {
        payerIsInMadrid();
        deliveringToSerrekunda("ctx-1");

        assertEquals("EUR", resolver.resolve(deliverableTo("ctx-1"), request, null).displayCurrency());
    }

    @Test
    void theSameBuyerSeesTheSamePricesWhereverTheyShipTo() {
        payerIsInMadrid();
        deliveringToSerrekunda("ctx-gm");
        when(deliveryContexts.require("ctx-es", null)).thenReturn(DeliveryContext.builder()
                .id("ctx-es").latitude(MADRID_LAT).longitude(MADRID_LNG).countryCode("ES")
                .geocodeConfidence(GeocodeConfidence.EXACT).build());

        String toGambia = resolver.resolve(deliverableTo("ctx-gm"), request, null).displayCurrency();
        String toSpain = resolver.resolve(deliverableTo("ctx-es"), request, null).displayCurrency();

        assertEquals(toSpain, toGambia,
                "changing the recipient must not reprice the catalogue for the payer");
        assertEquals("EUR", toGambia);
    }

    /**
     * The strongest form of the guarantee: currency resolution is not given the
     * delivery half at all, so it cannot come to depend on it later.
     */
    @Test
    void currencyResolutionNeverConsultsTheDeliveryContext() {
        payerIsInMadrid();

        resolver.resolveDisplayCurrency(null, request);

        verify(deliveryContexts, never()).require(any(), any());
    }

    // ── Ranking must never come from the payer's IP ──────────────────────────

    @Test
    void theCatalogueIsNotRankedAgainstTheBuyersOwnCity() {
        payerIsInMadrid();
        deliveringToSerrekunda("ctx-1");

        BrowsingContext context = resolver.resolve(deliverableTo("ctx-1"), request, null);

        assertFalse(MADRID_LAT == context.deliveryLatitude(),
                "a phone in Banjul must not rank below one in Spain that cannot reach the recipient");
        assertEquals(SERREKUNDA_LAT, context.deliveryLatitude());
    }

    /** Delivery resolution is not given the request, so it cannot read an IP. */
    @Test
    void deliveryResolutionNeverConsultsTheRequest() {
        deliveringToSerrekunda("ctx-1");

        resolver.resolveDelivery(deliverableTo("ctx-1"), null);

        verify(geoLookup, never()).resolveContext(any());
    }

    @Test
    void withNoDestinationGivenNothingIsInventedFromTheIp() {
        payerIsInMadrid();

        BrowsingContext context = resolver.resolve(CatalogueLocationParams.none(), request, null);

        assertNull(context.deliveryLatitude(), "an unknown destination stays unknown");
        assertNull(context.deliveryCountry(),
                "ES is where the payer is; it is not where the goods are going");
        assertFalse(context.hasDestination());
        assertEquals("EUR", context.displayCurrency(), "and prices still resolve");
    }

    // ── The ways a destination can be given ──────────────────────────────────

    @Test
    void anExplicitPinIsAccepted() {
        payerIsInMadrid();

        BrowsingContext context = resolver.resolve(
                CatalogueLocationParams.of(null, SERREKUNDA_LAT, SERREKUNDA_LNG, "GM", null, null),
                request, null);

        assertEquals(SERREKUNDA_LAT, context.deliveryLatitude());
        assertEquals("GM", context.deliveryCountry());
        assertTrue(context.hasPreciseDeliveryPoint());
    }

    @Test
    void aCountryAloneIsEnoughToFilterEvenWithoutAPin() {
        payerIsInMadrid();

        BrowsingContext context = resolver.resolve(
                CatalogueLocationParams.of(null, null, null, "gm", null, null), request, null);

        assertEquals("GM", context.deliveryCountry());
        assertFalse(context.hasDeliveryPoint());
        assertTrue(context.hasDestination(), "a country still narrows what can be delivered");
    }

    /** Transposed coordinates put Serrekunda in the South Atlantic. */
    @Test
    void nonsenseCoordinatesAreIgnoredRatherThanRankedAgainst() {
        payerIsInMadrid();

        BrowsingContext context = resolver.resolve(
                CatalogueLocationParams.of(null, 0.0, 0.0, "GM", null, null), request, null);

        assertFalse(context.hasDeliveryPoint());
        assertEquals("GM", context.deliveryCountry(), "the country it came with is still usable");
    }

    /**
     * A context is the best destination because it is the one the cart and
     * checkout will price against too — so the catalogue cannot disagree with
     * what the buyer is later charged.
     */
    @Test
    void aContextWinsOverExplicitCoordinates() {
        payerIsInMadrid();
        deliveringToSerrekunda("ctx-1");

        BrowsingContext context = resolver.resolve(
                CatalogueLocationParams.of("ctx-1", MADRID_LAT, MADRID_LNG, "ES", null, null),
                request, null);

        assertEquals(SERREKUNDA_LAT, context.deliveryLatitude());
        assertEquals("GM", context.deliveryCountry());
        assertEquals("ctx-1", context.deliveryContextId());
    }

    // ── The payer half ───────────────────────────────────────────────────────

    @Test
    void anExplicitCurrencyChoiceWins() {
        payerIsInMadrid();

        assertEquals("GBP", resolver.resolveDisplayCurrency("gbp", request),
                "a shopper who picked sterling meant it");
    }

    @Test
    void anUnsupportedCurrencyIsRefusedRatherThanRenderedUnconverted() {
        payerIsInMadrid();

        org.junit.jupiter.api.Assertions.assertThrows(
                com.sujula.exceptions.BadRequestException.class,
                () -> resolver.resolveDisplayCurrency("JPY", request));
    }

    @Test
    void anUnresolvableCallerStillGetsPrices() {
        when(geoLookup.resolveContext(any())).thenReturn(new GeoResponses.ResolvedContext(
                false, "GM", "GMD", "en", "Africa/Banjul", "default"));

        assertEquals("GMD", resolver.resolveDisplayCurrency(null, request));
    }

    /** A payer country we do not trade in falls back rather than failing. */
    @Test
    void aCurrencyWeDoNotTradeInFallsBackToTheHomeCurrency() {
        when(geoLookup.resolveContext(any())).thenReturn(new GeoResponses.ResolvedContext(
                true, "JP", "JPY", "en", "Asia/Tokyo", "ip"));

        assertEquals("GMD", resolver.resolveDisplayCurrency(null, request));
    }
}
