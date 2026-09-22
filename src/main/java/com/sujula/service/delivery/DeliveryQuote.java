package com.sujula.service.delivery;

import com.sujula.model.constant.DeliveryMode;

import java.math.BigDecimal;
import java.util.List;

/**
 * What delivering a basket costs, priced one product at a time.
 *
 * <p>There is no single "shipping cost" on a multivendor order: two products in
 * the same basket may leave from two different towns, weigh different amounts
 * and travel under different delivery scopes. Each therefore gets its own leg
 * with its own distance, weight and price, and the order-level figure is only
 * their sum.
 *
 * @param currency the currency every {@code cost} is expressed in
 * @param total    sum of the legs
 * @param complete false when a leg could not be converted into {@code currency};
 *                 the total is then incomplete and must not be charged
 * @param legs     one entry per priced product, in the order they were passed in
 */
public record DeliveryQuote(String currency,
                            BigDecimal total,
                            boolean complete,
                            List<DeliveryLeg> legs) {

    /**
     * One product's journey from its vendor to the buyer.
     *
     * @param distanceKm        vendor origin to destination, great-circle
     * @param distanceEstimated true when coordinates were unavailable and a
     *                          scope-based fallback distance was used instead
     * @param billableWeightKg  unit weight × quantity, or the configured default
     *                          weight when the vendor recorded none
     * @param cost              this leg's price in the quote's currency; zero
     *                          when waived
     * @param waivedReason      why the leg is free, or null when it is charged
     */
    public record DeliveryLeg(Long productId,
                              Long vendorId,
                              String productName,
                              int quantity,
                              BigDecimal billableWeightKg,
                              BigDecimal distanceKm,
                              boolean distanceEstimated,
                              DeliveryMode mode,
                              BigDecimal cost,
                              String waivedReason) {

        public boolean isWaived() {
            return waivedReason != null;
        }
    }
}
