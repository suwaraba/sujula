package com.sujula.model.money;

import com.sujula.model.constant.FxSource;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The rate a figure was converted at, and the moment that rate was taken.
 *
 * <p>Without this, a converted amount is a number nobody can explain. The rate
 * table moves every day; an order priced last week cannot be re-derived from
 * today's rates, and a vendor asking why their payout was 166.01 GBP gets no
 * answer but the figure itself. Frozen here, the arithmetic stays checkable for
 * as long as the order exists — which for a financial record is indefinitely.
 *
 * <p>Embedded rather than a table of its own because a snapshot has no identity
 * apart from the row it explains, and because a join to answer "what rate was
 * this" would be paid on every settlement report.
 *
 * <p>The convention is fixed and worth stating once, since getting it backwards
 * is silent: {@code display = native × rate}. The rate is units of the buyer's
 * currency per one unit of the vendor's.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class FxSnapshot {

    /** The vendor's currency — what the goods were listed and will be settled in. */
    @Column(name = "fx_native_currency", length = 3)
    private String nativeCurrency;

    /** The buyer's currency — what they were charged in. */
    @Column(name = "fx_display_currency", length = 3)
    private String displayCurrency;

    /**
     * Units of {@link #displayCurrency} per one unit of {@link #nativeCurrency}.
     *
     * <p>Eight decimal places, because a rate is not money and must not be
     * rounded like one. Rounding it to the currency's own scale first would
     * flatten it — to zero places for XOF — and the error would then multiply
     * across every line of the order.
     */
    @Column(name = "fx_rate", precision = 18, scale = 8)
    private BigDecimal rate;

    /**
     * When the rate was published — not when this row was written.
     *
     * <p>The difference matters when a dispute turns on which day's rate applied.
     */
    @Column(name = "fx_rate_at")
    private LocalDateTime rateAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "fx_source", length = 20)
    private FxSource source;

    /** The held quote this rate was honoured from, when it came from one. */
    @Column(name = "fx_quote_id", length = 64)
    private String quoteId;

    /** Two currencies that were never actually different. */
    public static FxSnapshot identity(String currency, LocalDateTime at) {
        return new FxSnapshot(currency, currency, BigDecimal.ONE, at, FxSource.IDENTITY, null);
    }

    public static FxSnapshot published(String nativeCurrency, String displayCurrency,
                                       BigDecimal rate, LocalDateTime rateAt) {
        return new FxSnapshot(nativeCurrency, displayCurrency, rate, rateAt,
                FxSource.PUBLISHED_RATE, null);
    }

    public static FxSnapshot held(String nativeCurrency, String displayCurrency,
                                  BigDecimal rate, LocalDateTime rateAt, String quoteId) {
        return new FxSnapshot(nativeCurrency, displayCurrency, rate, rateAt,
                FxSource.HELD_QUOTE, quoteId);
    }

    /** Whether anything was actually recorded. */
    public boolean isRecorded() {
        return rate != null;
    }

    /** Whether the two sides were the same currency, so no conversion applied. */
    public boolean isIdentity() {
        return source == FxSource.IDENTITY
                || (nativeCurrency != null && nativeCurrency.equalsIgnoreCase(displayCurrency));
    }
}
