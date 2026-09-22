package com.sujula.repository.admin;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sujula.model.admin.ModerationCase;
import com.sujula.model.constant.ModerationCaseStatus;
import com.sujula.model.constant.ModerationReason;

@Repository
public interface ModerationCaseRepository extends JpaRepository<ModerationCase, Long> {

    boolean existsByReference(String reference);

    Optional<ModerationCase> findByReference(String reference);

    /**
     * The queue, filtered and oldest-deadline first.
     *
     * <p>Sorted by {@code dueBy} rather than by age, because the two are
     * different questions and only one of them is about a promise. A case raised
     * yesterday with a four-hour deadline is more urgent than one raised last
     * week with a fortnight's.
     */
    @Query("SELECT c FROM ModerationCase c WHERE "
         + "(:status IS NULL OR c.status = :status) "
         + "AND (:reason IS NULL OR c.reason = :reason) "
         + "AND (:assigneeId IS NULL OR c.assignedTo.id = :assigneeId) "
         + "ORDER BY c.dueBy ASC NULLS LAST, c.createdAt ASC")
    Page<ModerationCase> findQueue(@Param("status") ModerationCaseStatus status,
                                   @Param("reason") ModerationReason reason,
                                   @Param("assigneeId") Long assigneeId,
                                   Pageable pageable);

    /**
     * Open cases against one account.
     *
     * <p>What makes "this seller has three open cases" a fact rather than
     * something somebody happens to remember, and what an administrator looks at
     * before deciding whether a fourth is a pattern.
     */
    @Query("SELECT c FROM ModerationCase c WHERE c.accountable.id = :userId "
         + "AND c.status IN (com.sujula.model.constant.ModerationCaseStatus.OPEN, "
         + "                 com.sujula.model.constant.ModerationCaseStatus.IN_REVIEW) "
         + "ORDER BY c.createdAt ASC")
    List<ModerationCase> findOpenAgainst(@Param("userId") Long userId);

    /** An existing open case about the same thing, so a second report joins it. */
    @Query("SELECT c FROM ModerationCase c WHERE c.subjectType = :subjectType "
         + "AND c.subjectId = :subjectId AND c.status IN ("
         + "  com.sujula.model.constant.ModerationCaseStatus.OPEN, "
         + "  com.sujula.model.constant.ModerationCaseStatus.IN_REVIEW) "
         + "ORDER BY c.createdAt ASC LIMIT 1")
    Optional<ModerationCase> findOpenAbout(@Param("subjectType") String subjectType,
                                           @Param("subjectId") Long subjectId);

    @Query("SELECT COUNT(c) FROM ModerationCase c WHERE c.status IN ("
         + "  com.sujula.model.constant.ModerationCaseStatus.OPEN, "
         + "  com.sujula.model.constant.ModerationCaseStatus.IN_REVIEW) "
         + "AND c.dueBy IS NOT NULL AND c.dueBy < :now")
    long countOverdue(@Param("now") LocalDateTime now);

    long countByStatus(ModerationCaseStatus status);
}
