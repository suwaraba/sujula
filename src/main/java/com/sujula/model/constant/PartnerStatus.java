package com.sujula.model.constant;

public enum PartnerStatus {
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
