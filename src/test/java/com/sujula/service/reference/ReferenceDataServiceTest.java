package com.sujula.service.reference;

import com.sujula.dto.response.reference.ReferenceResponses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Countries, locales and the configuration a client is allowed to read. */
class ReferenceDataServiceTest {

    private ReferenceDataProperties properties;
    private ReferenceDataService service;

    @BeforeEach
    void setUp() {
        properties = new ReferenceDataProperties();
        CurrencyCatalogue catalogue = new CurrencyCatalogue(properties);
        catalogue.index();
        service = new ReferenceDataService(properties, catalogue);
    }

    // ── Currencies ───────────────────────────────────────────────────────────

    @Test
    void theCurrencyListCarriesMinorUnitsAndTheSmallestUnit() {
        ReferenceResponses.Currencies response = service.currencies();

        ReferenceResponses.Currency cfa = currency(response.currencies(), "XOF");
        assertEquals(0, cfa.minorUnits());
        assertEquals(0, new java.math.BigDecimal("1").compareTo(cfa.smallestUnit()));

        ReferenceResponses.Currency dalasi = currency(response.currencies(), "GMD");
        assertEquals(2, dalasi.minorUnits());
        assertTrue(dalasi.base());
        assertEquals("GMD", response.base());
    }

    @Test
    void exactlyOneCurrencyIsTheBase() {
        assertEquals(1, service.currencies().currencies().stream()
                .filter(ReferenceResponses.Currency::base).count());
    }

    // ── Countries ────────────────────────────────────────────────────────────

    /**
     * The distinction this endpoint exists for. A buyer in London orders from a
     * Gambian vendor for delivery to Serekunda: their country buys and does not
     * ship, and one "supported" boolean could not say that.
     */
    @Test
    void buyingAndShippingAreDifferentSets() {
        List<ReferenceResponses.Country> countries = service.countries().countries();

        ReferenceResponses.Country uk = country(countries, "GB");
        assertTrue(uk.buy());
        assertFalse(uk.ship());

        ReferenceResponses.Country gambia = country(countries, "GM");
        assertTrue(gambia.buy());
        assertTrue(gambia.ship());

        assertTrue(countries.stream().filter(ReferenceResponses.Country::ship).count()
                        < countries.stream().filter(ReferenceResponses.Country::buy).count(),
                "shipping reaches fewer places than money comes from");
    }

    @Test
    void theHomeMarketShipsAndIsNamedAsTheBase() {
        assertEquals("GM", service.countries().base());
        assertTrue(country(service.countries().countries(), "GM").ship());
    }

    @Test
    void everyCountryNamesACurrencyAndADialCode() {
        for (ReferenceResponses.Country country : service.countries().countries()) {
            assertNotNull(country.currency(), country.code() + " has no currency");
            assertNotNull(country.dialCode(), country.code() + " has no dial code");
            assertEquals(2, country.code().length());
        }
    }

    /** Every shippable country must be priceable, or checkout there cannot complete. */
    @Test
    void everyShippableCountrysCurrencyIsOneWeSupport() {
        List<String> supported = service.currencies().currencies().stream()
                .map(ReferenceResponses.Currency::code)
                .toList();

        service.countries().countries().stream()
                .filter(ReferenceResponses.Country::ship)
                .forEach(country -> assertTrue(supported.contains(country.currency()),
                        country.code() + " ships but settles in " + country.currency()
                                + ", which is not a currency this marketplace trades in"));
    }

    // ── Locales ──────────────────────────────────────────────────────────────

    @Test
    void localesCarryTheirDirection() {
        ReferenceResponses.Locales locales = service.locales();

        assertEquals("en-GM", locales.defaultLocale());
        assertTrue(locales.locales().stream().anyMatch(ReferenceResponses.Locale::rtl),
                "a client cannot lay out Arabic without being told to flip");
        assertFalse(locale(locales.locales(), "en-GM").rtl());
    }

    @Test
    void theDefaultLocaleIsOneOfTheOffered() {
        ReferenceResponses.Locales locales = service.locales();

        assertTrue(locales.locales().stream()
                .anyMatch(locale -> locale.tag().equals(locales.defaultLocale())));
    }

    // ── Public config ────────────────────────────────────────────────────────

    @Test
    void publicConfigCarriesFlagsVersionsAndContacts() {
        ReferenceResponses.PublicConfig config = service.publicConfig();

        assertTrue(config.features().containsKey("guestCheckout"));
        assertEquals(3, config.minimumAppVersions().size());
        assertNotNull(config.support().email());
        assertEquals("GMD", config.baseCurrency());
        assertEquals(900, config.fxQuoteTtlSeconds(), "the fifteen-minute window, in seconds");
    }

    /**
     * A caller must not be able to change what everyone after it is told. These
     * maps come from a singleton's configuration.
     */
    @Test
    void theResponseCannotBeMutatedByItsCaller() {
        ReferenceResponses.PublicConfig config = service.publicConfig();

        assertThrows(UnsupportedOperationException.class,
                () -> config.features().put("loyalty", true));
        assertThrows(UnsupportedOperationException.class,
                () -> config.minimumAppVersions().put("android", "99.0.0"));
    }

    @Test
    void flagsKeepTheirConfiguredOrder() {
        assertEquals("guestCheckout",
                service.publicConfig().features().keySet().iterator().next(),
                "read by people as well as clients; a list that reshuffles is hard to diff");
    }

    /**
     * The response is assembled field by field from an allow-list, so nothing
     * that was not deliberately put there can appear — which is what keeps a
     * future property named for a secret out of it.
     */
    @Test
    void nothingSecretLeaksThroughPublicConfig() {
        String rendered = service.publicConfig().toString().toLowerCase();

        for (String forbidden : List.of("secret", "password", "api-key", "apikey",
                                        "token", "credential")) {
            assertFalse(rendered.contains(forbidden),
                    "public config mentions '" + forbidden + "'");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    // Distinct names rather than overloads: generics erase, so three find(List, String)
    // methods are one method as far as the compiler is concerned.

    private static ReferenceResponses.Currency currency(List<ReferenceResponses.Currency> all, String code) {
        return all.stream().filter(c -> c.code().equals(code)).findFirst()
                .orElseThrow(() -> new AssertionError("no currency " + code));
    }

    private static ReferenceResponses.Country country(List<ReferenceResponses.Country> all, String code) {
        return all.stream().filter(c -> c.code().equals(code)).findFirst()
                .orElseThrow(() -> new AssertionError("no country " + code));
    }

    private static ReferenceResponses.Locale locale(List<ReferenceResponses.Locale> all, String tag) {
        return all.stream().filter(l -> l.tag().equals(tag)).findFirst()
                .orElseThrow(() -> new AssertionError("no locale " + tag));
    }
}
