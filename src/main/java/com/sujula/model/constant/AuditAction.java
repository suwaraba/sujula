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

    // ── The administrative surface ───────────────────────────────────────────
    //
    // Every one of these is a decision a person made that another person may
    // have to answer for. They are separate values rather than one ADMIN_ACTION
    // with a description, because "what have we suspended people for this
    // quarter" has to be a query rather than a reading exercise.

    USER_CREATED_BY_ADMIN,
    USER_PROFILE_EDITED_BY_ADMIN,
    USER_ROLE_GRANTED,
    USER_ROLE_REVOKED,
    USER_SESSIONS_ENDED_BY_ADMIN,
    USER_MFA_RESET,

    /**
     * An administrator opened a session as somebody else.
     *
     * <p>The most invasive thing this surface can do — it reads a person's
     * messages, their addresses, their orders — so it is its own action, always
     * recorded, and carries the reason that was given at the time. The
     * difference between support work and snooping is this row.
     */
    USER_IMPERSONATED,
    USER_IMPERSONATION_ENDED,

    SANCTION_ISSUED,
    SANCTION_LIFTED,

    MODERATION_CASE_RAISED,
    MODERATION_CASE_ASSIGNED,
    MODERATION_CASE_RESOLVED,

    STORE_APPROVED,
    STORE_REJECTED,
    STORE_SUSPENDED,
    STORE_COMMISSION_CHANGED,
    KYC_APPROVED,
    KYC_REJECTED,

    PRODUCT_APPROVED,
    PRODUCT_REJECTED,
    PRODUCT_SUSPENDED,
    PRODUCT_CREATED_BY_ADMIN,
    PRODUCT_EDITED_BY_ADMIN,
    REVIEW_PUBLISHED,
    REVIEW_REJECTED,

    ORDER_CANCELLED_BY_ADMIN,
    ORDER_PLACED_BY_ADMIN,

    /**
     * A vendor order's status was set by hand rather than earned.
     *
     * <p>Break-glass, and named so it reads as one in a log. Every other status
     * on this platform is a consequence of something that happened; this is the
     * escape hatch for when the thing that happened cannot be recorded, and it
     * should be rare enough that a month with twenty of them is a question.
     */
    VENDOR_ORDER_STATUS_FORCED,

    SHIPMENT_ASSIGNED,
    SHIPMENT_UNASSIGNED,
    SHIPMENT_REASSIGNED,
    SHIPMENT_CANCELLED_BY_ADMIN,

    /**
     * A handover was recorded without the code that normally proves it.
     *
     * <p>The C4 escape hatch, and the single most sensitive action here: it is
     * the one way a parcel reaches DELIVERED without anybody having presented
     * anything. Step-up, a mandatory note, and its own audit action — so that
     * "how often does this happen, and to whose parcels" is answerable.
     */
    SHIPMENT_HANDOFF_OVERRIDDEN,

    DRIVER_APPROVED,
    DRIVER_SUSPENDED,
    DRIVER_ZONES_CHANGED,
    PICKUP_POINT_CREATED_BY_ADMIN,
    PICKUP_POINT_EDITED,
    PICKUP_POINT_SUSPENDED,
    ZONE_CREATED,
    ZONE_EDITED,
    RATE_CARD_CHANGED,

    PAYOUT_BATCH_CREATED,
    PAYOUT_BATCH_APPROVED,
    PAYOUT_BATCH_CANCELLED,
    PAYOUT_ITEM_RETRIED,
    FX_REFRESHED,
    FX_SPREAD_CHANGED,
    REPORT_EXPORTED,

    DISPUTE_ASSIGNED,
    DISPUTE_RESOLVED,
    DISPUTE_CALLBACK_REQUESTED,

    ANNOUNCEMENT_SENT,
    NOTIFICATION_SENT_BY_ADMIN,
    FEATURE_FLAG_CHANGED,
    JOB_TRIGGERED,
    /** An existing account raised to administrator by the bootstrap. */
    ADMIN_PROMOTED
}
