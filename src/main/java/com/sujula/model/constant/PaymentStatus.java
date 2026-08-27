package com.sujula.model.constant;

/**
 * Lifecycle of a single {@code Payment}, mirrored onto the order as its
 * payment flag.
 *
 * <p>{@code PENDING} covers everything not yet settled — a gateway checkout the
 * buyer has not completed, a transfer not yet matched, and cash not yet handed
 * over — so an unpaid order always reads the same way regardless of method.
 */
public enum PaymentStatus {

    /** Awaiting the money: gateway checkout open, transfer expected, or cash due at handover. */
    PENDING,

    /** Gateway authorised the amount but has not captured it yet. */
    AUTHORIZED,

    /** Money received in full. */
    PAID,

    /** The attempt failed; the buyer may retry with the same or another method. */
    FAILED,

    /** Abandoned before any money moved. */
    CANCELLED,

    /** Part of a settled payment was returned to the buyer. */
    PARTIALLY_REFUNDED,

    /** The whole amount was returned to the buyer. */
    REFUNDED;

    /** True once the full amount has been received and not (yet) returned. */
    public boolean isSettled() {
        return this == PAID;
    }

    /** True when no further money is expected or held — nothing left to collect or refund. */
    public boolean isClosed() {
        return this == CANCELLED || this == REFUNDED;
    }

    /** True while the order is still waiting for money. */
    public boolean isOutstanding() {
        return this == PENDING || this == AUTHORIZED || this == FAILED;
    }
}
