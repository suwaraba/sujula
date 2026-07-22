package com.sujula.model.constant;

public enum PickupPointStatus {
    PENDING,    // Application submitted, awaiting admin review
    APPROVED,   // Active — can receive and dispatch packages
    REJECTED,   // Application declined
    SUSPENDED   // Temporarily disabled by admin
}
