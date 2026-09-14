package com.sujula.model.constant;

/**
 * What kind of actor an account is.
 *
 * <p>One role per account, which is a constraint rather than an oversight: a
 * person who both sells and buys does both under one account, because the
 * platform's rules are about what they are doing rather than what they are.
 * Where an account needs a second capability — a seller who also drives — that
 * is a second account, and the audit trail stays legible.
 */
public enum UserRole {

    CUSTOMER,
    VENDOR,
    DELIVERY,
    PICKUP_OPERATOR,

    /**
     * Somebody who works the queues and cannot decide anything.
     *
     * <p>The distinction that makes the admin surface safe to staff. Support
     * reads everything — orders, disputes, the custody chain, a user's profile —
     * and answers people. It cannot move money, change a status, suspend a
     * store, grant a role or impersonate anybody, because those are the actions
     * that cannot be undone by the next person to look.
     *
     * <p>Written as the separate role rather than as a permission on ADMIN so
     * that the default for a new member of staff is the safe one. A system where
     * the safe option requires remembering to remove something is a system where
     * everybody ends up an administrator.
     */
    SUPPORT,

    /** Everything. Deliberately few of these. */
    ADMIN;

    /** Whether this account can reach the administrative surface at all. */
    public boolean isStaff() {
        return this == ADMIN || this == SUPPORT;
    }

    /**
     * Whether this account may make a decision rather than only read one.
     *
     * <p>The single test behind every write on {@code /admin}. Support can see
     * why a parcel is stuck; only an administrator can move it.
     */
    public boolean canDecide() {
        return this == ADMIN;
    }
}
