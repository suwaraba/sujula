package com.sujula.model.constant;

/** What a data-protection request asks the platform to do. */
public enum DataRequestType {

    /** Give the user a copy of everything held about them. */
    EXPORT,

    /**
     * Erase the user.
     *
     * <p>Pseudonymisation rather than deletion: orders, payments and payouts are
     * financial records the platform is required to keep, so the rows stay and
     * the personal data in them is overwritten.
     */
    ERASURE
}
