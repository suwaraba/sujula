package com.sujula.model.platform;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.sujula.model.constant.JobRunStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One pass of a background job.
 *
 * <p>A row per run rather than a "last status" column on something, because the
 * question an operator asks is never "is it working now" — it is "when did it
 * stop". A job that has failed silently every night for a week and succeeded
 * this morning looks healthy under a last-status field and looks like a problem
 * under a list of runs.
 *
 * <p>Runs that did nothing are recorded too. A queue drainer finding nothing to
 * drain is the normal case and the useful one: a gap in these rows means the
 * scheduler stopped, which is a different and worse problem than a job failing.
 */
@Entity
@Table(name = "job_runs",
       indexes = {
           @Index(name = "idx_jobrun_name",    columnList = "jobName, startedAt"),
           @Index(name = "idx_jobrun_status",  columnList = "status")
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JobRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String jobName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private JobRunStatus status = JobRunStatus.RUNNING;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    /** How many things it handled. Zero is a legitimate and common answer. */
    @Column(nullable = false)
    @Builder.Default
    private int itemsProcessed = 0;

    @Column(length = 2000)
    private String failureReason;

    /**
     * Set when a person ran it rather than the clock.
     *
     * <p>Worth distinguishing: a manual run at three in the afternoon in the
     * middle of an incident is context, and a list where it is indistinguishable
     * from the scheduled two o'clock pass loses it.
     */
    private Long triggeredByUserId;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    /** How long it took, in milliseconds, or null while it is still going. */
    public Long durationMs() {
        if (startedAt == null || finishedAt == null) return null;
        return java.time.Duration.between(startedAt, finishedAt).toMillis();
    }
}
