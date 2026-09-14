package com.sujula.model.constant;

/**
 * What a finance export contains.
 *
 * <p>An enum rather than a free string in the path, because the path segment
 * decides which query runs and which rows leave the building. "Whatever the
 * client typed" is not an access-control decision.
 */
public enum ReportType {

    /** Every ledger row in the window, per vendor, per currency. */
    LEDGER,

    /** What the platform earned: commission, delivery margin, FX spread. */
    REVENUE,

    /** Transfers out, with their state and their failures. */
    PAYOUTS,

    /** What buyers paid, and what was refunded. */
    PAYMENTS,

    /**
     * Escrow against paid-out against held, side by side.
     *
     * <p>The one somebody runs when a bank statement does not agree with the
     * platform, which is the day this report justifies its existence.
     */
    RECONCILIATION;

    /** A short lower-case token for the URL. */
    public String slug() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
