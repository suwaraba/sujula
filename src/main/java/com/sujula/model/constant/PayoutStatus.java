package com.sujula.model.constant;

/**
 * Where a transfer has got to.
 *
 * <p>REQUESTED is the state this platform actually spends its time in. A seller
 * asks, and a person decides: money leaving is the one action no later API call
 * can undo, and on a marketplace where parcels may still be crossing a border
 * an automatic transfer is how you pay out a sale that is about to be refunded.
 */
public enum PayoutStatus {

    /** A seller has asked. Nothing has moved and nothing is committed. */
    REQUESTED,

    /** Approved and queued for the next transfer run. */
    PENDING,

    /** With the bank or the mobile-money provider. */
    PROCESSING,

    /** The money reached the seller. */
    COMPLETED,

    /**
     * It did not, and the reason is recorded.
     *
     * <p>The money goes back to the balance through a reversal entry rather than
     * by editing anything - the attempt happened, and a ledger that hid it would
     * leave a seller unable to explain a gap in their own statement.
     */
    FAILED,

    /** Withdrawn before it was approved, by the seller or by support. */
    CANCELLED;

    /** Whether this payout still has the money committed against it. */
    public boolean isOpen() {
        return this == REQUESTED || this == PENDING || this == PROCESSING;
    }

    /** Whether the money has left the platform. */
    public boolean isSettled() {
        return this == COMPLETED;
    }
}
