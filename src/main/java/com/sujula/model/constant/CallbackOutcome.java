package com.sujula.model.constant;

/**
 * What came of ringing somebody.
 *
 * <p>"No answer" and "spoke to them" are not the same record, and a platform
 * that collapsed them could not tell a supervisor which callbacks are still
 * owed. Three of these mean somebody still has to try again.
 */
public enum CallbackOutcome {

    /** Spoke to them, and the case moved. */
    SPOKE,

    /** Rang out. */
    NO_ANSWER,

    /** The number does not reach them. */
    UNREACHABLE,

    /** They asked to be called back later. */
    RESCHEDULED,

    /** They declined to discuss it. */
    DECLINED;

    /** Whether somebody still owes this person a call. */
    public boolean needsAnotherTry() {
        return this == NO_ANSWER || this == RESCHEDULED;
    }
}
