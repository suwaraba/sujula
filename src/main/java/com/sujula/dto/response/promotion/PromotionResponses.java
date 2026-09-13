package com.sujula.dto.response.promotion;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sujula.model.constant.CouponType;
import com.sujula.model.constant.PromotionStatus;
import com.sujula.model.constant.PromotionType;

/** What a seller sees of their own discounts. */
public final class PromotionResponses {

    private PromotionResponses() {}

    /**
     * @param running whether it is discounting anything at this moment, which is
     *                not the same as being ACTIVE: a scheduled promotion is
     *                active and not yet running
     * @param currency the vendor's own, always. A buyer sees this converted at
     *                the rate their order was quoted at
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Promotion(
            Long id,
            String name,
            String description,
            PromotionType type,
            PromotionStatus status,
            boolean running,
            String blockedReason,

            BigDecimal percentOff,
            BigDecimal amountOff,
            String currency,
            Integer buyQuantity,
            Integer getQuantity,
            BigDecimal getDiscountPercent,
            BigDecimal bundlePrice,
            BigDecimal minimumBasket,
            BigDecimal maximumDiscount,

            boolean storeWide,
            Set<Long> productIds,
            Set<Long> categoryIds,

            LocalDateTime startsAt,
            LocalDateTime endsAt,
            int timesApplied,
            LocalDateTime activatedAt,
            LocalDateTime createdAt) {}

    public record PromotionPage(
            List<Promotion> items, int page, int size, long totalElements, int totalPages) {}

    /**
     * @param conflicts the promotions an activation was refused over, named.
     *                  "Conflicts with an existing promotion" leaves a seller
     *                  hunting; naming the one and the goods it covers does not
     */
    public record Activated(
            Long id,
            PromotionStatus status,
            boolean running,
            List<Conflict> conflicts,
            String message) {}

    public record Conflict(Long promotionId, String name, String overlap, List<Long> productIds) {}

    // -- Coupons -------------------------------------------------------------

    /**
     * @param timesUsed how many redemptions, against the limit. The two numbers
     *                  are what a seller watching a campaign actually reads
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Coupon(
            Long id,
            String code,
            String description,
            CouponType type,
            BigDecimal value,
            String currency,
            BigDecimal minimumOrderAmount,
            BigDecimal maximumDiscountAmount,
            Integer usageLimit,
            int timesUsed,
            Integer remaining,
            Integer perUserLimit,
            boolean active,
            boolean redeemable,
            String blockedReason,
            LocalDateTime startsAt,
            LocalDateTime expiresAt,
            LocalDateTime createdAt) {}

    public record CouponPage(
            List<Coupon> items, int page, int size, long totalElements, int totalPages) {}

    /**
     * One redemption.
     *
     * @param customer a name, not an email. A seller needs to recognise a
     *                 customer, not to be handed a mailing list with their
     *                 campaign report
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Redemption(
            Long id,
            String customer,
            Long orderId,
            LocalDateTime usedAt) {}

    public record Redemptions(
            String code,
            int timesUsed,
            Integer usageLimit,
            List<Redemption> redemptions,
            int page, int size, long totalElements, int totalPages) {}
}
