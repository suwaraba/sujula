import type { CouponType, PromotionStatus, PromotionType } from '@/api/types';
import type { Tone } from '@/components/ui';

/**
 * What each promotion type actually asks for.
 *
 * The server validates per type — BUY_X_GET_Y needs quantities, PERCENT needs
 * a percentage, BUNDLE needs a price — so the form asks for exactly those and
 * nothing else. A form that shows every field for every type is a form where
 * most of what you fill in is silently discarded.
 */
export type PromotionFields = {
  percentOff?: boolean;
  amountOff?: boolean;
  quantities?: boolean;
  getDiscountPercent?: boolean;
  bundlePrice?: boolean;
};

export const PROMOTION_TYPES: {
  value: PromotionType;
  label: string;
  blurb: string;
  fields: PromotionFields;
}[] = [
  {
    value: 'PERCENT',
    label: 'Percentage off',
    blurb: '20% off everything in this promotion.',
    fields: { percentOff: true },
  },
  {
    value: 'FIXED',
    label: 'Amount off',
    blurb: 'A flat sum off, in your own currency.',
    fields: { amountOff: true },
  },
  {
    value: 'BUY_X_GET_Y',
    label: 'Buy some, get some',
    blurb: 'Buy two, get the third free — or at a discount.',
    fields: { quantities: true, getDiscountPercent: true },
  },
  {
    value: 'FREE_SHIPPING',
    label: 'Free delivery',
    blurb: 'Delivery costs nothing on a qualifying basket.',
    fields: {},
  },
  {
    value: 'BUNDLE',
    label: 'Bundle price',
    blurb: 'These items together for one price.',
    fields: { bundlePrice: true },
  },
];

export function promotionFields(type: PromotionType): PromotionFields {
  return PROMOTION_TYPES.find((entry) => entry.value === type)?.fields ?? {};
}

export const PROMOTION_STATUS_TONE: Record<PromotionStatus, Tone> = {
  DRAFT: 'neutral',
  ACTIVE: 'ok',
  PAUSED: 'warn',
  EXPIRED: 'neutral',
  CANCELLED: 'danger',
};

export const PROMOTION_STATUS_LABEL: Record<PromotionStatus, string> = {
  DRAFT: 'Draft',
  ACTIVE: 'On',
  PAUSED: 'Paused',
  EXPIRED: 'Finished',
  CANCELLED: 'Cancelled',
};

export const COUPON_TYPES: { value: CouponType; label: string; blurb: string }[] = [
  { value: 'PERCENTAGE', label: 'Percentage off', blurb: 'A share off the basket.' },
  { value: 'FIXED_AMOUNT', label: 'Amount off', blurb: 'A flat sum off, in your own currency.' },
  { value: 'FREE_SHIPPING', label: 'Free delivery', blurb: 'Delivery costs nothing.' },
];

/**
 * `datetime-local` wants `YYYY-MM-DDTHH:mm` and the API writes `LocalDateTime`,
 * which is the same shape with seconds. Converting both ways here keeps the
 * screens from doing string surgery.
 */
export function toLocalInput(value: string | null | undefined): string {
  if (!value) return '';
  return value.slice(0, 16);
}

export function fromLocalInput(value: string): string | undefined {
  const trimmed = value.trim();
  if (!trimmed) return undefined;
  return trimmed.length === 16 ? `${trimmed}:00` : trimmed;
}
