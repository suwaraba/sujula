package com.sujula.model.constant;

/** Where one physical handset is.
 *
 * <p>Serialised rather than counted, because a phone is not interchangeable
 * with another phone of the same model. It has an IMEI, a history, and in this
 * market a very real chance of having been stolen — so the platform tracks the
 * individual unit and not just how many there are.
 */
public enum ImeiStatus {

    /** On the shelf and sellable. */
    IN_STOCK,

    /** Held against an order that has not completed. */
    RESERVED,

    /** Gone to a buyer. */
    SOLD,

    /** Came back. Returns to the shelf only after it has been checked. */
    RETURNED,

    /** Sent for repair, and not sellable while it is away. */
    IN_REPAIR,

    /** Written off: damaged beyond sale, or lost. */
    WRITTEN_OFF,

    /**
     * Reported stolen, here or elsewhere.
     *
     * <p>Terminal and irreversible from this surface. A seller who could clear
     * this flag would be a seller who can launder a stolen handset through the
     * platform, and the whole reason to record an IMEI is to make that harder.
     */
    BLOCKED;

    /** Whether this unit counts towards what the shop can sell today. */
    public boolean isSellable() {
        return this == IN_STOCK;
    }

    /** Whether a seller may still move this unit between states. */
    public boolean isSellerChangeable() {
        return this != BLOCKED && this != SOLD;
    }
}
