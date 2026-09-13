package com.sujula.model.constant;

/** Where a staff invitation has got to. */
public enum StoreStaffStatus {

    /**
     * Invited by email, not yet accepted — and possibly not yet a user at all.
     *
     * <p>The person invited may have no account here. That is the ordinary case
     * rather than an edge one: a seller adds their cousin who has never used the
     * platform, and the invitation has to survive until that cousin registers.
     */
    INVITED,

    /** Accepted, and the permissions below are live. */
    ACTIVE,

    /**
     * Removed. Kept rather than deleted, so an audit of who could see what, and
     * when, has something to read.
     */
    REVOKED;

    public boolean isLive() {
        return this == ACTIVE;
    }
}
