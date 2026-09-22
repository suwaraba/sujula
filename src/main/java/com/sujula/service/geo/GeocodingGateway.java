package com.sujula.service.geo;

import com.sujula.dto.GeoAddress;
import com.sujula.service.GoogleMapsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * The one place that knows geocoding can fail, and refuses to make that
 * everybody else's problem.
 *
 * <p>{@link GoogleMapsService} throws: no API key, no network, no result, a
 * rural address nobody has mapped — all of it arrives as an exception. That is
 * reasonable for a client of an API and wrong for this application, where not
 * knowing where an address is has always been an ordinary outcome rather than an
 * error. Most of Gambia has no street numbering; a buyer whose compound cannot
 * be found must still be able to save where they live, and a public endpoint
 * asked to check an address must answer "could not place it" rather than 500.
 *
 * <p>So everything here returns an {@link Optional} and nothing propagates. The
 * callers then have one thing to handle instead of four, and none of them can
 * forget: there is no exception to omit a catch for.
 *
 * <p>{@link #isConfigured()} is separate from a failed lookup on purpose. "No
 * API key on this deployment" and "we looked and could not find it" lead to
 * different answers for the shopper — the first is ours to fix, the second is a
 * prompt to drop a pin on a map — and collapsing them into one empty result
 * would tell them to move a pin that nothing was ever going to read.
 */
@Slf4j
@Component
public class GeocodingGateway {

    private final GoogleMapsService maps;
    private final String defaultLanguage;
    private final boolean configured;

    public GeocodingGateway(GoogleMapsService maps,
                            @Value("${sujula.geocoding.default-language:en}") String defaultLanguage,
                            @Value("${sujula.google.geocoding.api-key:}") String apiKey) {
        this.maps = maps;
        this.defaultLanguage = blankTo(defaultLanguage, "en");
        this.configured = apiKey != null && !apiKey.isBlank();

        if (!configured) {
            log.warn("[Geo] No sujula.google.geocoding.api-key configured. Addresses will be saved "
                    + "without coordinates, delivery will price from scope fallback distances, and "
                    + "/geo/validate-address will report that lookup is unavailable rather than "
                    + "failing.");
        }
    }

    /** Whether this deployment can geocode at all. */
    public boolean isConfigured() {
        return configured;
    }

    /**
     * Address text to a point.
     *
     * @return empty when geocoding is unconfigured, unreachable, or simply found
     *         nothing — the caller treats all three the same way, and
     *         {@link #isConfigured()} tells it apart when the difference matters
     */
    public Optional<GeoAddress> forward(String query, String language) {
        if (!configured || query == null || query.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(maps.getCoordinates(query, language(language)));
        } catch (RuntimeException e) {
            log.info("[Geo] Could not place '{}': {}", query, e.getMessage());
            return Optional.empty();
        }
    }

    /** A point to an address. */
    public Optional<GeoAddress> reverse(double latitude, double longitude, String language) {
        if (!configured || !isValidCoordinate(latitude, longitude)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(maps.getAddress(latitude, longitude, language(language)));
        } catch (RuntimeException e) {
            log.info("[Geo] Could not resolve {},{}: {}", latitude, longitude, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Whether a pair of numbers is a place on Earth.
     *
     * <p>Checked here rather than trusted, because these arrive from clients.
     * Longitude and latitude transposed is the classic one — 13.44, -16.67 is
     * Serekunda and -16.67, 13.44 is the South Atlantic — and the second pair is
     * perfectly valid, so only the range check catches the null island case where
     * both are zero.
     */
    public static boolean isValidCoordinate(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            return false;
        }
        if (latitude.isNaN() || longitude.isNaN()
                || latitude.isInfinite() || longitude.isInfinite()) {
            return false;
        }
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            return false;
        }
        // Exactly (0,0) is in the Gulf of Guinea and is almost always an unset
        // field rather than a delivery address.
        return !(latitude == 0.0d && longitude == 0.0d);
    }

    private String language(String requested) {
        return blankTo(requested, defaultLanguage).toLowerCase(Locale.ROOT);
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
