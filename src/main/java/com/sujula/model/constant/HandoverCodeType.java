package com.sujula.model.constant;


public enum HandoverCodeType {
    VENDOR_TO_DRIVER,       // Vendor → Driver pickup
    VENDOR_TO_PICKUP,       // Vendor → Pickup point deposit
    DRIVER_TO_PICKUP,       // Driver → Pickup point (return / hub transfer)
    PICKUP_TO_DRIVER,       // Pickup point → Driver (last-mile assignment)
    PICKUP_TO_CUSTOMER,     // Pickup point → Customer self-collection
    DRIVER_TO_CUSTOMER      // Driver → Customer door delivery
}
