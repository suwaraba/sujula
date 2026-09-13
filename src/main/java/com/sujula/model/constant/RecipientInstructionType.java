package com.sujula.model.constant;

/**
 * A standing instruction the recipient has given about her own parcel.
 *
 * <p>None of these moves the parcel — that is {@code CustodyChain}'s alone.
 * They change where it is going, when it is going there, or what the driver may
 * do if nobody answers the door. Each one is recorded with the proof that the
 * person giving it held the code, because the driver ends up acting on it.
 */
public enum RecipientInstructionType {

    /**
     * Send it to a counter instead of the door.
     *
     * <p>The ordinary answer when the recipient works and nobody is home in the
     * afternoon, and the reason the pickup network exists.
     */
    CHOOSE_PICKUP_POINT,

    /** Come on a different day. */
    RESCHEDULE,

    /**
     * Leave it with the neighbour, or behind the shop, without me.
     *
     * <p>The one instruction that changes what counts as proof of delivery: it
     * substitutes a recorded authorisation for the code the recipient would
     * otherwise read out. That is why it is a verified record with the exact
     * words in it rather than a flag, and why it names where.
     */
    AUTHORISE_SAFE_DROP;

    /** Whether a later instruction of this kind replaces an earlier one. */
    public boolean supersedesItself() {
        return true;
    }
}
