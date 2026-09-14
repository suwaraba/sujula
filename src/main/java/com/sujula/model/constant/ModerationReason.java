package com.sujula.model.constant;

/**
 * What a case is about.
 *
 * <p>Fixed codes rather than free text, because the reason decides the sanction
 * and because "what are we suspending people for this month" is a question the
 * platform has to be able to answer without reading two thousand notes.
 */
public enum ModerationReason {

    /** Selling something that may not be sold here. */
    PROHIBITED_ITEM,

    /** A listing that describes something other than what arrives. */
    MISLEADING_LISTING,

    /** Somebody else's photographs, brand or text. */
    INTELLECTUAL_PROPERTY,

    /** Reviews or orders arranged to move a rating. */
    RATING_MANIPULATION,

    /**
     * Persuading buyers to pay outside the platform.
     *
     * <p>The one this market actually sees most, and the reason the message
     * filter exists. A buyer who pays by mobile money outside an escrowed order
     * has no delivery record and no way to be refunded — and on this route they
     * are usually on another continent from the goods.
     */
    OFF_PLATFORM_PAYMENT,

    /** Abuse, threats, or harassment of another user. */
    ABUSIVE_CONDUCT,

    /** Taking money for goods that were never sent. */
    NON_DELIVERY,

    /** Somebody else's identity, or documents that are not theirs. */
    IDENTITY_FRAUD,

    /** Anything else, which a note has to explain. */
    OTHER;

    /**
     * Whether a first offence normally ends an account rather than pausing it.
     *
     * <p>Advisory: it shapes what the queue suggests, and an administrator
     * decides. Encoding it here rather than in somebody's head is what makes two
     * administrators reach the same answer on the same facts.
     */
    public boolean isSevere() {
        return this == IDENTITY_FRAUD || this == NON_DELIVERY || this == PROHIBITED_ITEM;
    }

    /** What kind of thing a case of this sort is usually raised against. */
    public String usualSubject() {
        return switch (this) {
            case PROHIBITED_ITEM, MISLEADING_LISTING, INTELLECTUAL_PROPERTY -> "PRODUCT";
            case RATING_MANIPULATION -> "REVIEW";
            case OFF_PLATFORM_PAYMENT, ABUSIVE_CONDUCT, IDENTITY_FRAUD -> "USER";
            case NON_DELIVERY -> "VENDOR";
            case OTHER -> "USER";
        };
    }
}
