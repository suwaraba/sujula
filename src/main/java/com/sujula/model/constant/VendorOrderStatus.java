package com.sujula.model.constant;

/** Fulfilment status of one vendor's slice of a multivendor order. */
public enum VendorOrderStatus {
    PENDING,
    CONFIRMED,
    PROCESSING,
    SHIPPED,
    DELIVERED,
    CANCELLED,
    REFUNDED
}
