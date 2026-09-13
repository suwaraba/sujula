package com.sujula.dto.request.promotion;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

import com.sujula.model.constant.CouponType;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** What a seller sends about their own discounts. */
public final class PromotionRequests {

    private PromotionRequests() {}

    /**
     * A promotion, which is a price rather than a credential.
     *
     * <p>There is no currency field. A FIXED amount is in the vendor's
     * settlement currency, like every other figure they state: what a buyer in
     * Madrid sees is that amount converted at the rate their order was quoted
     * at. Letting a seller denominate a discount in EUR would have them funding
     * an amount that moves with the market between the promotion being written
     * and the order being placed.
     *
     * <p>Nor is there a status. A promotion is created as a DRAFT and activated
     * by its own endpoint, which is where the overlap check lives — a status a
     * client could set on the way in would be a way past it.
     */
    public record CreatePromotion(
            @NotBlank @Size(max = 150) String name,
            @Size(max = 300) String description,
            @NotNull com.sujula.model.constant.PromotionType type,

            /** PERCENT: one to a hundred. */
            @DecimalMin("0.01") BigDecimal percentOff,

            /** FIXED: in the store's own currency. */
            @DecimalMin("0.01") BigDecimal amountOff,

            /** BUY_X_GET_Y. */
            @Min(1) Integer buyQuantity,
            @Min(1) Integer getQuantity,
            @DecimalMin("0.01") BigDecimal getDiscountPercent,

            /** BUNDLE: what the whole set costs together. */
            @DecimalMin("0.01") BigDecimal bundlePrice,

            @DecimalMin("0.00") BigDecimal minimumBasket,
            @DecimalMin("0.01") BigDecimal maximumDiscount,

            /** Empty means the whole shop. */
            Set<Long> productIds,
            Set<Long> categoryIds,

            LocalDateTime startsAt,
            LocalDateTime endsAt) {}

    /** Editing one. Absent fields are left alone; the status is not editable here. */
    public record UpdatePromotion(
            @Size(max = 150) String name,
            @Size(max = 300) String description,
            @DecimalMin("0.01") BigDecimal percentOff,
            @DecimalMin("0.01") BigDecimal amountOff,
            @Min(1) Integer buyQuantity,
            @Min(1) Integer getQuantity,
            @DecimalMin("0.01") BigDecimal getDiscountPercent,
            @DecimalMin("0.01") BigDecimal bundlePrice,
            @DecimalMin("0.00") BigDecimal minimumBasket,
            @DecimalMin("0.01") BigDecimal maximumDiscount,
            Set<Long> productIds,
            Set<Long> categoryIds,
            LocalDateTime startsAt,
            LocalDateTime endsAt,
            /** True to stop a running promotion without cancelling it. */
            Boolean paused) {}

    // -- Coupons -------------------------------------------------------------

    /**
     * A coupon, which is a credential rather than a price.
     *
     * <p>The difference from a promotion is who knows about it, and it changes
     * what can be limited. A coupon can be capped per customer because there is
     * a customer to count; a promotion cannot, because there is nobody to count.
     *
     * @param code       upper-cased and unique platform-wide, so two shops
     *                   cannot issue the same word and leave a buyer holding a
     *                   code that means two things
     * @param perUserLimit how many times one account may use it. The field that
     *                   stops a launch offer being spent forty times by one
     *                   person with forty email addresses
     */
    public record CreateCoupon(
            @NotBlank @Size(min = 3, max = 40)
            @Pattern(regexp = "^[A-Za-z0-9_-]+$",
                     message = "Use letters, numbers, hyphens and underscores - it gets read out "
                             + "over the phone and typed on a small keypad")
            String code,
            @Size(max = 200) String description,
            @NotNull CouponType type,

            /** A percentage for PERCENTAGE, an amount for FIXED_AMOUNT, ignored for FREE_SHIPPING. */
            @DecimalMin("0.01") BigDecimal value,

            @DecimalMin("0.00") BigDecimal minimumOrderAmount,
            @DecimalMin("0.01") BigDecimal maximumDiscountAmount,

            @Min(1) Integer usageLimit,
            @Min(1) Integer perUserLimit,

            LocalDateTime startsAt,
            LocalDateTime expiresAt) {}

    public record UpdateCoupon(
            @Size(max = 200) String description,
            @DecimalMin("0.01") BigDecimal value,
            @DecimalMin("0.00") BigDecimal minimumOrderAmount,
            @DecimalMin("0.01") BigDecimal maximumDiscountAmount,
            @Min(1) Integer usageLimit,
            @Min(1) Integer perUserLimit,
            LocalDateTime startsAt,
            LocalDateTime expiresAt,
            Boolean active) {}
}
