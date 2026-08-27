package com.sujula.service.cart;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.Map;

/**
 * Immutable snapshot of the exchange rates needed to price one cart read.
 *
 * <p>Built once per response from a single batched lookup, so a cart spanning
 * N vendor currencies still costs one rate query rather than one per line.
 *
 * <p>A rate keyed by {@code from} converts an amount in {@code from} into the
 * table's target currency: {@code targetAmount = amount * rate}.
 */
public final class RateTable {

    /** Money scale used for every converted amount. */
    public static final int MONEY_SCALE = 2;

    private final String target;
    private final Map<String, BigDecimal> rates;

    public RateTable(String target, Map<String, BigDecimal> rates) {
        this.target = target;
        this.rates = Collections.unmodifiableMap(rates);
    }

    public String target() {
        return target;
    }

    /** Rate from {@code currency} into the target, or null when unknown. */
    public BigDecimal rateFor(String currency) {
        if (currency == null) return null;
        String c = currency.toUpperCase();
        if (c.equals(target)) return BigDecimal.ONE;
        return rates.get(c);
    }

    public boolean canConvert(String currency) {
        return rateFor(currency) != null;
    }

    /**
     * Converts and rounds to {@link #MONEY_SCALE}.
     *
     * @return null when no rate is known for {@code currency}, so a missing rate
     *         can never be mistaken for a zero amount
     */
    public BigDecimal convert(BigDecimal amount, String currency) {
        if (amount == null) return null;
        BigDecimal rate = rateFor(currency);
        if (rate == null) return null;
        return amount.multiply(rate).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** Rounds an amount already denominated in the target currency. */
    public static BigDecimal round(BigDecimal amount) {
        return amount == null ? null : amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
