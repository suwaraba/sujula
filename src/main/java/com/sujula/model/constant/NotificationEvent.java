package com.sujula.model.constant;

/**
 * What happened, as something a person can hold a preference about.
 *
 * <p>Grouped by who cares. A buyer wants to know where their parcel is; a seller
 * wants to know they have been paid; a driver wants to know a job is waiting.
 * Giving everybody one list of everything is how somebody turns the whole thing
 * off to stop being told about promotions and then misses a delivery.
 */
public enum NotificationEvent {

    // ── The buyer's order ────────────────────────────────────────────────────

    /** Paid for, and on its way to the sellers. */
    ORDER_PLACED,

    /** Something moved on an order, where nothing more specific fits. */
    ORDER_UPDATE,

    /** A seller could not fulfil their part of it. */
    ORDER_CANCELLED,

    /** Money on its way back. */
    REFUND_ISSUED,

    // ── The parcel ───────────────────────────────────────────────────────────

    /** A driver has it. */
    PARCEL_COLLECTED,

    /** It is out for delivery today. */
    PARCEL_OUT_FOR_DELIVERY,

    /** Nobody was there. */
    PARCEL_ATTEMPT_FAILED,

    /** It is sitting at a counter, waiting to be collected. */
    PARCEL_AT_PICKUP_POINT,

    /**
     * The code that opens a parcel, or that lets the recipient redirect it.
     *
     * <p>Mandatory. This one is not a notification about something — it is the
     * thing itself, and a preference that could suppress it would leave a parcel
     * nobody can collect.
     */
    PARCEL_CODE,

    /** Handed over. */
    PARCEL_DELIVERED,

    // ── After the sale ───────────────────────────────────────────────────────

    /** Something moved on a return. */
    RETURN_UPDATE,

    /**
     * Something moved on a dispute.
     *
     * <p>Mandatory. Money is held still while one is open, and somebody who
     * could switch off the only warning that their balance has been frozen would
     * find out from the balance.
     */
    DISPUTE_UPDATE,

    /** Somebody wrote to you. */
    MESSAGE_RECEIVED,

    /** A seller answered your review. */
    REVIEW_REPLY,

    // ── The seller's money ───────────────────────────────────────────────────

    /** A sale landed. */
    SALE_MADE,

    /** Something needs packing. */
    ORDER_TO_FULFIL,

    /** Money sent. */
    PAYOUT_SENT,

    /**
     * A transfer that did not work.
     *
     * <p>Mandatory. A seller whose bank rejected a payout has to act, and this
     * is the only place they would learn to.
     */
    PAYOUT_FAILED,

    /** Stock running out. */
    LOW_STOCK,

    // ── The driver and the counter ───────────────────────────────────────────

    /** A job is being offered. */
    DELIVERY_OFFERED,

    /** A parcel has arrived at your counter, or is on its way to it. */
    PICKUP_PARCEL_ARRIVED,

    /** Something on your shelf is past its storage window. */
    PICKUP_PARCEL_OVERDUE,

    // ── The account ──────────────────────────────────────────────────────────

    /**
     * Somebody signed in from somewhere new, or a password changed.
     *
     * <p>Mandatory, for the reason every platform makes it so: the message
     * warning you that somebody else has your account is not one the person who
     * has your account should be able to turn off.
     */
    SECURITY_ALERT,

    /** Verification, approval, a document that needs attention. */
    ACCOUNT_UPDATE,

    // ── Everything else ──────────────────────────────────────────────────────

    /** Offers and campaigns. The one most people switch off, and rightly. */
    PROMOTION,

    /** Anything that does not fit above. */
    GENERAL;

    /**
     * Whether somebody may switch this off entirely.
     *
     * <p>Five may not, and each of them is a case where silence costs the person
     * something they cannot get back: a parcel they cannot collect, a freeze they
     * did not know about, a payout that bounced, or somebody else in their
     * account. Everything else is theirs to choose.
     *
     * <p>Note the shape of this: it is a property of the event rather than a
     * rule somewhere in the dispatcher. A rule in the dispatcher is one a second
     * dispatcher would not have.
     */
    public boolean isMandatory() {
        return this == PARCEL_CODE || this == DISPUTE_UPDATE || this == PAYOUT_FAILED
                || this == SECURITY_ALERT || this == REFUND_ISSUED;
    }

    /**
     * Whether this is on by default on a channel the user has said nothing about.
     *
     * <p>Promotions are the only thing that starts off. Everything else starts on
     * and can be turned off, because the alternative — everything off until
     * somebody goes looking for a settings page — means the first parcel arrives
     * with no warning at all.
     */
    public boolean isOnByDefault(NotificationChannel channel) {
        if (isMandatory()) {
            return true;
        }
        if (this == PROMOTION) {
            // Not even in the inbox. An inbox full of offers is one nobody reads,
            // and the things in it that matter are the ones that get missed.
            return false;
        }
        // Push is off until there is a device, at which point registering one is
        // itself the opt-in. Email for everything would be an inbox nobody wants.
        return switch (channel) {
            case IN_APP -> true;
            case EMAIL -> isWorthAnEmail();
            case PUSH -> true;
        };
    }

    /**
     * Whether this is worth interrupting somebody's inbox for by default.
     *
     * <p>The test is whether they would have to do something about it. "Your
     * parcel was collected" is nice to see in the app and is not worth an email;
     * "nobody was there and we are trying again on Thursday" needs an answer.
     */
    private boolean isWorthAnEmail() {
        return switch (this) {
            case ORDER_PLACED, ORDER_CANCELLED, PARCEL_ATTEMPT_FAILED, PARCEL_AT_PICKUP_POINT,
                 PARCEL_DELIVERED, RETURN_UPDATE, MESSAGE_RECEIVED, SALE_MADE, ORDER_TO_FULFIL,
                 PAYOUT_SENT, DELIVERY_OFFERED, PICKUP_PARCEL_ARRIVED, PICKUP_PARCEL_OVERDUE,
                 ACCOUNT_UPDATE -> true;
            default -> false;
        };
    }

    /** Which part of the settings page this belongs under. */
    public String group() {
        return switch (this) {
            case ORDER_PLACED, ORDER_UPDATE, ORDER_CANCELLED, REFUND_ISSUED -> "Orders";
            case PARCEL_COLLECTED, PARCEL_OUT_FOR_DELIVERY, PARCEL_ATTEMPT_FAILED,
                 PARCEL_AT_PICKUP_POINT, PARCEL_CODE, PARCEL_DELIVERED -> "Deliveries";
            case RETURN_UPDATE, DISPUTE_UPDATE, MESSAGE_RECEIVED, REVIEW_REPLY -> "After the sale";
            case SALE_MADE, ORDER_TO_FULFIL, PAYOUT_SENT, PAYOUT_FAILED, LOW_STOCK -> "Selling";
            case DELIVERY_OFFERED, PICKUP_PARCEL_ARRIVED, PICKUP_PARCEL_OVERDUE -> "Delivering";
            case SECURITY_ALERT, ACCOUNT_UPDATE -> "Account";
            case PROMOTION, GENERAL -> "Everything else";
        };
    }
}
