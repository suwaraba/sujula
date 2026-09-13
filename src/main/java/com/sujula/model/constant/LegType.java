package com.sujula.model.constant;

/**
 * Which hop of the journey a leg is.
 *
 * <p>A parcel from Banjul to a sister in Serrekunda may go straight from the
 * shop to her door, or through a hub because the driver who can reach the shop
 * is not the one who covers her street. Both are ordinary, so a shipment is a
 * list of legs rather than one journey with one driver.
 */
public enum LegType {

    /** Shop to recipient, one driver, no hub. */
    ORIGIN_TO_RECIPIENT,

    /** Shop to a pickup point. */
    ORIGIN_TO_PICKUP,

    /** Pickup point to the recipient's door. */
    PICKUP_TO_RECIPIENT,

    /** Hub to hub, which is how anything crossing a region moves. */
    PICKUP_TO_PICKUP,

    /**
     * The recipient collects it themselves from a pickup point.
     *
     * <p>Has no driver, and still has a leg: the handover at the counter is a
     * link in the chain and needs the same proof as any other.
     */
    PICKUP_TO_COUNTER;

    /** Whether this leg ends with the goods in the recipient's hands. */
    public boolean endsWithRecipient() {
        return this == ORIGIN_TO_RECIPIENT || this == PICKUP_TO_RECIPIENT
                || this == PICKUP_TO_COUNTER;
    }

    /** Whether this leg starts at the seller's shop. */
    public boolean startsAtOrigin() {
        return this == ORIGIN_TO_RECIPIENT || this == ORIGIN_TO_PICKUP;
    }

    /** Whether a driver carries this leg at all. */
    public boolean needsDriver() {
        return this != PICKUP_TO_COUNTER;
    }
}
