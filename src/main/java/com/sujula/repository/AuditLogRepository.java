package com.sujula.repository;

import com.sujula.model.AuditLog;
import com.sujula.model.constant.AuditAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/**
 * Reads over the audit trail. There is deliberately no update or delete path
 * beyond what {@code JpaRepository} inherits — an audit entry is written once.
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    Page<AuditLog> findByActorIdOrderByCreatedAtDesc(Long actorUserId, Pageable pageable);

    Page<AuditLog> findByActionOrderByCreatedAtDesc(AuditAction action, Pageable pageable);

    /** Everything ever done to one account, vendor or payment. */
    Page<AuditLog> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
            String targetType, Long targetId, Pageable pageable);

    Page<AuditLog> findByCreatedAtBetweenOrderByCreatedAtDesc(
            LocalDateTime from, LocalDateTime to, Pageable pageable);

    Page<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * The back office's audit search.
     *
     * <p>Every filter narrows and all of them are optional, because the question
     * is never the same twice: "what did this agent do on Tuesday", "who has
     * touched this seller", "who has been issuing refunds". One query rather
     * than five named ones, so a filter nobody anticipated does not need a
     * release.
     */
    @Query(value = "SELECT a FROM AuditLog a "
         + "WHERE (:actorId IS NULL OR a.actor.id = :actorId) "
         + "AND (:action IS NULL OR a.action = :action) "
         + "AND (:targetType IS NULL OR a.targetType = :targetType) "
         + "AND (:targetId IS NULL OR a.targetId = :targetId) "
         + "AND (:q IS NULL OR LOWER(a.summary) LIKE LOWER(CONCAT('%', CAST(:q AS String), '%')) "
         + "     OR LOWER(a.actorEmail) LIKE LOWER(CONCAT('%', CAST(:q AS String), '%')) "
         + "     OR LOWER(a.targetLabel) LIKE LOWER(CONCAT('%', CAST(:q AS String), '%'))) "
         + "AND (CAST(:from AS LocalDateTime) IS NULL OR a.createdAt >= :from) "
         + "AND (CAST(:to AS LocalDateTime) IS NULL OR a.createdAt < :to) "
         + "ORDER BY a.createdAt DESC, a.id DESC",
           countQuery = "SELECT COUNT(a) FROM AuditLog a "
         + "WHERE (:actorId IS NULL OR a.actor.id = :actorId) "
         + "AND (:action IS NULL OR a.action = :action) "
         + "AND (:targetType IS NULL OR a.targetType = :targetType) "
         + "AND (:targetId IS NULL OR a.targetId = :targetId) "
         + "AND (:q IS NULL OR LOWER(a.summary) LIKE LOWER(CONCAT('%', CAST(:q AS String), '%')) "
         + "     OR LOWER(a.actorEmail) LIKE LOWER(CONCAT('%', CAST(:q AS String), '%')) "
         + "     OR LOWER(a.targetLabel) LIKE LOWER(CONCAT('%', CAST(:q AS String), '%'))) "
         + "AND (CAST(:from AS LocalDateTime) IS NULL OR a.createdAt >= :from) "
         + "AND (CAST(:to AS LocalDateTime) IS NULL OR a.createdAt < :to)")
    Page<AuditLog> search(@Param("actorId") Long actorId,
                          @Param("action") AuditAction action,
                          @Param("targetType") String targetType,
                          @Param("targetId") Long targetId,
                          @Param("q") String q,
                          @Param("from") java.time.LocalDateTime from,
                          @Param("to") java.time.LocalDateTime to,
                          Pageable pageable);
}