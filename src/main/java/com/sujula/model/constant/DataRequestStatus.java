package com.sujula.model.constant;

/** Progress of a data-protection request. */
public enum DataRequestStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED;

    /** Still going to be worked on. A second request while one is open is the same request. */
    public boolean isOpen() {
        return this == PENDING || this == PROCESSING;
    }
}
