package com.sujula.model.constant;

/** What a promotion takes off the price. */
public enum PromotionType {

    /** A percentage off each qualifying line. */
    PERCENT,

    /**
     * A fixed amount off.
     *
     * <p>Denominated in the vendor's settlement currency and in no other. A
     * buyer paying in EUR sees it converted at the rate their order was quoted
     * at; the seller is discounting their own money in their own units.
     */
    FIXED,

    /** Buy X of something, get Y of it free or reduced. */
    BUY_X_GET_Y,

    /** The delivery leg is free. Worth more here than the goods sometimes are. */
    FREE_SHIPPING,

    /** A set of products sold together for less than the sum of them. */
    BUNDLE;

    /** Whether this type needs a money amount rather than a percentage. */
    public boolean isMonetary() {
        return this == FIXED;
    }

    /** Whether this type needs the X and Y quantities set. */
    public boolean needsQuantities() {
        return this == BUY_X_GET_Y;
    }
}
