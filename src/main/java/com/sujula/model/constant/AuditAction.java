package com.sujula.model.constant;

/**
 * Administrative actions worth answering "who did this, and when?" for.
 *
 * <p>Only decisions a person makes about someone else's account, money or
 * standing on the platform belong here. Ordinary business events have their own
 * trails — an order's status history, a payment's own record — and duplicating
 * them into the audit log would bury the handful of entries that matter.
 */
public enum AuditAction {

    // ── Accounts ──────────────────────────────────────────────────────────────
    USER_BLOCKED,
    USER_UNBLOCKED,
    USER_FRAUD_FLAGGED,
    USER_FRAUD_CLEARED,
    USER_ENABLED,
    USER_DISABLED,
    USER_UNLOCKED,
    /** Soft delete — the account is disabled but its history is kept. */
    USER_DELETED,
    /** Hard delete — the row is gone. The audit entry is what survives it. */
    USER_PURGED,

    // ── Partners ──────────────────────────────────────────────────────────────
    VENDOR_STATUS_CHANGED,
    /** A store was opened and is waiting on its documents. */
    VENDOR_STORE_CREATED,
    /** Identity or business documents were submitted, or resubmitted. */
    VENDOR_KYC_SUBMITTED,
    /**
     * Where a store's money goes was changed.
     *
     * <p>The most valuable thing an attacker inside a seller's account can
     * change, and the one people ask about afterwards: when did it move, and
     * from which session. Recorded with four digits of the new destination and
     * never the number.
     */
    VENDOR_PAYOUT_DESTINATION_CHANGED,
    /** Somebody was given, or refused, access to a store that is not theirs. */
    VENDOR_STAFF_CHANGED,

    // ── Money ─────────────────────────────────────────────────────────────────
    PAYMENT_TRANSFER_CONFIRMED,
    PAYMENT_COLLECTED_IN_PERSON,
    PAYMENT_REFUNDED,
    PAYMENT_CANCELLED,
    PAYMENT_MARKED_FAILED,

    // ── Platform ──────────────────────────────────────────────────────────────
    /** The first administrator, created from configuration at startup. */
    ADMIN_BOOTSTRAPPED,
    /** An existing account raised to administrator by the bootstrap. */
    ADMIN_PROMOTED
}
