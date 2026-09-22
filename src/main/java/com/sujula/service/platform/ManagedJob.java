package com.sujula.service.platform;

/**
 * A background job an operator can see and can run by hand.
 *
 * <p>Implemented by every {@code @Scheduled} worker on the platform, which is
 * how {@code GET /admin/jobs} is a list of what actually runs rather than a
 * list somebody maintained. A worker that forgets to implement this does not
 * appear — so the interface is the registration, and there is no second place
 * to keep in step.
 */
public interface ManagedJob {

    /** A stable name. It appears in run history, so renaming one loses its past. */
    String jobName();

    /** What it does, in words an operator can act on at two in the morning. */
    String description();

    /**
     * Runs one pass now.
     *
     * @return how many things it handled. Zero is a legitimate answer and the
     *         common one — a queue drainer finding nothing to drain is healthy.
     */
    int runOnce();

    /**
     * How often the scheduler runs it, in milliseconds.
     *
     * <p>Reported so an operator can tell "has not run in an hour" from "runs
     * daily". Without it, every job's last-run time is uninterpretable.
     */
    long intervalMs();

    /** Whether it is switched on in this deployment at all. */
    default boolean isEnabled() {
        return true;
    }
}
