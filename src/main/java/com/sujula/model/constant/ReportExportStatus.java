package com.sujula.model.constant;

public enum ReportExportStatus {

    /** Asked for. Nothing has been read yet. */
    QUEUED,

    BUILDING,

    /** Built, and downloadable until it expires. */
    READY,

    /** It did not build, and the reason is on the row. */
    FAILED,

    /**
     * It was built and the link has since lapsed.
     *
     * <p>A state rather than a deleted row: somebody asking "who exported every
     * vendor's earnings in March" must still get an answer, and that answer
     * cannot depend on the file still being there.
     */
    EXPIRED;

    public boolean isFinished() {
        return this == READY || this == FAILED || this == EXPIRED;
    }
}
