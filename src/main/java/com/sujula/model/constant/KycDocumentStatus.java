package com.sujula.model.constant;

/** Where one submitted document has got to. */
public enum KycDocumentStatus {

    /** Uploaded, waiting to be looked at. */
    SUBMITTED,

    ACCEPTED,

    /**
     * Rejected, with a reason the applicant can act on.
     *
     * <p>A rejection without a reason is the commonest way an onboarding funnel
     * dies: the applicant re-uploads the same blurry photograph because nobody
     * told them it was blurry. The reason is not optional on this status.
     */
    REJECTED;

    public boolean isDecided() {
        return this != SUBMITTED;
    }
}
