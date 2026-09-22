package com.sujula.model.constant;

/**
 * The store's overall verification standing, derived from its documents rather
 * than stored beside them.
 *
 * <p>Derived on purpose. A stored summary and the rows it summarises drift the
 * first time a document is decided through a path that forgets to update it, and
 * the drift is invisible until a store that has passed is told it has not.
 */
public enum KycStatus {

    /** Nothing has been submitted. */
    NOT_STARTED,

    /** Some of what is required is missing. */
    INCOMPLETE,

    /** Everything required is in and waiting on a reviewer. */
    IN_REVIEW,

    /** At least one document came back rejected, and the applicant must act. */
    ACTION_REQUIRED,

    /** Everything required has been accepted. */
    VERIFIED
}
