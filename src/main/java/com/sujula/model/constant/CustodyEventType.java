package com.sujula.model.constant;

/**
 * A thing that happened to a parcel, with proof attached.
 *
 * <p>These are the links in the chain. A shipment's status is derived from the
 * events recorded against it and is never set directly, which is C4 stated as a
 * data model: a status field somebody can assign is a custody chain with a hole
 * in it, and the hole is exactly where a parcel reaches DELIVERED without anyone
 * having handed it to anyone.
 *
 * <p>Every one of these carries evidence — a code the receiving party presented,
 * a position, a photograph — and refuses to be written without it. The status
 * that follows is a consequence rather than an input.
 */
public enum CustodyEventType {

    /**
     * The driver is at the shop and says so.
     *
     * <p>Not a transfer: nothing has changed hands. It exists because the moment
     * a driver claims to be somewhere is worth recording separately from the
     * moment they claim to have been given something, and because a seller
     * waiting on a collection wants to know a driver has arrived.
     */
    ARRIVED_AT_ORIGIN,

    /** The seller handed the parcel over, and the driver presented the release code. */
    COLLECTED,

    /** The driver left it at a pickup point, which acknowledged it. */
    DEPOSITED,

    /** A pickup point gave it back to a driver for the last leg. */
    REDISPATCHED,

    /**
     * The recipient has it.
     *
     * <p>The end of the chain, and the only event that releases the seller's
     * money. Requires the recipient's code, a position inside the geofence and a
     * photograph — because this is the link somebody would forge if any single
     * one of those were enough on its own.
     */
    RELEASED,

    /**
     * One driver handed it to another.
     *
     * <p>Needs both of them: the parcel is in two people's hands for a moment
     * and a chain that recorded only one side would have a link nobody can
     * attest to.
     */
    TRANSFERRED,

    /**
     * Delivery was attempted and did not happen.
     *
     * <p>Custody does not move — the driver still has the parcel, which is the
     * whole point of recording this rather than simply ending the chain.
     */
    FAILED_ATTEMPT,

    /** It went back to the seller or to a hub after too many failures. */
    RETURNED;

    /** Whether this event moves the parcel between hands. */
    public boolean isTransfer() {
        return this == COLLECTED || this == DEPOSITED || this == REDISPATCHED
                || this == RELEASED || this == TRANSFERRED || this == RETURNED;
    }

    /**
     * Whether a code has to be presented for this event.
     *
     * <p>Every transfer of possession does. The party <em>giving</em> the parcel
     * up holds the code and reads it to the party taking it, so presenting it
     * proves the two were in the same place — which is the only thing a code can
     * prove and the entire reason for one.
     */
    public boolean requiresCode() {
        return this == COLLECTED || this == DEPOSITED || this == RELEASED || this == TRANSFERRED;
    }

    /** Whether the chain is finished after this. */
    public boolean isTerminal() {
        return this == RELEASED || this == RETURNED;
    }
}
