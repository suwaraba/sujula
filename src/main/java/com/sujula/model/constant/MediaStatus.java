package com.sujula.model.constant;

/** Whether an image is actually there yet. */
public enum MediaStatus {

    /**
     * A storage key has been claimed and nothing has been uploaded to it.
     *
     * <p>The row exists first so that ordering and limits are decided before
     * bytes move, and so an upload that never completes leaves something to
     * clean up rather than a gap nobody knows about.
     */
    PENDING,

    /** The file is in storage and the listing may show it. */
    READY,

    /** The upload never completed, or the file was rejected. */
    FAILED
}
