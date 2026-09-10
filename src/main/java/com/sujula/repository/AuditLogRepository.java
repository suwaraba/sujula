package com.sujula.repository;

import com.sujula.model.AuditLog;
import com.sujula.model.constant.AuditAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
