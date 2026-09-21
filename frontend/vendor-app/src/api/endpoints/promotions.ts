import { api } from '../client';
import type {
  Coupon, CouponPage, CouponType, Promotion, PromotionActivated,
  PromotionPage, PromotionStatus, PromotionType, Redemptions,
} from '../types';

export type PromotionInput = {
  name: string;
  description?: string;
  type: PromotionType;
  percentOff?: number;
  amountOff?: number;
  buyQuantity?: number;
  getQuantity?: number;
  getDiscountPercent?: number;
  bundlePrice?: number;
  minimumBasket?: number;
  maximumDiscount?: number;
  /** Naming neither products nor categories makes it store-wide. */
  productIds?: number[];
  categoryIds?: number[];
  startsAt?: string;
  endsAt?: string;
};

export type CouponInput = {
  code: string;
  description?: string;
  type: CouponType;
  value?: number;
  minimumOrderAmount?: number;
  maximumDiscountAmount?: number;
  usageLimit?: number;
  perUserLimit?: number;
  startsAt?: string;
  expiresAt?: string;
};

/**
 * Two different things a seller can discount with.
 *
 * A **promotion** applies itself: a buyer who puts the right goods in a basket
 * gets it without typing anything. A **coupon** is a code somebody has to know,
 * read out or type — which is why its format is constrained to what survives
 * being read over a phone onto a small keypad.
 *
 * Both are priced in the seller's own settlement currency, like everything else
 * they set. There is no currency field on the way in.
 */
export const promotionsApi = {
  list: (params: { status?: PromotionStatus; page?: number; size?: number }) =>
    api.get<PromotionPage>('/vendor/promotions', { query: params }),

  /** Created as a DRAFT; activating is its own call. */
  create: (input: PromotionInput, idempotencyKey?: string) =>
    api.post<Promotion>('/vendor/promotions', input, { idempotent: idempotencyKey ?? true }),

  update: (promotionId: number, input: Partial<PromotionInput> & { paused?: boolean }) =>
    api.patch<Promotion>(`/vendor/promotions/${promotionId}`, input),

  /** Answers with any overlapping promotions rather than refusing them. */
  activate: (promotionId: number, idempotencyKey?: string) =>
    api.post<PromotionActivated>(`/vendor/promotions/${promotionId}/activate`, undefined, {
      idempotent: idempotencyKey ?? true,
    }),

  remove: (promotionId: number) => api.delete<void>(`/vendor/promotions/${promotionId}`),

  // ── Coupons ────────────────────────────────────────────────────────────────

  coupons: (params: { activeOnly?: boolean; page?: number; size?: number }) =>
    api.get<CouponPage>('/vendor/coupons', { query: params }),

  createCoupon: (input: CouponInput, idempotencyKey?: string) =>
    api.post<Coupon>('/vendor/coupons', input, { idempotent: idempotencyKey ?? true }),

  /** The code itself cannot be changed — buyers may already be holding it. */
  updateCoupon: (
    couponId: number,
    input: Omit<Partial<CouponInput>, 'code' | 'type'> & { active?: boolean },
  ) => api.patch<Coupon>(`/vendor/coupons/${couponId}`, input),

  redemptions: (couponId: number, params: { page?: number; size?: number }) =>
    api.get<Redemptions>(`/vendor/coupons/${couponId}/redemptions`, { query: params }),
};
