package com.sujula.service.delivery;

import com.sujula.model.constant.DeliveryMode;
import com.sujula.model.constant.DeliveryScope;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

/**
 * The delivery rate card.
 *
 * <p>Every amount here is denominated in {@link #currency}; a quote is computed
 * in that currency and converted once into whatever currency the buyer is
 * shopping in. Keeping one rate card rather than one per vendor currency means
 * two vendors the same distance away charge the same to deliver the same parcel,
 * whichever currency they happen to settle in.
 *
 * <p>Defaults are a sane Gambia-first starting point, not a pricing decision —
 * override them per deployment in {@code application.properties}.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "sujula.delivery.pricing")
public class DeliveryPricingProperties {

    /** ISO-4217 code every amount below is expressed in. */
    private String currency = "GMD";

    /** Charged on every parcel before distance and weight are added. */
    private BigDecimal baseFee = new BigDecimal("50.00");

    /** Distance the base fee already covers. */
    private BigDecimal includedKm = new BigDecimal("3");

    private BigDecimal perKm = new BigDecimal("12.00");

    /** Weight the base fee already covers. */
    private BigDecimal includedKg = new BigDecimal("1");

    private BigDecimal perKg = new BigDecimal("25.00");

    /** Floor for a chargeable leg. Collection at the vendor's own store is free and ignores this. */
    private BigDecimal minFee = new BigDecimal("50.00");

    /** Ceiling for a single leg; null leaves it uncapped. */
    private BigDecimal maxFee;

    /** Assumed weight for a product whose vendor never recorded one. */
    private BigDecimal defaultWeightKg = new BigDecimal("0.50");

    /**
     * Waive delivery for a vendor once the buyer's goods with that vendor reach
     * this value. Null disables free delivery entirely.
     */
    private BigDecimal freeAbove;

    /** How much further afield a product ships, applied to the whole leg. */
    private Map<DeliveryScope, BigDecimal> scopeMultiplier = defaultScopeMultipliers();

    /** Delivering to a hub costs less than to a door; collecting in store costs nothing. */
    private Map<DeliveryMode, BigDecimal> modeMultiplier = defaultModeMultipliers();

    /**
     * Distance assumed when coordinates are missing at either end and the
     * address cannot be geocoded — a buyer must still get a price.
     */
    private Map<DeliveryScope, BigDecimal> fallbackKm = defaultFallbackKm();

    public BigDecimal scopeMultiplierFor(DeliveryScope scope) {
        if (scope == null) {
            return BigDecimal.ONE;
        }
        return scopeMultiplier.getOrDefault(scope, BigDecimal.ONE);
    }

    public BigDecimal modeMultiplierFor(DeliveryMode mode) {
        if (mode == null) {
            return BigDecimal.ONE;
        }
        return modeMultiplier.getOrDefault(mode, BigDecimal.ONE);
    }

    /**
     * The rate card's formula, in the one place both callers can reach it.
     *
     * <p>{@code base + per-km beyond the included distance + per-kg beyond the
     * included weight}, scaled for how far the goods travel under their scope and
     * for how the buyer receives them, then clamped to the floor and ceiling.
     *
     * <p>It lives here rather than inside the quoting service because two things
     * price delivery: the per-product quote a basket needs, and the per-mode
     * quote {@code /delivery/quote} answers with. Kept as two copies of the
     * arithmetic they agree today and have no reason to keep agreeing — and a
     * shipping estimate that disagrees with what checkout charges is the kind of
     * discrepancy a shopper notices.
     *
     * <p>In the rate card's own currency; converting is the caller's job.
     *
     * @param distanceKm how far the parcel travels
     * @param weightKg   billable weight
     */
    public BigDecimal priceLeg(BigDecimal distanceKm, BigDecimal weightKg,
                               DeliveryScope scope, DeliveryMode mode) {
        if (mode == DeliveryMode.VENDOR_PICKUP) {
            return BigDecimal.ZERO;   // nothing is delivered
        }

        BigDecimal chargeableKm = nonNull(distanceKm).subtract(includedKm).max(BigDecimal.ZERO);
        BigDecimal chargeableKg = nonNull(weightKg).subtract(includedKg).max(BigDecimal.ZERO);

        BigDecimal cost = baseFee
                .add(perKm.multiply(chargeableKm))
                .add(perKg.multiply(chargeableKg))
                .multiply(scopeMultiplierFor(scope))
                .multiply(modeMultiplierFor(mode));

        cost = cost.max(minFee);
        if (maxFee != null) {
            cost = cost.min(maxFee);
        }
        return cost.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static BigDecimal nonNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public BigDecimal fallbackKmFor(DeliveryScope scope) {
        if (scope == null) {
            return new BigDecimal("15");
        }
        return fallbackKm.getOrDefault(scope, new BigDecimal("15"));
    }

    private static Map<DeliveryScope, BigDecimal> defaultScopeMultipliers() {
        Map<DeliveryScope, BigDecimal> map = new EnumMap<>(DeliveryScope.class);
        map.put(DeliveryScope.REGIIONAL, BigDecimal.ONE);
        map.put(DeliveryScope.RECOGER,   new BigDecimal("0.50"));
        map.put(DeliveryScope.NATIONAL,  new BigDecimal("1.40"));
        map.put(DeliveryScope.GLOBAL,    new BigDecimal("2.50"));
        return map;
    }

    private static Map<DeliveryMode, BigDecimal> defaultModeMultipliers() {
        Map<DeliveryMode, BigDecimal> map = new EnumMap<>(DeliveryMode.class);
        map.put(DeliveryMode.HOME_DELIVERY, BigDecimal.ONE);
        map.put(DeliveryMode.PICKUP_POINT,  new BigDecimal("0.75"));
        map.put(DeliveryMode.VENDOR_PICKUP, BigDecimal.ZERO);
        return map;
    }

    private static Map<DeliveryScope, BigDecimal> defaultFallbackKm() {
        Map<DeliveryScope, BigDecimal> map = new EnumMap<>(DeliveryScope.class);
        map.put(DeliveryScope.REGIIONAL, new BigDecimal("15"));
        map.put(DeliveryScope.RECOGER,   new BigDecimal("5"));
        map.put(DeliveryScope.NATIONAL,  new BigDecimal("120"));
        map.put(DeliveryScope.GLOBAL,    new BigDecimal("800"));
        return map;
    }
}
