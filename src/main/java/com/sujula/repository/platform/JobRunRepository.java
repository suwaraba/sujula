package com.sujula.repository.platform;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sujula.model.constant.JobRunStatus;
import com.sujula.model.platform.JobRun;

public interface JobRunRepository extends JpaRepository<JobRun, Long> {

    Optional<JobRun> findTopByJobNameOrderByStartedAtDesc(String jobName);

    /** The last time this job actually finished cleanly, whenever that was. */
    Optional<JobRun> findTopByJobNameAndStatusOrderByStartedAtDesc(String jobName,
                                                                  JobRunStatus status);

    @Query("SELECT r FROM JobRun r "
         + "WHERE (:jobName IS NULL OR r.jobName = :jobName) "
         + "AND (:status IS NULL OR r.status = :status) "
         + "ORDER BY r.startedAt DESC, r.id DESC")
    Page<JobRun> search(@Param("jobName") String jobName,
                        @Param("status") JobRunStatus status,
                        Pageable pageable);

    @Query("SELECT COUNT(r) FROM JobRun r WHERE r.jobName = :jobName "
         + "AND r.status = com.sujula.model.constant.JobRunStatus.FAILED "
         + "AND r.startedAt >= :since")
    long countFailuresSince(@Param("jobName") String jobName,
                            @Param("since") LocalDateTime since);

    /**
     * Rows still claiming to be running long after any pass should have ended.
     *
     * <p>A process killed mid-pass gets no chance to record its own death, and a
     * row that says RUNNING for three weeks is a job that looks busy. Whatever
     * notices marks them ABANDONED.
     */
    @Query("SELECT r FROM JobRun r WHERE r.status = "
         + "com.sujula.model.constant.JobRunStatus.RUNNING AND r.startedAt < :before")
    List<JobRun> findStale(@Param("before") LocalDateTime before);

    /** Housekeeping: these rows are numerous and only the recent ones are read. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM JobRun r WHERE r.startedAt < :before")
    int deleteOlderThan(@Param("before") LocalDateTime before);
}
