package com.sujula.service.delivery;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.Map;

import com.sujula.model.constant.DeliveryMode;
import com.sujula.model.constant.DeliveryScope;

/**
 * A rate card's numbers, and the one place the leg formula lives.
 *
 * <p>There are two sources of these numbers: {@link DeliveryPricingProperties},
 * which is what a deployment starts with, and a {@code DeliveryRateCard} row,
 * which is what an administrator sets from that day forward. Both produce one of
 * these, and both price through the same method — because two copies of the
 * arithmetic agree on the day they are written and have no reason to keep
 * agreeing, and a shipping estimate that disagrees with what checkout charges is
 * the kind of discrepancy a shopper notices on the one order that matters.
 *
 * <p>Every amount is denominated in {@link #currency}. Converting into whatever
 * the buyer is shopping in is the caller's job, done once, with the rate
 * snapshotted — a leg priced here and converted twice is a leg nobody can
 * explain.
 */
public record LegRate(
        String currency,
        BigDecimal baseFee,
        BigDecimal includedKm,
        BigDecimal perKm,
        BigDecimal includedKg,
        BigDecimal perKg,
        BigDecimal minFee,
        BigDecimal maxFee,
        BigDecimal freeAbove,
        Map<DeliveryScope, BigDecimal> scopeMultiplier,
        Map<DeliveryMode, BigDecimal> modeMultiplier) {

    public LegRate {
        scopeMultiplier = scopeMultiplier == null
                ? new EnumMap<>(DeliveryScope.class) : new EnumMap<>(scopeMultiplier);
        modeMultiplier = modeMultiplier == null
                ? new EnumMap<>(DeliveryMode.class) : new EnumMap<>(modeMultiplier);
    }

    /**
     * {@code base + per-km beyond the included distance + per-kg beyond the
     * included weight}, scaled for how far the goods travel under their scope and
     * for how the buyer receives them, then clamped to the floor and ceiling.
     *
     * <p>In this card's own currency.
     *
     * @param distanceKm how far the parcel travels
     * @param weightKg   billable weight
     */
    public BigDecimal priceLeg(BigDecimal distanceKm, BigDecimal weightKg,
                               DeliveryScope scope, DeliveryMode mode) {
        if (mode == DeliveryMode.VENDOR_PICKUP) {
            return BigDecimal.ZERO;   // nothing is delivered
        }

        BigDecimal chargeableKm = nonNull(distanceKm).subtract(nonNull(includedKm)).max(BigDecimal.ZERO);
        BigDecimal chargeableKg = nonNull(weightKg).subtract(nonNull(includedKg)).max(BigDecimal.ZERO);

        BigDecimal cost = nonNull(baseFee)
                .add(nonNull(perKm).multiply(chargeableKm))
                .add(nonNull(perKg).multiply(chargeableKg))
                .multiply(scopeMultiplierFor(scope))
                .multiply(modeMultiplierFor(mode));

        if (minFee != null) {
            cost = cost.max(minFee);
        }
        if (maxFee != null) {
            cost = cost.min(maxFee);
        }
        return cost.setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal scopeMultiplierFor(DeliveryScope scope) {
        return scope == null ? BigDecimal.ONE : scopeMultiplier.getOrDefault(scope, BigDecimal.ONE);
    }

    public BigDecimal modeMultiplierFor(DeliveryMode mode) {
        return mode == null ? BigDecimal.ONE : modeMultiplier.getOrDefault(mode, BigDecimal.ONE);
    }

    private static BigDecimal nonNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
