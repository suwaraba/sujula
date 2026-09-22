package com.sujula.model.constant;

public enum PartnerStatus {

    /**
     * Registered, but nothing has been verified yet.
     *
     * <p>The first state of a new store. It exists as a state of its own rather
     * than as a flag on PENDING because the two are waiting on different people:
     * this is waiting on the applicant to send their documents, and PENDING is
     * waiting on an administrator to read them. Collapsed into one status, a
     * queue of applications to review is full of rows nobody has submitted.
     */
    PENDING_KYC,

    PENDING,
    APPROVED,
    SUSPENDED,
    REJECTED, ACTIVE;

    /**
     * Whether a partner in this state may trade — list products, take orders, be
     * paid.
     *
     * <p>The rule lives here because three places need it: order placement,
     * product creation and the vendor service's own gate. Kept as three separate
     * copies they agree today and have no reason to keep agreeing.
     */
    public boolean canTrade() {
        return this == APPROVED || this == ACTIVE;
    }
}
