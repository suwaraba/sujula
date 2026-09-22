package com.sujula.service.promotion;

import org.springframework.data.domain.Pageable;

import com.sujula.dto.request.promotion.PromotionRequests;
import com.sujula.dto.response.promotion.PromotionResponses;
import com.sujula.model.constant.PromotionStatus;

/**
 * A seller's own discounts.
 *
 * <p>Two kinds, and the difference is who knows about them. A promotion is a
 * price the shop is charging, visible to anyone looking. A coupon is a
 * credential a buyer presents. They look alike and behave differently: a coupon
 * can be capped per customer because there is a customer to count, and a
 * promotion cannot.
 *
 * <p>Every amount a seller states is in their settlement currency and in no
 * other. A buyer paying in EUR sees it converted at the rate their order was
 * quoted at — re-striking the discount in the buyer's currency would leave the
 * seller funding an amount that moves with the market.
 */
public interface PromotionService {

    PromotionResponses.PromotionPage list(Long userId, PromotionStatus status, Pageable pageable);

    /** Created as a DRAFT. Activation is its own endpoint, and so is its check. */
    PromotionResponses.Promotion create(Long userId, PromotionRequests.CreatePromotion request);

    PromotionResponses.Promotion update(Long userId, Long promotionId,
                                        PromotionRequests.UpdatePromotion request);

    /** Cancelled rather than removed once it has discounted anything: orders reference it. */
    void delete(Long userId, Long promotionId);

    /**
     * Turns it on, unless something else is already discounting the same goods.
     *
     * <p>Two overlapping promotions on one product do not compound into a
     * sensible price — they compound into whichever the pricing code applies
     * first, which is a different answer on different days. So the refusal names
     * the conflict rather than describing it.
     */
    PromotionResponses.Activated activate(Long userId, Long promotionId);

    // -- Coupons -------------------------------------------------------------

    PromotionResponses.CouponPage coupons(Long userId, boolean activeOnly, Pageable pageable);

    PromotionResponses.Coupon createCoupon(Long userId, PromotionRequests.CreateCoupon request);

    PromotionResponses.Coupon updateCoupon(Long userId, Long couponId,
                                           PromotionRequests.UpdateCoupon request);

    /** Who redeemed it. Names rather than email addresses. */
    PromotionResponses.Redemptions redemptions(Long userId, Long couponId, Pageable pageable);
}
