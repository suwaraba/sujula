package com.sujula.service.reference;

import com.sujula.dto.response.reference.ReferenceResponses;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The catalogue of what this deployment supports: currencies, countries,
 * locales, and the configuration a client is allowed to read.
 *
 * <p>All of it is read from configuration and converted here. Nothing is
 * persisted and nothing is per-caller, which is what makes these the cheapest
 * endpoints in the application — and they need to be, because a storefront hits
 * them on first paint to decide what currency selector to draw and whether to
 * lay itself out right-to-left.
 */
@Service
public class ReferenceDataService {

    private final ReferenceDataProperties properties;
    private final CurrencyCatalogue currencies;

    public ReferenceDataService(ReferenceDataProperties properties, CurrencyCatalogue currencies) {
        this.properties = properties;
        this.currencies = currencies;
    }

    // ── Currencies ───────────────────────────────────────────────────────────

    public ReferenceResponses.Currencies currencies() {
        String base = currencies.baseCurrency();
        List<ReferenceResponses.Currency> list = currencies.all().stream()
                .map(currency -> {
                    String code = currency.getCode().toUpperCase(Locale.ROOT);
                    return new ReferenceResponses.Currency(
                            code,
                            currency.getName(),
                            currency.getSymbol(),
                            currency.getMinorUnits(),
                            currencies.smallestUnit(code),
                            currency.isBuyerFacing(),
                            currency.isSettlement(),
                            code.equals(base));
                })
                .toList();
        return new ReferenceResponses.Currencies(base, list);
    }

    // ── Countries ────────────────────────────────────────────────────────────

    /**
     * Where money may come from and where goods may go.
     *
     * <p>Returned together with both flags rather than as two lists, because a
     * client usually needs both at once: one country picker for the billing
     * address and another for the shipping address, drawn from the same fetch.
     */
    public ReferenceResponses.Countries countries() {
        List<ReferenceResponses.Country> list = properties.getCountries().stream()
                .filter(country -> country.getCode() != null && !country.getCode().isBlank())
                .map(country -> new ReferenceResponses.Country(
                        country.getCode().toUpperCase(Locale.ROOT),
                        country.getName(),
                        country.getCurrency() == null
                                ? null : country.getCurrency().toUpperCase(Locale.ROOT),
                        country.getDialCode(),
                        country.isBuy(),
                        country.isShip()))
                .toList();
        return new ReferenceResponses.Countries(
                properties.getBaseCountry() == null
                        ? null : properties.getBaseCountry().toUpperCase(Locale.ROOT),
                list);
    }

    // ── Locales ──────────────────────────────────────────────────────────────

    public ReferenceResponses.Locales locales() {
        List<ReferenceResponses.Locale> list = properties.getLocales().stream()
                .filter(locale -> locale.getTag() != null && !locale.getTag().isBlank())
                .map(locale -> new ReferenceResponses.Locale(
                        locale.getTag(), locale.getName(), locale.getNativeName(), locale.isRtl()))
                .toList();
        return new ReferenceResponses.Locales(properties.getDefaultLocale(), list);
    }

    // ── Public configuration ─────────────────────────────────────────────────

    /**
     * What an anonymous caller may know about this deployment.
     *
     * <p>Assembled field by field from an allow-list, never by reflecting over
     * the environment or filtering a property source. The difference matters: a
     * filtered view is one careless rename away from publishing
     * {@code sujula.payment.callback-secret}, whereas a hand-written response can
     * only ever contain what somebody deliberately put in it.
     *
     * <p>Defensive copies, because these maps come from a singleton's
     * configuration and a caller that mutated one would change the answer given
     * to everyone after it.
     */
    /**
     * Normalises a locale tag to the one this platform actually serves.
     *
     * <p>Case-insensitively, because "FR-sn" is the same locale as "fr-SN" and
     * refusing it teaches nobody anything. Anything not on the list is refused
     * outright: a translation written in a locale the catalogue never asks for
     * is work a seller did that nothing will ever read.
     */
    public String requireLocale(String tag) {
        if (tag == null || tag.isBlank()) {
            throw new com.sujula.exceptions.BadRequestException("A locale is required.");
        }
        String wanted = tag.trim();
        return properties.getLocales().stream()
                .map(ReferenceDataProperties.Locale::getTag)
                .filter(supported -> supported.equalsIgnoreCase(wanted))
                .findFirst()
                .orElseThrow(() -> new com.sujula.exceptions.BadRequestException(
                        "This platform does not serve the locale " + wanted + ". Supported: "
                        + properties.getLocales().stream()
                                .map(ReferenceDataProperties.Locale::getTag)
                                .collect(java.util.stream.Collectors.joining(", "))));
    }

    public ReferenceResponses.PublicConfig publicConfig() {
        ReferenceDataProperties.Config config = properties.getConfig();

        return new ReferenceResponses.PublicConfig(
                // LinkedHashMap copies rather than Map.copyOf, which does not
                // keep insertion order — these are read by people as well as by
                // clients, and a flag list that reshuffles itself between
                // restarts is needlessly hard to diff.
                java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(config.getFeatures())),
                java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(config.getMinimumAppVersions())),
                new ReferenceResponses.Support(
                        config.getSupportEmail(),
                        config.getSupportPhone(),
                        config.getSupportWhatsapp(),
                        config.getTermsUrl(),
                        config.getPrivacyUrl()),
                currencies.baseCurrency(),
                properties.getBaseCountry() == null
                        ? null : properties.getBaseCountry().toUpperCase(Locale.ROOT),
                properties.getDefaultLocale(),
                (int) properties.getFxQuoteTtl().getSeconds());
    }
}
