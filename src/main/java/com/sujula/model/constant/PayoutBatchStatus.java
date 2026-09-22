package com.sujula.model.constant;

/**
 * Where a run of transfers has got to.
 *
 * <p>Three of these are before any money moves, which is deliberate: the
 * expensive mistakes in payouts are all made while assembling, and a state
 * machine that went straight from "created" to "sent" would have no moment in it
 * for anybody to look.
 */
public enum PayoutBatchStatus {

    /**
     * Assembled and not yet put up for approval.
     *
     * <p>Items can still be dropped here. Nothing is committed and no vendor has
     * been told anything.
     */
    DRAFT,

    /** Sitting in front of somebody who is not the person who built it. */
    AWAITING_APPROVAL,

    /**
     * Released. The items are PENDING and the money is committed against them.
     *
     * <p>Not "sent": the transfers are with the bank or the mobile-money
     * provider from here, and each one succeeds or fails on its own. A batch is
     * how they were authorised, not a single thing that either worked or did not.
     */
    APPROVED,

    /**
     * Every item has reached an end — completed, failed, or cancelled.
     *
     * <p>Derived by counting the items rather than set by hand, so it cannot say
     * a run is finished while a transfer is still with a bank.
     */
    SETTLED,

    /** Abandoned before release. No money moved and the reason is recorded. */
    CANCELLED;

    public boolean isOpen() {
        return this == DRAFT || this == AWAITING_APPROVAL;
    }

    /** Whether money has been committed against this batch's items. */
    public boolean isReleased() {
        return this == APPROVED || this == SETTLED;
    }
}
