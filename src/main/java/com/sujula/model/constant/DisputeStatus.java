package com.sujula.model.constant;

/**
 * Where a dispute has got to.
 *
 * <p>Opening one freezes the seller's money on that sub-order and nothing else
 * (C3): one vendor's line being argued over must not hold up another vendor's
 * payout on the same payment.
 */
public enum DisputeStatus {

    /** Raised. Both sides can still add to it. */
    OPEN,

    /** Somebody from the platform is reading it. */
    UNDER_REVIEW,

    /** Decided. Which way is in the outcome. */
    RESOLVED,

    /** The person who raised it took it back. */
    WITHDRAWN;

    /**
     * Whether the money on this sub-order is frozen.
     *
     * <p>The whole reason this enum is read anywhere outside the disputes
     * surface. While this is true the seller's escrow does not release and their
     * available balance carries a hold.
     */
    public boolean freezesMoney() {
        return this == OPEN || this == UNDER_REVIEW;
    }

    public boolean isClosed() {
        return this == RESOLVED || this == WITHDRAWN;
    }
}
