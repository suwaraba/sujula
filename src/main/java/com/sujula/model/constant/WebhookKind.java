package com.sujula.model.constant;

/**
 * Which surface a webhook arrived on.
 *
 * <p>Separate from the provider, because one provider can serve two surfaces
 * and two providers can serve one. The kind decides which handler runs and what
 * the platform is willing to believe as a result.
 */
public enum WebhookKind {

    /**
     * Money. The highest-consequence of the three: a forged event here marks an
     * unpaid order paid, which ships goods nobody bought.
     */
    PSP,

    /**
     * Delivery receipts for messages the platform sent.
     *
     * <p>Covers email today — bounces, deliveries, complaints — and the SMS path
     * maps here too. This platform does not send SMS: the driver and the pickup
     * operator read their codes in the app, and the recipient's code reaches the
     * buyer by email for them to pass on, the way a remittance reference does.
     * If a deployment ever adds an SMS sender, its receipts land here with no
     * further work.
     */
    MESSAGING,

    /**
     * An outsourced identity check coming back with an answer.
     *
     * <p>Never trusted to APPROVE on its own. A provider saying somebody is who
     * they say they are moves a document to "checked"; a person still decides
     * whether the store opens, because the consequence of being wrong is a
     * driver's goods in a stranger's hands.
     */
    KYC;

    /** The path segment this kind is served at. */
    public String slug() {
        return switch (this) {
            case PSP -> "psp";
            case MESSAGING -> "messaging";
            case KYC -> "kyc";
        };
    }
}
