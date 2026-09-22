package com.sujula.model.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * What the platform adds to a published rate, and from when.
 *
 * <p>The spread is how a marketplace charging in euro and paying out in dalasi
 * survives a rate that moves between the two. It is real revenue and it is the
 * buyer's money, so it is a record with a start time and an author rather than a
 * number in a properties file that changes when somebody redeploys.
 *
 * <p><b>It never reaches backwards.</b> Every converted figure anybody is
 * charged already carries the rate it was converted at and the moment that rate
 * was taken (C2); a spread written today applies to conversions from today.
 * Rows are appended, never edited, and the one in force at an instant is the
 * latest whose {@code effectiveFrom} has passed — which is what makes it
 * possible to answer "why was this order converted at 0.0112" a year later.
 *
 * <p>A null {@code fromCurrency} or {@code toCurrency} is a wildcard, so a
 * platform can set one spread everywhere and override a single busy pair. The
 * most specific row in force wins, and specificity is stated rather than left to
 * query order.
 */
@Entity
@Table(name = "fx_spreads",
       indexes = {
           @Index(name = "idx_spread_pair", columnList = "fromCurrency, toCurrency"),
           @Index(name = "idx_spread_from", columnList = "effectiveFrom")
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FxSpread {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ISO 4217, or null for "any source currency". */
    @Column(length = 3)
    private String fromCurrency;

    /** ISO 4217, or null for "any target currency". */
    @Column(length = 3)
    private String toCurrency;

    /**
     * Basis points added to the published rate. 150 is 1.5%.
     *
     * <p>Basis points rather than a decimal fraction, because a spread typed as
     * 0.015 and a spread typed as 1.5 are indistinguishable to a form and differ
     * by a factor of a hundred in what a buyer pays.
     */
    @Column(nullable = false)
    private int basisPoints;

    @Column(nullable = false)
    private LocalDateTime effectiveFrom;

    @Column(nullable = false)
    private Long setByUserId;

    @Column(length = 500)
    private String reason;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    /** Whether this row speaks about converting {@code from} into {@code to}. */
    public boolean covers(String from, String to) {
        return (fromCurrency == null || fromCurrency.equalsIgnoreCase(from))
                && (toCurrency == null || toCurrency.equalsIgnoreCase(to));
    }

    /** A named pair beats a wildcard; two named sides beat one. */
    public int specificity() {
        return (fromCurrency != null ? 1 : 0) + (toCurrency != null ? 1 : 0);
    }

    /** The spread as a multiplier: 150bp becomes 1.0150. */
    public BigDecimal multiplier() {
        return BigDecimal.ONE.add(
                BigDecimal.valueOf(basisPoints).divide(BigDecimal.valueOf(10_000), 8,
                        RoundingMode.HALF_UP));
    }

    /** Human form for a response: 150 becomes "1.50%". */
    public String asPercentage() {
        return BigDecimal.valueOf(basisPoints)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP) + "%";
    }
}
