package com.sujula.model.constant;

/** How the money reaches the platform, which decides who is allowed to confirm a payment. */
public enum PaymentChannel {

    /** Gateway-hosted checkout; confirmed by the provider callback. */
    ONLINE,

    /** Buyer-initiated bank transfer; confirmed by an admin who matches the reference. */
    OFFLINE_TRANSFER,

    /** Cash or card taken by a person at handover; confirmed by that person. */
    IN_PERSON
}
