package com.sujula.model.constant;

/** Where a policy case has got to. */
public enum ModerationCaseStatus {

    /** Raised, and nobody has picked it up. */
    OPEN,

    /** Somebody has it. */
    IN_REVIEW,

    /** Decided, with an outcome and — where one was warranted — a sanction. */
    RESOLVED,

    /**
     * Looked at, and nothing was wrong.
     *
     * <p>Its own status rather than a resolution with an empty outcome, because
     * the two say different things about the person reported. Somebody cleared
     * six times is being reported by somebody rather than doing something.
     */
    DISMISSED;

    public boolean isOpen() {
        return this == OPEN || this == IN_REVIEW;
    }
}
