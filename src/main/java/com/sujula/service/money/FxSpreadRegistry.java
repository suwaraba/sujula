package com.sujula.service.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.model.finance.FxSpread;
import com.sujula.repository.finance.FxSpreadRepository;

/**
 * What the platform adds to a published rate, at a given moment.
 *
 * <p>Takes a moment rather than assuming now, and that is the whole design. A
 * buyer charged in euro for goods priced in dalasi was charged at one rate, with
 * one spread, at one instant — and both halves have to remain answerable
 * afterwards or the total is a figure nobody can explain (C2). Rows are
 * appended, never edited, so asking this class about March gets March's answer
 * however many times the spread has moved since.
 *
 * <p>No spread configured is a spread of zero rather than an error. A platform
 * that refused to convert until somebody filled in a form would refuse its first
 * order; charging the published rate with nothing added is both a safe default
 * and an honest one.
 */
@Component
public class FxSpreadRegistry {

    private final FxSpreadRepository spreads;

    public FxSpreadRegistry(FxSpreadRepository spreads) {
        this.spreads = spreads;
    }

    /** The spread that applied, and the row it came from. */
    public record Applied(int basisPoints, Long spreadId, LocalDateTime effectiveFrom,
                          BigDecimal multiplier) {

        /** Nothing configured: the published rate, untouched. */
        static Applied none() {
            return new Applied(0, null, null, BigDecimal.ONE);
        }
    }

    /**
     * The spread in force for a conversion at an instant.
     *
     * @param from the currency being converted out of — the vendor's, on a
     *             listing, since that is what a price starts in
     * @param to   the currency the buyer is charged in
     * @param at   when. Today for a live quote; the order's own
     *             {@code fxRateAt} when re-explaining a figure already charged
     */
    @Transactional(readOnly = true)
    public Applied resolve(String from, String to, LocalDateTime at) {
        if (from == null || to == null || from.equalsIgnoreCase(to)) {
            return Applied.none();   // no conversion happened, so nothing to add to
        }
        List<FxSpread> inForce = spreads.findInForceAt(at == null ? LocalDateTime.now() : at);

        Optional<FxSpread> best = inForce.stream()
                .filter(s -> s.covers(from, to))
                // Most specific wins, and a later start breaks a tie between two
                // equally specific rows — which is what happens the day one
                // supersedes another.
                .max(Comparator.comparingInt(FxSpread::specificity)
                        .thenComparing(FxSpread::getEffectiveFrom));

        return best.map(s -> new Applied(s.getBasisPoints(), s.getId(), s.getEffectiveFrom(),
                        s.multiplier()))
                .orElseGet(Applied::none);
    }

    /**
     * What the platform earned on one converted figure.
     *
     * <p>The difference between what the buyer was charged and what the same
     * amount would have come to at the published rate. Derived from the rate and
     * spread the order already carries rather than from today's — a spread
     * changed since must not change what March's orders appear to have earned.
     *
     * @param chargedInDisplay what the buyer actually paid, in their currency
     * @param basisPoints      the spread that was applied, from the order
     */
    public static BigDecimal marginOn(BigDecimal chargedInDisplay, int basisPoints, int scale) {
        if (chargedInDisplay == null || basisPoints == 0) {
            return BigDecimal.ZERO;
        }
        // charged = base * (1 + bp/10000), so the margin is charged * bp / (10000 + bp).
        BigDecimal bp = BigDecimal.valueOf(basisPoints);
        return chargedInDisplay.multiply(bp)
                .divide(BigDecimal.valueOf(10_000).add(bp), scale, RoundingMode.HALF_UP);
    }
}
