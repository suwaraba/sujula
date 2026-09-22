package com.sujula.model.constant;

/**
 * Where a refund has got to.
 *
 * <p>A refund is not a button. Money leaving the platform is the one action that
 * cannot be undone by another API call, so it passes through a person: the buyer
 * asks, an administrator decides, and only then does anything move.
 */
public enum RefundRequestStatus {

    /** The buyer asked. Nothing has moved. */
    REQUESTED,

    /** An administrator agreed. The money is on its way back. */
    APPROVED,

    /** The money is back with the buyer. */
    COMPLETED,

    /** An administrator refused, with a reason the buyer can read. */
    DECLINED,

    /** The buyer changed their mind before it was decided. */
    WITHDRAWN;

    public boolean isOpen() {
        return this == REQUESTED || this == APPROVED;
    }

    public boolean isSettled() {
        return this == COMPLETED || this == DECLINED || this == WITHDRAWN;
    }
}
