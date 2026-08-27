package com.sujula.model.constant;

/**
 * Machine-readable reason a cart line or the cart as a whole changed during
 * revalidation. Surfaced to the client so the storefront can explain the change
 * instead of silently altering what the shopper thought they were buying.
 */
public enum CartIssueType {

    /** Vendor changed the listing price since the item was added. */
    PRICE_CHANGED,

    /** Requested quantity exceeded available stock and was clamped. */
    QUANTITY_REDUCED,

    /** Nothing left in stock; the line cannot be checked out. */
    OUT_OF_STOCK,

    /** Product was deactivated or deleted after being added. */
    PRODUCT_UNAVAILABLE,

    /** Selected variant was deactivated or deleted after being added. */
    VARIANT_UNAVAILABLE,

    /** Vendor is no longer approved to sell. */
    VENDOR_UNAVAILABLE,

    /** No exchange rate available to convert this vendor's currency to the display currency. */
    RATE_UNAVAILABLE,

    /** An applied coupon stopped being valid and was dropped. */
    COUPON_REMOVED
}
