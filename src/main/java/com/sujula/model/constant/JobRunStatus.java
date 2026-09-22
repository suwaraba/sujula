package com.sujula.model.constant;

public enum JobRunStatus {

    RUNNING,

    /** It finished. It may have found nothing to do, which is not a failure. */
    SUCCEEDED,

    FAILED,

    /**
     * It was still marked RUNNING long after any run should have finished.
     *
     * <p>Written by whatever notices, rather than left as a RUNNING row from
     * three weeks ago. A process killed mid-pass leaves no chance to record its
     * own death, and a row that says RUNNING forever is a job that looks busy.
     */
    ABANDONED;

    public boolean isFinished() {
        return this != RUNNING;
    }
}
