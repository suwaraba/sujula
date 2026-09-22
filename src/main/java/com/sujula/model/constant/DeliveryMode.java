package com.sujula.model.constant;

/**
 * How the buyer receives the goods. Chosen at checkout, it drives both the
 * delivery price (a leg to the door costs more than a leg to a hub, and
 * collecting at the store costs nothing) and which in-person payment methods
 * are offered.
 */
public enum DeliveryMode {

    /** Courier delivers to the buyer's address. */
    HOME_DELIVERY,

    /** Delivered to a pickup point; the buyer collects it there. */
    PICKUP_POINT,

    /** Buyer collects from the vendor's own store — no delivery leg at all. */
    VENDOR_PICKUP
}
