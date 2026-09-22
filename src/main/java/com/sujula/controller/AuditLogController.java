package com.sujula.controller;

import com.sujula.dto.response.AuditLogResponse;
import com.sujula.dto.response.PagedResponse;
import com.sujula.model.constant.AuditAction;
import com.sujula.service.AuditService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The audit trail, newest first.
 *
 * <p>Read-only by design: there is no endpoint to edit or remove an entry, and
 * adding one would defeat the point of keeping it.
 */
@RestController
@RequestMapping("/api/admin/audit-logs")
@PreAuthorize("hasRole('ADMIN')")
public class AuditLogController {

    private final AuditService auditService;

    public AuditLogController(AuditService auditService) {
        this.auditService = auditService;
    }

    /** Everything, or one kind of action, or everything one admin has done. */
    @GetMapping
    public ResponseEntity<PagedResponse<AuditLogResponse>> find(
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) Long actorId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(page, size);

        if (actorId != null) {
            return ResponseEntity.ok(PagedResponse.of(
                    auditService.findByActor(actorId, pageable).map(AuditLogResponse::from)));
        }
        if (action != null) {
            return ResponseEntity.ok(PagedResponse.of(
                    auditService.findByAction(action, pageable).map(AuditLogResponse::from)));
        }
        return ResponseEntity.ok(PagedResponse.of(auditService.findAll(pageable).map(AuditLogResponse::from)));
    }

    /**
     * Everything ever done to one account, vendor or payment — the view a
     * support agent needs when a seller asks why they were suspended.
     */
    @GetMapping("/{targetType}/{targetId}")
    public ResponseEntity<PagedResponse<AuditLogResponse>> findForTarget(
            @PathVariable String targetType,
            @PathVariable Long targetId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PagedResponse.of(
                auditService.findByTarget(targetType.toUpperCase(), targetId, PageRequest.of(page, size))
                        .map(AuditLogResponse::from)));
    }
}
