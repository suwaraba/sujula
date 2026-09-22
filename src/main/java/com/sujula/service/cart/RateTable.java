package com.sujula.service.cart;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;

/**
 * Immutable snapshot of the exchange rates needed to price one read.
 *
 * <p>Built once per response from a single batched lookup, so a cart spanning
 * N vendor currencies still costs one rate query rather than one per line.
 *
 * <p>A rate keyed by {@code from} converts an amount in {@code from} into the
 * table's target currency: {@code targetAmount = amount * rate}.
 *
 * <p>Two things it carries that a bare map does not, and both are required
 * rather than convenient.
 *
 * <p><strong>When the rates were taken.</strong> A converted figure that does
 * not record its rate and the moment of it cannot be explained afterwards — the
 * table has moved by the next morning. Anything that stores a converted amount
 * stores this alongside it.
 *
 * <p><strong>The target's real scale.</strong> Rounding every conversion to two
 * places is right for dalasi and sterling and wrong for CFA, which has no minor
 * unit at all: a total of 1250.50 XOF is an amount nobody can tender. The table
 * is told its target's scale when it is built, so a conversion lands on
 * something the currency can express.
 */
public final class RateTable {

    /**
     * The scale most currencies use.
     *
     * <p>Kept for the arithmetic that operates on amounts already denominated in
     * a known currency and does its own rounding. New code that converts between
     * currencies should use {@link #scale()} instead, which knows what the target
     * currency actually is.
     */
    public static final int MONEY_SCALE = 2;

    private final String target;
    private final Map<String, BigDecimal> rates;
    private final int scale;
    private final LocalDateTime takenAt;

    /**
     * @param scale   decimal places the target currency has — 0 for XOF, 2 for
     *                most others
     * @param takenAt when these rates were read, to be frozen onto anything
     *                priced from them
     */
    public RateTable(String target, Map<String, BigDecimal> rates, int scale, LocalDateTime takenAt) {
        this.target = target;
        this.rates = Collections.unmodifiableMap(rates);
        this.scale = scale;
        this.takenAt = takenAt;
    }

    /**
     * A table that rounds to two places and does not know when it was read.
     *
     * <p>The older shape. Callers that price money a buyer or a vendor will see
     * should use the four-argument constructor: without a timestamp there is
     * nothing to freeze onto the order, and without the target's scale a CFA
     * total comes out carrying centimes.
     */
    public RateTable(String target, Map<String, BigDecimal> rates) {
        this(target, rates, MONEY_SCALE, null);
    }

    public String target() {
        return target;
    }

    /** Decimal places the target currency has. */
    public int scale() {
        return scale;
    }

    /** When these rates were read. Null on a table built without one. */
    public LocalDateTime takenAt() {
        return takenAt;
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
     * Converts and rounds to the target currency's own scale.
     *
     * @return null when no rate is known for {@code currency}, so a missing rate
     *         can never be mistaken for a zero amount
     */
    public BigDecimal convert(BigDecimal amount, String currency) {
        if (amount == null) return null;
        BigDecimal rate = rateFor(currency);
        if (rate == null) return null;
        return amount.multiply(rate).setScale(scale, RoundingMode.HALF_UP);
    }

    /** Rounds an amount already denominated in the target currency. */
    public BigDecimal roundTarget(BigDecimal amount) {
        return amount == null ? null : amount.setScale(scale, RoundingMode.HALF_UP);
    }

    /** Rounds to two places, for amounts whose currency is not this table's target. */
    public static BigDecimal round(BigDecimal amount) {
        return amount == null ? null : amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
