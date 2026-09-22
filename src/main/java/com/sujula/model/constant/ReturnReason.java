package com.sujula.model.constant;

/**
 * Why the buyer wants to send it back.
 *
 * <p>A fixed list rather than free text, because the reason decides who pays the
 * carriage. A phone that arrived cracked is the seller's cost; a phone somebody
 * simply did not want is the buyer's, and a system that could not tell them
 * apart would have to guess — and would guess against whoever complained less.
 */
public enum ReturnReason {

    /** It arrived broken. */
    DAMAGED_IN_TRANSIT,

    /** It was broken before it was sent, or stopped working immediately. */
    FAULTY,

    /** Not what the listing said it was. */
    NOT_AS_DESCRIBED,

    /** A different product altogether. */
    WRONG_ITEM,

    /** The box was short. */
    MISSING_PARTS,

    /** Nothing wrong with it; they changed their mind. */
    NO_LONGER_WANTED,

    /** It never turned up, which is a return with nothing to return. */
    NEVER_ARRIVED;

    /**
     * Whether the seller carries the cost of getting it back.
     *
     * <p>Everything but a change of mind. A buyer who is charged carriage for a
     * seller's mistake learns not to complain, and a marketplace where nobody
     * complains is not one where nothing is wrong.
     */
    public boolean sellerPaysCarriage() {
        return this != NO_LONGER_WANTED;
    }

    /**
     * Whether there is anything to send back at all.
     *
     * <p>A parcel that never arrived cannot be returned, so the flow skips
     * straight past the carriage and the counting. Treating it like any other
     * return would leave a buyer waiting to be asked to post something they
     * never received.
     */
    public boolean hasGoodsToReturn() {
        return this != NEVER_ARRIVED;
    }
}
