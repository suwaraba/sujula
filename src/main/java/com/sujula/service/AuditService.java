package com.sujula.service;

import com.sujula.model.AuditLog;
import com.sujula.model.constant.AuditAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * The record of who did what to whom.
 *
 * <p>Recording runs inside the caller's transaction, unlike notifications: if
 * an action cannot be written to the log, the action itself must not stand.
 * That is the difference between an audit trail and a best-effort side channel
 * — a blocked seller or a refunded order with no entry explaining it is worse
 * than the operation failing and being retried.
 *
 * <p>The actor is taken from the security context, so callers record what
 * happened rather than restating who they are.
 */
public interface AuditService {

    /**
     * Records one administrative decision.
     *
     * @param targetType what was acted on: USER, VENDOR, PAYMENT, ORDER
     * @param targetLabel who or what it was, in words — outlives the row itself
     * @param summary one line, as it should read in an admin's list
     * @param details the reason given, or before/after values; may be null
     */
    AuditLog record(AuditAction action, String targetType, Long targetId,
                    String targetLabel, String summary, String details);

    /** Same, without a reason to record. */
    AuditLog record(AuditAction action, String targetType, Long targetId,
                    String targetLabel, String summary);

    /**
     * Records an action the platform took on its own, with no admin behind it.
     * Used by the startup bootstrap, which runs before anyone has signed in.
     */
    AuditLog recordSystemAction(AuditAction action, String targetType, Long targetId,
                                String targetLabel, String summary, String details);

    Page<AuditLog> findAll(Pageable pageable);

    Page<AuditLog> findByActor(Long actorUserId, Pageable pageable);

    Page<AuditLog> findByAction(AuditAction action, Pageable pageable);

    /** Everything ever done to one account, vendor or payment. */
    Page<AuditLog> findByTarget(String targetType, Long targetId, Pageable pageable);
}
