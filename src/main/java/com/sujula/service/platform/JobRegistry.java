package com.sujula.service.platform;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.JobRunStatus;
import com.sujula.model.platform.JobRun;
import com.sujula.repository.platform.JobRunRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * What runs in the background, when it last ran, and whether it worked.
 *
 * <p>Every pass is a row, including the ones that found nothing to do. The
 * question an operator asks is never "is it working now" — it is "when did it
 * stop" — and a job that failed silently every night for a week and succeeded
 * this morning looks healthy under a last-status field. It looks like a problem
 * under a list of runs, which is the point.
 *
 * <p>A gap in the rows is itself the signal. A drainer finding an empty queue
 * still writes a row; no row at all means the scheduler stopped, which is worse
 * and different.
 */
@Slf4j
@Component
public class JobRegistry {

    /** After this, a RUNNING row is a process that died rather than a slow pass. */
    private static final Duration STALE_AFTER = Duration.ofHours(2);

    /** Run history older than this is deleted; only the recent rows are ever read. */
    private static final Duration KEEP_HISTORY = Duration.ofDays(30);

    private final JobRunRepository runs;
    private final ObjectProvider<ManagedJob> provider;

    /** Built on first use; the set cannot change after startup. */
    private volatile Map<String, ManagedJob> jobs;

    /**
     * Takes a provider rather than a {@code List<ManagedJob>} on purpose.
     *
     * <p>Every worker holds this registry so its scheduled pass leaves a row,
     * and this registry holds every worker so an operator can list and trigger
     * them. Asking for the list in the constructor makes that mutual, and the
     * application does not start. Resolving the jobs on first use breaks the
     * cycle in one place, which beats an {@code @Lazy} on each of five workers
     * that the sixth will forget.
     */
    public JobRegistry(JobRunRepository runs, ObjectProvider<ManagedJob> provider) {
        this.runs = runs;
        this.provider = provider;
    }

    private Map<String, ManagedJob> jobs() {
        Map<String, ManagedJob> known = jobs;
        if (known != null) {
            return known;
        }
        synchronized (this) {
            if (jobs != null) {
                return jobs;
            }
            Map<String, ManagedJob> built = new LinkedHashMap<>();
            // The interface is the registration. A worker that does not
            // implement it does not appear here, which beats a hand-maintained
            // list that drifts.
            provider.orderedStream()
                    .sorted(java.util.Comparator.comparing(ManagedJob::jobName))
                    .forEach(job -> built.put(job.jobName(), job));
            jobs = Map.copyOf(built);
            log.info("[Jobs] {} background job(s) registered: {}", jobs.size(), jobs.keySet());
            return jobs;
        }
    }

    public List<ManagedJob> all() {
        return List.copyOf(jobs().values());
    }

    public Optional<ManagedJob> find(String jobName) {
        return Optional.ofNullable(jobs().get(jobName));
    }

    public ManagedJob require(String jobName) {
        return find(jobName).orElseThrow(() -> new ResourceNotFoundException(
                "No background job called \"" + jobName + "\". The ones that exist are: "
                        + String.join(", ", jobs().keySet())));
    }

    /**
     * Runs a pass and records it, whatever happens.
     *
     * <p>The recording is the reason this wrapper exists. A worker that caught
     * its own exception and logged it leaves nothing an operator can query, and
     * a worker that did not catch it leaves a row saying RUNNING forever.
     *
     * @param triggeredByUserId set when a person ran it rather than the clock
     */
    public JobRun run(ManagedJob job, Long triggeredByUserId) {
        JobRun run = begin(job.jobName(), triggeredByUserId);
        try {
            int handled = job.runOnce();
            return finish(run.getId(), handled, null);
        } catch (RuntimeException e) {
            log.error("[Jobs] {} failed", job.jobName(), e);
            finish(run.getId(), 0, e.getMessage() == null ? e.toString() : e.getMessage());
            throw e;
        }
    }

    /**
     * Opens a run row on its own transaction.
     *
     * <p>REQUIRES_NEW so the row survives whatever the job itself does with its
     * transaction — including rolling it back, which is exactly the case where
     * somebody needs to see that a run happened.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public JobRun begin(String jobName, Long triggeredByUserId) {
        return runs.save(JobRun.builder()
                .jobName(jobName)
                .status(JobRunStatus.RUNNING)
                .startedAt(LocalDateTime.now())
                .triggeredByUserId(triggeredByUserId)
                .build());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public JobRun finish(Long runId, int handled, String failureReason) {
        JobRun run = runs.findById(runId).orElse(null);
        if (run == null) {
            return null;
        }
        run.setFinishedAt(LocalDateTime.now());
        run.setItemsProcessed(handled);
        run.setStatus(failureReason == null ? JobRunStatus.SUCCEEDED : JobRunStatus.FAILED);
        run.setFailureReason(failureReason == null ? null : truncate(failureReason, 2000));
        return runs.save(run);
    }

    /** Marks rows whose process died mid-pass, and prunes ancient history. */
    @Transactional
    public int sweep() {
        List<JobRun> stale = runs.findStale(LocalDateTime.now().minus(STALE_AFTER));
        for (JobRun run : stale) {
            run.setStatus(JobRunStatus.ABANDONED);
            run.setFinishedAt(LocalDateTime.now());
            run.setFailureReason("Still marked running "
                    + Duration.between(run.getStartedAt(), LocalDateTime.now()).toHours()
                    + " hours after it started. The process almost certainly died mid-pass.");
            runs.save(run);
        }
        int pruned = runs.deleteOlderThan(LocalDateTime.now().minus(KEEP_HISTORY));
        if (!stale.isEmpty() || pruned > 0) {
            log.info("[Jobs] Swept {} abandoned run(s), pruned {} old row(s)",
                    stale.size(), pruned);
        }
        return stale.size();
    }

    /** Every job with its last run, for the back office. */
    @Transactional(readOnly = true)
    public List<Described> describeAll() {
        List<Described> described = new ArrayList<>();
        LocalDateTime dayAgo = LocalDateTime.now().minusDays(1);
        for (ManagedJob job : jobs().values()) {
            JobRun last = runs.findTopByJobNameOrderByStartedAtDesc(job.jobName()).orElse(null);
            JobRun lastGood = runs.findTopByJobNameAndStatusOrderByStartedAtDesc(
                    job.jobName(), JobRunStatus.SUCCEEDED).orElse(null);
            described.add(new Described(job, last, lastGood,
                    runs.countFailuresSince(job.jobName(), dayAgo)));
        }
        return described;
    }

    public record Described(ManagedJob job, JobRun lastRun, JobRun lastSuccess,
                            long failuresInLastDay) {}

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
