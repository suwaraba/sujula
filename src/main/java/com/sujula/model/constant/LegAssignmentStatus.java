package com.sujula.model.constant;

/** Where a driver's offer of one leg has got to. */
public enum LegAssignmentStatus {

    /** Nobody has been offered it yet. */
    UNASSIGNED,

    /** Offered to a driver, who has until a deadline to answer. */
    OFFERED,

    /** The driver took it. */
    ACCEPTED,

    /** The driver said no, with a reason. */
    DECLINED,

    /** Nobody answered in time and it went back into the pool. */
    EXPIRED,

    /** The driver has the parcel. */
    IN_PROGRESS,

    /** Done. */
    COMPLETED,

    /** Taken off this driver, by them or by support. */
    CANCELLED;

    /** Whether this leg is still somebody's to do. */
    public boolean isLive() {
        return this == OFFERED || this == ACCEPTED || this == IN_PROGRESS;
    }

    /** Whether a driver may still answer an offer in this state. */
    public boolean isAnswerable() {
        return this == OFFERED;
    }
}
