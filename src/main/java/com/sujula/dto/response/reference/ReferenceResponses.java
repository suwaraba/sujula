package com.sujula.dto.response.reference;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** What the reference endpoints return. */
public final class ReferenceResponses {

    private ReferenceResponses() {}

    /**
     * A currency this marketplace prices in.
     *
     * @param minorUnits decimal places the currency actually has. A client that
     *                   formats to two everywhere will show CFA amounts that
     *                   cannot be tendered
     * @param smallestUnit the smallest expressible amount — 1 for XOF, 0.01 for
     *                   GMD — so a client can decide whether a difference is
     *                   real or conversion residue
     */
    public record Currency(
            String code,
            String name,
            String symbol,
            int minorUnits,
            BigDecimal smallestUnit,
            boolean buyerFacing,
            boolean settlement,
            boolean base) {}

    public record Currencies(String base, List<Currency> currencies) {}

    /**
     * An indicative rate: what the pair was worth when it was last published.
     *
     * @param indicative always true, and named so it cannot be mistaken for a
     *                  commitment. Rates move; what a buyer is charged at comes
     *                  from a quote, not from here
     * @param fetchedAt when the rate was published. A client showing a rate
     *                  without saying how old it is invites someone to rely on it
     * @param inverted  true when no direct rate was stored and this is the
     *                  reciprocal of the opposite pair. Worth disclosing: a
     *                  reciprocal carries no spread and will not match a dealer's
     *                  quote in that direction
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Rate(
            String base,
            String quote,
            BigDecimal rate,
            boolean indicative,
            boolean inverted,
            LocalDateTime fetchedAt,
            String message) {}

    /**
     * A held rate.
     *
     * @param id       the handle, and for a guest the whole of their claim to it
     * @param expiresAt when it stops being payable
     * @param expiresInSeconds so a client can run a countdown without trusting
     *                  its own clock to agree with the server's
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FxQuote(
            String id,
            String base,
            String quote,
            BigDecimal rate,
            BigDecimal baseAmount,
            BigDecimal quoteAmount,
            LocalDateTime rateFetchedAt,
            LocalDateTime createdAt,
            LocalDateTime expiresAt,
            long expiresInSeconds,
            boolean consumed) {}

    /**
     * A country, and what may happen there.
     *
     * @param buy  money may come from here
     * @param ship goods may go here. Not the same set: a buyer in London orders
     *             for delivery to Serekunda, so their country buys and does not
     *             ship
     */
    public record Country(
            String code,
            String name,
            String currency,
            String dialCode,
            boolean buy,
            boolean ship) {}

    public record Countries(String base, List<Country> countries) {}

    /** @param rtl right-to-left, so a client knows to flip its layout */
    public record Locale(String tag, String name, String nativeName, boolean rtl) {}

    public record Locales(String defaultLocale, List<Locale> locales) {}

    /**
     * What an anonymous client is allowed to know about this deployment.
     *
     * @param features named switches, booleans only
     * @param minimumAppVersions below these a client should tell the user to
     *                  update rather than fail in some less legible way
     */
    public record PublicConfig(
            Map<String, Boolean> features,
            Map<String, String> minimumAppVersions,
            Support support,
            String baseCurrency,
            String baseCountry,
            String defaultLocale,
            int fxQuoteTtlSeconds) {}

    public record Support(String email, String phone, String whatsapp,
                          String termsUrl, String privacyUrl) {}
}
