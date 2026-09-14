package com.sujula.model.constant;

/**
 * What the dispute is about.
 *
 * <p>Narrower than a return's reasons on purpose. A return is "I want to send
 * this back"; a dispute is "the other side and I cannot agree", and the list is
 * short because the cases that get this far are few and each needs different
 * evidence.
 */
public enum DisputeReason {

    /** The parcel never came, whatever the tracking says. */
    NOT_RECEIVED,

    /** It came, and it was not what was paid for. */
    NOT_AS_DESCRIBED,

    /** It came broken. */
    DAMAGED,

    /** A return the seller would not accept or would not settle. */
    RETURN_REFUSED,

    /** Money that was agreed and did not arrive. */
    REFUND_NOT_RECEIVED,

    /** Somebody was charged for something they did not order. */
    UNAUTHORISED_CHARGE;

    /**
     * Whether the custody chain is the first place to look.
     *
     * <p>For these, the answer is usually already recorded: who handed the
     * parcel to whom, where, with what code and what photograph. A dispute whose
     * facts are in the chain should be read before anybody is asked to argue.
     */
    public boolean answeredByTheCustodyChain() {
        return this == NOT_RECEIVED || this == DAMAGED;
    }
}
