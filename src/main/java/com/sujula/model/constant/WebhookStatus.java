package com.sujula.model.constant;

public enum WebhookStatus {

    /** Stored, verified, not yet acted on. */
    RECEIVED,

    /** Acted on, and the outcome is on the row. */
    PROCESSED,

    /**
     * The same event arrived again.
     *
     * <p>Not an error. Providers retry by design, and a duplicate is answered
     * 200 so they stop — a 4xx would make them retry harder at the exact moment
     * the platform is telling them it already has it.
     */
    DUPLICATE,

    /**
     * The signature or the timestamp did not check out. Nothing was acted on.
     *
     * <p>Kept rather than discarded: a burst of these is somebody probing, and a
     * platform that threw them away cannot see that happening.
     */
    REJECTED,

    /** Acting on it failed, and it will be tried again. */
    RETRYING,

    /** Acting on it kept failing. A person has to look. */
    FAILED,

    /**
     * Understood, and deliberately nothing to do.
     *
     * <p>Providers send far more than any platform uses. Recording "we saw this
     * and it is not ours to act on" is what stops somebody later assuming a
     * missing effect was a bug.
     */
    IGNORED;

    public boolean isFinished() {
        return this == PROCESSED || this == DUPLICATE || this == REJECTED
                || this == FAILED || this == IGNORED;
    }
}
