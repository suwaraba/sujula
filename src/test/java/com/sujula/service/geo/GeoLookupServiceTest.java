package com.sujula.service.geo;

import com.sujula.dto.GeoAddress;
import com.sujula.dto.request.geo.GeoRequests;
import com.sujula.dto.response.geo.GeoResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.model.constant.GeocodeConfidence;
import com.sujula.service.GeoService;
import com.sujula.service.GoogleMapsService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The public geo endpoints, where every answer has to be useful even when the
 * lookup behind it is not available.
 */
class GeoLookupServiceTest {

    private GoogleMapsService maps;
    private GeoService geoService;

    @BeforeEach
    void setUp() {
        maps = mock(GoogleMapsService.class);
        geoService = mock(GeoService.class);
    }

    private GeoLookupService configured() {
        return new GeoLookupService(new GeocodingGateway(maps, "en", "a-test-api-key"), geoService);
    }

    private GeoLookupService unconfigured() {
        return new GeoLookupService(new GeocodingGateway(maps, "en", ""), geoService);
    }

    private static GeoRequests.ValidateAddress address(String query) {
        return new GeoRequests.ValidateAddress(query, null, null, null, null, null, null);
    }

    // ── Forward ──────────────────────────────────────────────────────────────

    @Test
    void aFoundAddressComesBackWithItsConfidence() {
        when(maps.getCoordinates(any(), any())).thenReturn(new GeoAddress(
                "14 Kairaba Avenue", 13.4383, -16.6781, "Gambia", "GM", "Serekunda",
                GeocodeConfidence.EXACT));

        GeoResponses.AddressLookup result = configured().validate(address("14 Kairaba Avenue"));

        assertTrue(result.resolved());
        assertTrue(result.available());
        assertEquals(GeocodeConfidence.EXACT, result.confidence());
        assertFalse(result.needsPinConfirmation());
        assertEquals("GM", result.countryCode());
    }

    @Test
    void anApproximateMatchAsksForAPin() {
        when(maps.getCoordinates(any(), any())).thenReturn(new GeoAddress(
                "Brikama", 13.2714, -16.6492, "Gambia", "GM", "Brikama",
                GeocodeConfidence.APPROXIMATE));

        GeoResponses.AddressLookup result = configured().validate(address("somewhere in Brikama"));

        assertTrue(result.resolved());
        assertTrue(result.needsPinConfirmation());
        assertNotNull(result.message());
    }

    /** Not finding an address is an ordinary outcome here, not an error. */
    @Test
    void anAddressNobodyKnowsIsAnAnswerNotAFailure() {
        when(maps.getCoordinates(any(), any())).thenThrow(new RuntimeException("no results"));

        GeoResponses.AddressLookup result = configured().validate(address("a compound near the river"));

        assertFalse(result.resolved());
        assertTrue(result.available(), "we looked; we just did not find it");
        assertTrue(result.needsPinConfirmation());
    }

    /**
     * "Nobody looked" and "we looked and found nothing" lead to different advice,
     * so they must not collapse into one answer.
     */
    @Test
    void anUnconfiguredDeploymentSaysSoRatherThanPretending() {
        GeoResponses.AddressLookup result = unconfigured().validate(address("14 Kairaba Avenue"));

        assertFalse(result.resolved());
        assertFalse(result.available());
        assertFalse(result.needsPinConfirmation(),
                "there is no point asking for a pin nothing will read");
        assertTrue(result.message().contains("not configured"));
    }

    @Test
    void anEmptyLookupIsRefused() {
        assertThrows(BadRequestException.class,
                () -> configured().validate(new GeoRequests.ValidateAddress(
                        null, null, null, null, null, null, null)));
    }

    @Test
    void addressPartsAreJoinedInTheOrderAGeocoderReads() {
        GeoRequests.ValidateAddress request = new GeoRequests.ValidateAddress(
                null, "14 Kairaba Avenue", "Serekunda", "West Coast", null, "GM", null);

        assertEquals("14 Kairaba Avenue, Serekunda, West Coast, GM", request.toSearchText());
    }

    // ── Reverse ──────────────────────────────────────────────────────────────

    @Test
    void aPinIsNamed() {
        when(maps.getAddress(13.4383, -16.6781, "en")).thenReturn(new GeoAddress(
                "14 Kairaba Avenue", 13.4383, -16.6781, "Gambia", "GM", "Serekunda",
                GeocodeConfidence.EXACT));

        GeoResponses.AddressLookup result =
                configured().reverse(new GeoRequests.Reverse(13.4383, -16.6781, "en"));

        assertTrue(result.resolved());
        assertEquals("Serekunda", result.city());
    }

    /** Exactly (0,0) is the Gulf of Guinea and is almost always an unset field. */
    @Test
    void nullIslandIsRefused() {
        assertThrows(BadRequestException.class,
                () -> configured().reverse(new GeoRequests.Reverse(0.0, 0.0, null)));
    }

    @Test
    void coordinatesOffTheEarthAreRefused() {
        assertThrows(BadRequestException.class,
                () -> configured().reverse(new GeoRequests.Reverse(91.0, -16.0, null)));
    }

    // ── Context ──────────────────────────────────────────────────────────────

    @Test
    void aGambianShopperGetsDalasiAndBanjul() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(geoService.getCountryCode(request)).thenReturn("GM");

        GeoResponses.ResolvedContext context = configured().resolveContext(request);

        assertTrue(context.resolved());
        assertEquals("GMD", context.currency());
        assertEquals("Africa/Banjul", context.timezone());
        assertEquals("en", context.language());
    }

    @Test
    void aSenegaleseShopperGetsFrench() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(geoService.getCountryCode(request)).thenReturn("SN");
        when(geoService.getCurrencyCode("SN")).thenReturn("XOF");

        GeoResponses.ResolvedContext context = configured().resolveContext(request);

        assertEquals("fr", context.language());
        assertEquals("XOF", context.currency());
        assertEquals("Africa/Dakar", context.timezone());
    }

    /**
     * A storefront that renders nothing until an IP resolves is worse than one
     * that opens on its home market and lets the shopper change it.
     */
    @Test
    void anUnresolvableCallerStillGetsSomethingToRender() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(geoService.getCountryCode(request)).thenReturn(null);

        GeoResponses.ResolvedContext context = configured().resolveContext(request);

        assertFalse(context.resolved(), "and it admits it is a default");
        assertEquals("GM", context.countryCode());
        assertEquals("GMD", context.currency());
        assertEquals("default", context.source());
    }

    @Test
    void anUnknownCountryFallsBackToUtcRatherThanGuessing() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(geoService.getCountryCode(request)).thenReturn("FJ");

        assertEquals("UTC", configured().resolveContext(request).timezone());
    }
}
