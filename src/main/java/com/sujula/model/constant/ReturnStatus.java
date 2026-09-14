package com.sujula.model.constant;

/**
 * Where a return has got to.
 *
 * <p>A return is goods coming back. A refund is money going back. They are
 * different things on different timelines and this platform keeps them apart:
 * a parcel crossing Serrekunda takes days, and a buyer in Madrid watching a
 * single word change from "approved" to "refunded" with nothing in between has
 * no idea whether anybody has the goods.
 */
public enum ReturnStatus {

    /** The buyer asked. Nobody has answered. */
    REQUESTED,

    /** The seller agreed. The goods are to come back. */
    APPROVED,

    /** The seller said no, with a reason the buyer can read. */
    REJECTED,

    /**
     * The seller offered money instead of the goods coming back.
     *
     * <p>The ordinary outcome on this route and the reason it is a status of its
     * own. Sending a cracked screen protector from Serrekunda to Banjul costs
     * more than the protector, so "keep it and have half back" is the sensible
     * answer — but it is an offer, and an offer that is not accepted is not a
     * settlement.
     */
    OFFER_MADE,

    /** The buyer took the offer. What is owed is fixed at that moment. */
    OFFER_ACCEPTED,

    /** The seller has the goods back. */
    RECEIVED,

    /** The money has gone back to the buyer. */
    REFUNDED,

    /** Neither side would move, so somebody impartial has it. */
    ESCALATED,

    /** The buyer changed their mind before anybody decided. */
    WITHDRAWN;

    /** Whether this return is still somebody's to act on. */
    public boolean isOpen() {
        return this == REQUESTED || this == APPROVED || this == OFFER_MADE
                || this == OFFER_ACCEPTED || this == RECEIVED || this == ESCALATED;
    }

    public boolean isFinished() {
        return this == REFUNDED || this == REJECTED || this == WITHDRAWN;
    }

    /** Whether the seller is the one who has to do something next. */
    public boolean awaitsSeller() {
        return this == REQUESTED || this == OFFER_ACCEPTED || this == RECEIVED;
    }

    /** Whether the buyer is. */
    public boolean awaitsBuyer() {
        return this == APPROVED || this == OFFER_MADE;
    }
}
