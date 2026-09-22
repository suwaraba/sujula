package com.sujula.service.impl;

import com.sujula.model.AuditLog;
import com.sujula.model.constant.AuditAction;
import com.sujula.model.user.User;
import com.sujula.repository.AuditLogRepository;
import com.sujula.service.AuditService;
import com.sujula.service.GeoService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Writes the audit trail, taking the actor and the calling address from the
 * request rather than from the caller.
 *
 * <p>Every write joins the caller's transaction on purpose — see
 * {@link AuditService}. Reads are the admin-facing side and are paged
 * newest-first, which is the only order a human ever wants a log in.
 */
@Service
public class AuditServiceImpl implements AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditServiceImpl.class);

    private final AuditLogRepository auditLogRepository;
    private final GeoService geoService;

    public AuditServiceImpl(AuditLogRepository auditLogRepository, GeoService geoService) {
        this.auditLogRepository = auditLogRepository;
        this.geoService = geoService;
    }

    @Override
    @Transactional
    public AuditLog record(AuditAction action, String targetType, Long targetId,
                           String targetLabel, String summary, String details) {
        User actor = currentActor();
        AuditLog entry = auditLogRepository.save(AuditLog.builder()
                .actor(actor)
                .actorEmail(actor != null ? actor.getEmail() : null)
                .actorName(actor != null ? actor.getFullName() : null)
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .targetLabel(targetLabel)
                .summary(summary)
                .details(details)
                .ipAddress(callerAddress())
                .build());

        log.info("[Audit] {} by {} on {}#{} — {}", action,
                entry.getActorEmail() != null ? entry.getActorEmail() : "system",
                targetType, targetId, summary);
        return entry;
    }

    @Override
    @Transactional
    public AuditLog record(AuditAction action, String targetType, Long targetId,
                           String targetLabel, String summary) {
        return record(action, targetType, targetId, targetLabel, summary, null);
    }

    @Override
    @Transactional
    public AuditLog recordSystemAction(AuditAction action, String targetType, Long targetId,
                                       String targetLabel, String summary, String details) {
        return auditLogRepository.save(AuditLog.builder()
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .targetLabel(targetLabel)
                .summary(summary)
                .details(details)
                .build());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditLog> findAll(Pageable pageable) {
        return auditLogRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditLog> findByActor(Long actorUserId, Pageable pageable) {
        return auditLogRepository.findByActorIdOrderByCreatedAtDesc(actorUserId, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditLog> findByAction(AuditAction action, Pageable pageable) {
        return auditLogRepository.findByActionOrderByCreatedAtDesc(action, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditLog> findByTarget(String targetType, Long targetId, Pageable pageable) {
        return auditLogRepository.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(targetType, targetId, pageable);
    }

    /** The signed-in admin, or null when the platform acted on its own. */
    private User currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof User user) {
            return user;
        }
        return null;
    }

    /**
     * The address the action came from. Best-effort: the log is worth writing
     * even when the request cannot be resolved, so a failure here never costs
     * the entry.
     */
    private String callerAddress() {
        try {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
                HttpServletRequest request = attributes.getRequest();
                String clientIp = geoService.getClientIp(request);
                return clientIp != null ? clientIp : request.getRemoteAddr();
            }
        } catch (RuntimeException ex) {
            log.debug("[Audit] Could not resolve the caller address: {}", ex.getMessage());
        }
        return null;
    }
}
