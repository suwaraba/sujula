package com.sujula.service.reference;

import com.sujula.exceptions.BadRequestException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What this marketplace can price in, and how many decimal places each of those
 * currencies actually has.
 *
 * <h2>Why minor units are not a formatting detail</h2>
 *
 * <p>The West African CFA franc has no subdivision. There is no centime in
 * circulation and no way to tender one, so 1250.50 XOF is not a small
 * presentational untidiness — it is an amount that does not exist. A total
 * computed to two decimal places and shown to a buyer in Ziguinchor is a total
 * they cannot pay, and a payout figure carrying half a franc is one that will
 * never reconcile against what the bank actually moved.
 *
 * <p>So {@link #round} exists, and it rounds to the currency's own scale rather
 * than to two. It is the one place in the codebase that knows the difference.
 *
 * <p><strong>It is not yet used everywhere.</strong> Order totals, delivery legs
 * and payouts still scale to two places in their own code, which is correct for
 * dalasi, sterling and euro and wrong for CFA. Fixing that means touching money
 * arithmetic across checkout, pricing and settlement, which is a larger and
 * riskier change than adding this endpoint — so what this class does today is
 * make the correct answer available and name the discrepancy, rather than
 * quietly leave it undiscoverable.
 */
@Slf4j
@Component
public class CurrencyCatalogue {

    private final ReferenceDataProperties properties;

    /** Indexed by code, in configured order, because the order is the display order. */
    private Map<String, ReferenceDataProperties.Currency> byCode = Map.of();

    public CurrencyCatalogue(ReferenceDataProperties properties) {
        this.properties = properties;
    }

    /**
     * Built once at startup rather than on every lookup.
     *
     * <p>These endpoints are hit on every page load of a storefront — a currency
     * selector renders from them — and walking a list to find a three-letter code
     * on each request is work that never needed doing twice.
     */
    @PostConstruct
    void index() {
        Map<String, ReferenceDataProperties.Currency> index = new LinkedHashMap<>();
        for (ReferenceDataProperties.Currency currency : properties.getCurrencies()) {
            if (currency.getCode() == null || currency.getCode().isBlank()) {
                continue;
            }
            index.put(currency.getCode().toUpperCase(Locale.ROOT), currency);
        }
        // Collections.unmodifiableMap over a LinkedHashMap, not Map.copyOf:
        // Map.copyOf returns an *unordered* map, and its iteration order is not
        // merely different from the configured one but varies between runs. The
        // order here is the display order of a currency selector, so that would
        // have shuffled the picker on every restart.
        this.byCode = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(index));

        if (!this.byCode.containsKey(normalise(properties.getBaseCurrency()))) {
            // Not merely odd — every fallback in the application resolves to the
            // base currency, so one that is not in the catalogue means those
            // fallbacks resolve to something nothing can price.
            throw new IllegalStateException(
                    "sujula.reference.base-currency is " + properties.getBaseCurrency()
                            + ", which is not among the configured currencies. Every currency "
                            + "fallback in the application resolves to the base, so this would "
                            + "leave them resolving to something unsupported.");
        }
        log.info("[Reference] {} currencies, {} countries, {} locales; base {} / {}",
                byCode.size(), properties.getCountries().size(), properties.getLocales().size(),
                properties.getBaseCurrency(), properties.getBaseCountry());
    }

    public List<ReferenceDataProperties.Currency> all() {
        return List.copyOf(byCode.values());
    }

    public Optional<ReferenceDataProperties.Currency> find(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(byCode.get(normalise(code)));
    }

    public boolean isSupported(String code) {
        return find(code).isPresent();
    }

    /** The code, normalised, or a refusal naming what is actually on offer. */
    public String require(String code) {
        return find(code)
                .map(currency -> currency.getCode().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new BadRequestException(
                        (code == null || code.isBlank() ? "A currency" : code)
                                + " is not a currency this marketplace trades in. Supported: "
                                + String.join(", ", byCode.keySet())));
    }

    /**
     * How many decimal places this currency has. Two when it is unknown, which
     * is the commonest case and the safer guess.
     */
    public int minorUnits(String code) {
        return find(code).map(ReferenceDataProperties.Currency::getMinorUnits).orElse(2);
    }

    /**
     * Rounds an amount to something that currency can actually express.
     *
     * <p>HALF_UP rather than HALF_EVEN: banker's rounding is the right choice for
     * summing many figures without bias, and the wrong one for a single price a
     * person is about to be charged, where the only defensible behaviour is the
     * one they would get doing it by hand.
     */
    public BigDecimal round(BigDecimal amount, String currencyCode) {
        if (amount == null) {
            return null;
        }
        return amount.setScale(minorUnits(currencyCode), RoundingMode.HALF_UP);
    }

    /**
     * The smallest amount this currency can express — one franc, one butut.
     *
     * <p>What a caller needs to decide whether a difference is real or just the
     * residue of a conversion.
     */
    public BigDecimal smallestUnit(String currencyCode) {
        return BigDecimal.ONE.movePointLeft(minorUnits(currencyCode));
    }

    public String baseCurrency() {
        return normalise(properties.getBaseCurrency());
    }

    private static String normalise(String code) {
        return code == null ? null : code.trim().toUpperCase(Locale.ROOT);
    }
}
