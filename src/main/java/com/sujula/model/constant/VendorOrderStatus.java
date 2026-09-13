package com.sujula.model.constant;

import java.util.Set;

/**
 * Fulfilment status of one vendor's slice of a multivendor order.
 *
 * <p>Each slice moves on its own: one seller can be packing while another has
 * not looked at it, and the buyer's order status is derived from the slices
 * rather than the other way round.
 *
 * <p>The ladder is PENDING, PREPARING, READY_FOR_PICKUP, SHIPPED, DELIVERED.
 * READY_FOR_PICKUP earns its place: it is the state where the goods are packed
 * and waiting but have not left the shop, which is the moment the vendor release
 * code exists and the only window in which a driver can be sent without wasting
 * the journey.
 */
public enum VendorOrderStatus {

    /** Placed, and the seller has not yet said whether they will fulfil it. */
    PENDING,

    /** Accepted, and being picked and packed. */
    PREPARING,

    /**
     * Packed and waiting for a driver.
     *
     * <p>Nothing has left the shop. That matters twice over: a buyer cancelling
     * here costs the seller packing time but leaves no goods in transit, and a
     * driver dispatched here will not arrive to find nothing ready.
     */
    READY_FOR_PICKUP,

    /** Collected by a driver, with a release code presented. */
    SHIPPED,

    /** The buyer has it. Set when delivery is proven, never by the vendor. */
    DELIVERED,

    CANCELLED,

    REFUNDED;

    /** Nothing moves out of these. */
    public boolean isTerminal() {
        return this == DELIVERED || this == CANCELLED || this == REFUNDED;
    }

    /**
     * Whether the goods are still in the shop.
     *
     * <p>What decides whether a buyer may still cancel. READY_FOR_PICKUP counts:
     * the seller has done the packing, but nothing is on a road to another
     * country, and a cancellation there costs time rather than goods.
     */
    public boolean isPreDispatch() {
        return this == PENDING || this == PREPARING || this == READY_FOR_PICKUP;
    }

    /**
     * Where this status may go next.
     *
     * <p>Forward-only through the fulfilment sequence, with cancellation
     * available only while nothing has been collected. Once goods are with a
     * courier, "cancelled" means a refund, which is not the vendor's call.
     */
    public Set<VendorOrderStatus> allowedNext() {
        return switch (this) {
            case PENDING          -> Set.of(PREPARING, CANCELLED);
            case PREPARING        -> Set.of(READY_FOR_PICKUP, CANCELLED);
            case READY_FOR_PICKUP -> Set.of(SHIPPED, CANCELLED);
            case SHIPPED          -> Set.of(DELIVERED, REFUNDED);
            case DELIVERED        -> Set.of(REFUNDED);
            case CANCELLED, REFUNDED -> Set.of();
        };
    }

    public boolean canTransitionTo(VendorOrderStatus next) {
        return next != null && allowedNext().contains(next);
    }

    /**
     * True when a vendor may set this themselves.
     *
     * <p>A vendor says what they have done - accepted, packed, handed over. They
     * do not get to say the buyer received it, because delivery proves that, nor
     * that money went back, because an administrator decides that. Either would
     * let a seller close an order the buyer is still waiting on.
     */
    public boolean isVendorSettable() {
        return this == PREPARING || this == READY_FOR_PICKUP || this == CANCELLED;
    }
}
