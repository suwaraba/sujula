package com.sujula.model.constant;

/**
 * What happened to a vendor's money.
 *
 * <p>The reason is the point of a ledger. A seller looking at a balance they
 * disagree with is not asking what the number is — they can read it — they are
 * asking which of these moved it, and when.
 *
 * <p>Every entry is signed: positive is money owed to the vendor, negative is
 * money taken back or paid out. So a balance is a sum of the rows and nothing
 * else, and a row nobody wrote cannot have moved it.
 */
public enum LedgerEntryType {

    /** Goods sold. The gross, before the platform's share. */
    SALE,

    /** The platform's share of a sale. Always negative. */
    COMMISSION,

    /**
     * Money given back to a buyer, taken off this vendor's side.
     *
     * <p>Posted when an administrator approves a refund, never when one is
     * requested. A request is a question; this is the answer.
     */
    REFUND,

    /**
     * The commission on refunded goods, handed back.
     *
     * <p>Separate from REFUND rather than netted into it, because a seller
     * checking a refund wants to see that they were not charged commission on a
     * sale that did not happen. Netting hides exactly the thing they are
     * looking for.
     */
    COMMISSION_REVERSAL,

    /** Money sent to the vendor. Always negative: it leaves the balance. */
    PAYOUT,

    /**
     * A payout that failed and came back.
     *
     * <p>Kept distinct from an adjustment. "The bank rejected it" and "support
     * corrected something" are different facts, and a seller whose money bounced
     * deserves to see which.
     */
    PAYOUT_REVERSAL,

    /** A correction somebody made by hand, with a reason attached. */
    ADJUSTMENT;

    /** Whether this type normally adds to what a vendor is owed. */
    public boolean isCredit() {
        return this == SALE || this == COMMISSION_REVERSAL || this == PAYOUT_REVERSAL;
    }

    /**
     * Whether an entry of this type may be written by a person rather than by
     * an event.
     *
     * <p>Only one may. Everything else is the consequence of an order, a refund
     * decision or a transfer, and a ledger whose rows can be typed in freely is
     * a ledger that proves nothing.
     */
    public boolean isManual() {
        return this == ADJUSTMENT;
    }
}
