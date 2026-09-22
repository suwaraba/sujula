package com.sujula.model.constant;

/** Where a bulk job has got to. */
public enum CatalogueJobStatus {

    QUEUED,
    RUNNING,

    /**
     * Finished, and every row went in.
     */
    COMPLETED,

    /**
     * Finished, and some rows did not.
     *
     * <p>Its own status rather than a count on COMPLETED, because the two need
     * different words in front of a seller: one says "your 400 products are in",
     * the other says "380 are in and these 20 need your attention". A single
     * status would make the second read like the first.
     */
    COMPLETED_WITH_ERRORS,

    /** The file could not be read at all, so no row was attempted. */
    FAILED
}
