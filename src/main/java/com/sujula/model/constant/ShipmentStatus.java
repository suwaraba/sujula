package com.sujula.model.constant;

/**
 * Where a parcel is, derived from what has happened to it.
 *
 * <p><strong>Nothing sets this.</strong> It is computed from the shipment's
 * custody events every time one is appended, which is what makes it trustworthy:
 * a status nobody can assign cannot be wrong about whether a handover took
 * place. If this enum and the events disagree, the events are right and the
 * status is a bug.
 */
public enum ShipmentStatus {

    /** Packed by the seller, waiting for a driver to be found. */
    AWAITING_COLLECTION,

    /** A driver has been offered the first leg and has not answered. */
    DRIVER_OFFERED,

    /** A driver accepted and is on the way to the shop. */
    DRIVER_ASSIGNED,

    /** The driver is at the shop. */
    AT_ORIGIN,

    /** Collected, and moving. */
    IN_TRANSIT,

    /** Sitting at a pickup point, waiting for the recipient or the next leg. */
    AT_PICKUP_POINT,

    /** On its last leg to the recipient. */
    OUT_FOR_DELIVERY,

    /** The recipient has it. */
    DELIVERED,

    /** An attempt failed and another is scheduled. */
    ATTEMPT_FAILED,

    /** Gone back to the seller. */
    RETURNED,

    /** Cancelled before it moved. */
    CANCELLED;

    /**
     * Whether the goods are currently in somebody's hands on this platform.
     *
     * <p>What gates the recipient's address and phone number on the driver's
     * screen. A driver holding a parcel needs to know where it goes; a driver
     * who handed it over an hour ago does not, and one who declined the job
     * never did.
     */
    public boolean isCustodyActive() {
        return this == DRIVER_ASSIGNED || this == AT_ORIGIN || this == IN_TRANSIT
                || this == OUT_FOR_DELIVERY || this == ATTEMPT_FAILED;
    }

    public boolean isFinished() {
        return this == DELIVERED || this == RETURNED || this == CANCELLED;
    }
}
