package com.sujula.model.constant;

import java.util.Set;

/**
 * Fulfilment status of one vendor's slice of a multivendor order.
 *
 * <p>Each vendor's slice moves on its own: one seller can have shipped while
 * another has not started, and the buyer's order status is derived from the
 * slices rather than the other way round.
 */
public enum VendorOrderStatus {

    /** Placed, not yet acknowledged by the vendor. */
    PENDING,

    /** The vendor has accepted the order and will fulfil it. */
    CONFIRMED,

    /** Being picked and packed. */
    PROCESSING,

    /** Handed to the courier or ready for collection. */
    SHIPPED,

    /** The buyer has it. Set when delivery is confirmed, never by the vendor. */
    DELIVERED,

    CANCELLED,

    REFUNDED;

    /** Nothing moves out of these. */
    public boolean isTerminal() {
        return this == DELIVERED || this == CANCELLED || this == REFUNDED;
    }

    /**
     * Where this status may go next.
     *
     * <p>Forward-only through the fulfilment sequence, with cancellation
     * available only while nothing has been packed. Once goods are with a
     * courier, "cancelled" is a refund, which is not the vendor's call.
     */
    public Set<VendorOrderStatus> allowedNext() {
        return switch (this) {
            case PENDING    -> Set.of(CONFIRMED, CANCELLED);
            case CONFIRMED  -> Set.of(PROCESSING, CANCELLED);
            case PROCESSING -> Set.of(SHIPPED, CANCELLED);
            case SHIPPED    -> Set.of(DELIVERED, REFUNDED);
            case DELIVERED  -> Set.of(REFUNDED);
            case CANCELLED, REFUNDED -> Set.of();
        };
    }

    public boolean canTransitionTo(VendorOrderStatus next) {
        return next != null && allowedNext().contains(next);
    }

    /**
     * True when a vendor may set this themselves.
     *
     * <p>A vendor says what they have done — accepted, packed, handed over. They
     * do not get to say the buyer received it (delivery confirms that) or that
     * money went back (an admin does), because both would let a seller close an
     * order the buyer is still waiting on.
     */
    public boolean isVendorSettable() {
        return this == CONFIRMED || this == PROCESSING || this == SHIPPED || this == CANCELLED;
    }
}
