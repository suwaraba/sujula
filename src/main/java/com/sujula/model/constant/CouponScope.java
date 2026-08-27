package com.sujula.model.constant;

/**
 * Determines who funds a coupon's discount.
 *
 * <p>On a multivendor cart this is not cosmetic: the discount has to be
 * attributed to a payer before commission and vendor payouts can be computed.
 */
public enum CouponScope {

    /** Platform-funded. Applies across the whole cart and is prorated over vendor groups. */
    PLATFORM,

    /** Vendor-funded. Applies only to the issuing vendor's items. */
    VENDOR
}
