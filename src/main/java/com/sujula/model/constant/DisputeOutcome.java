package com.sujula.model.constant;

/**
 * How a dispute ended.
 *
 * <p>Recorded separately from the status because "resolved" is not an answer.
 * The seller waiting to be paid and the buyer waiting to be refunded are asking
 * different questions, and both are answered here.
 */
public enum DisputeOutcome {

    /** The buyer was right. The money goes back to them. */
    FOR_BUYER,

    /** The seller was right. The freeze lifts and they are paid. */
    FOR_VENDOR,

    /** Some of each, with the buyer's share named on the dispute. */
    SPLIT,

    /** Withdrawn or abandoned. Nothing moved either way. */
    NO_DECISION;

    /** Whether any money goes back to the buyer under this outcome. */
    public boolean refundsTheBuyer() {
        return this == FOR_BUYER || this == SPLIT;
    }
}
