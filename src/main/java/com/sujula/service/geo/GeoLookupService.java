package com.sujula.service.geo;

import com.sujula.dto.GeoAddress;
import com.sujula.dto.request.geo.GeoRequests;
import com.sujula.dto.response.geo.GeoResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.service.GeoService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The public geo endpoints: look an address up, name a point, work out where
 * the shopper is.
 *
 * <p>Nothing here persists anything, and that is a property worth stating rather
 * than merely observing. These are the lookups a checkout form makes while
 * someone is still typing — several per address — and a form that wrote a row
 * each time would fill the database with half-typed addresses belonging to
 * people who never ordered.
 *
 * <p>Everything degrades. A shopper whose country cannot be determined still
 * gets a currency, a language and a timezone, because a storefront that renders
 * nothing until an IP resolves is worse than one that guesses its home market
 * and lets them change it.
 */
@Slf4j
@Service
public class GeoLookupService {

    /**
     * Where a country's clocks are, for the countries this marketplace reaches.
     *
     * <p>A table rather than a library because the library answer is a list: most
     * countries have one zone and a few have twelve, and for the one-zone case —
     * which is all of West Africa — a lookup table is exact, free, and cannot
     * return Africa/Abidjan for a shopper in Banjul merely because both are on
     * UTC. Anything not listed falls back to UTC, which is honest rather than
     * wrong.
     */
    private static final Map<String, String> TIMEZONES = Map.ofEntries(
            Map.entry("GM", "Africa/Banjul"),
            Map.entry("SN", "Africa/Dakar"),
            Map.entry("GW", "Africa/Bissau"),
            Map.entry("GN", "Africa/Conakry"),
            Map.entry("ML", "Africa/Bamako"),
            Map.entry("MR", "Africa/Nouakchott"),
            Map.entry("SL", "Africa/Freetown"),
            Map.entry("LR", "Africa/Monrovia"),
            Map.entry("CI", "Africa/Abidjan"),
            Map.entry("GH", "Africa/Accra"),
            Map.entry("NG", "Africa/Lagos"),
            Map.entry("BF", "Africa/Ouagadougou"),
            Map.entry("NE", "Africa/Niamey"),
            Map.entry("TG", "Africa/Lome"),
            Map.entry("BJ", "Africa/Porto-Novo"),
            Map.entry("CV", "Atlantic/Cape_Verde"),
            Map.entry("MA", "Africa/Casablanca"),
            Map.entry("GB", "Europe/London"),
            Map.entry("IE", "Europe/Dublin"),
            Map.entry("FR", "Europe/Paris"),
            Map.entry("ES", "Europe/Madrid"),
            Map.entry("PT", "Europe/Lisbon"),
            Map.entry("DE", "Europe/Berlin"),
            Map.entry("IT", "Europe/Rome"),
            Map.entry("NL", "Europe/Amsterdam"),
            Map.entry("BE", "Europe/Brussels"),
            Map.entry("SE", "Europe/Stockholm"),
            Map.entry("NO", "Europe/Oslo"),
            Map.entry("AE", "Asia/Dubai"),
            Map.entry("SA", "Asia/Riyadh"),
            Map.entry("TR", "Europe/Istanbul"),
            Map.entry("CN", "Asia/Shanghai"),
            Map.entry("IN", "Asia/Kolkata"),
            Map.entry("CA", "America/Toronto"),
            Map.entry("US", "America/New_York"));

    /** The francophone neighbours. Used only when nothing better is known. */
    private static final java.util.Set<String> FRENCH_SPEAKING =
            java.util.Set.of("SN", "ML", "BF", "NE", "TG", "BJ", "CI", "GN", "MR");

    /** Home market. The fallback for a shopper nothing is known about. */
    private static final String HOME_COUNTRY = "GM";
    private static final String HOME_CURRENCY = "GMD";

    private final GeocodingGateway geocoding;
    private final GeoService geoService;

    public GeoLookupService(GeocodingGateway geocoding, GeoService geoService) {
        this.geocoding = geocoding;
        this.geoService = geoService;
    }

    // ── Forward ──────────────────────────────────────────────────────────────

    /**
     * Checks an address without saving it.
     *
     * <p>Answers rather than fails. "Could not find it" is an ordinary outcome
     * here — most of the country has no street numbering — and a 500 would turn
     * a normal result into an error the client has to special-case.
     */
    public GeoResponses.AddressLookup validate(GeoRequests.ValidateAddress request) {
        String search = request.toSearchText();
        if (search == null) {
            throw new BadRequestException(
                    "Send either a query or at least one address part to look up.");
        }
        if (!geocoding.isConfigured()) {
            return GeoResponses.AddressLookup.unavailable();
        }
        return geocoding.forward(search, request.language())
                .map(GeoLookupService::toLookup)
                .orElseGet(GeoResponses.AddressLookup::notFound);
    }

    // ── Reverse ──────────────────────────────────────────────────────────────

    /** Names a point: the pin someone just dropped on a map. */
    public GeoResponses.AddressLookup reverse(GeoRequests.Reverse request) {
        if (!GeocodingGateway.isValidCoordinate(request.lat(), request.lng())) {
            throw new BadRequestException(
                    "Those coordinates are not a place on Earth. Check that latitude and longitude "
                            + "have not been swapped.");
        }
        if (!geocoding.isConfigured()) {
            return GeoResponses.AddressLookup.unavailable();
        }
        return geocoding.reverse(request.lat(), request.lng(), request.language())
                .map(GeoLookupService::toLookup)
                .orElseGet(GeoResponses.AddressLookup::notFound);
    }

    // ── Context ──────────────────────────────────────────────────────────────

    /**
     * Where this request appears to come from, and what to show because of it.
     *
     * <p>A guess, and treated as one — it is what the storefront renders before
     * the shopper has said anything, not a decision about them. A VPN, a
     * corporate proxy or a roaming SIM will all mislead it, so every value here
     * is a default a client is expected to let the shopper override.
     */
    public GeoResponses.ResolvedContext resolveContext(HttpServletRequest request) {
        String country = normalise(geoService.getCountryCode(request));
        String source = country != null ? "ip" : "default";

        if (country == null) {
            country = HOME_COUNTRY;
        }

        return new GeoResponses.ResolvedContext(
                !"default".equals(source),
                country,
                currencyFor(country),
                FRENCH_SPEAKING.contains(country) ? "fr" : "en",
                TIMEZONES.getOrDefault(country, "UTC"),
                source);
    }

    /**
     * What to price in for a shopper in this country.
     *
     * <p>The JDK knows most of these; it is asked first so the table does not
     * have to list the world. Where it does not know — and for the home market,
     * where being wrong is least acceptable — dalasi.
     */
    private String currencyFor(String countryCode) {
        if (HOME_COUNTRY.equals(countryCode)) {
            return HOME_CURRENCY;
        }
        String resolved = geoService.getCurrencyCode(countryCode);
        return resolved != null ? resolved : HOME_CURRENCY;
    }

    // ─────────────────────────────────────────────────────────────────────────

    private static GeoResponses.AddressLookup toLookup(GeoAddress address) {
        return new GeoResponses.AddressLookup(
                true,
                true,
                address.getAddress(),
                address.getLatitude(),
                address.getLongitude(),
                address.getCountry(),
                address.getCountryCode(),
                address.getCity(),
                address.getConfidence(),
                address.getConfidence().needsConfirmation(),
                address.getConfidence().needsConfirmation()
                        ? "Found, but only approximately. Ask the buyer to place the pin."
                        : null);
    }

    private static String normalise(String countryCode) {
        return Optional.ofNullable(countryCode)
                .map(String::trim)
                .filter(code -> code.length() == 2)
                .map(code -> code.toUpperCase(Locale.ROOT))
                .orElse(null);
    }
}
