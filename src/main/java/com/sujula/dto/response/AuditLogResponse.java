package com.sujula.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sujula.model.AuditLog;
import com.sujula.model.constant.AuditAction;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** One line of the audit trail, as an admin reads it. */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuditLogResponse {

    private Long id;
    private AuditAction action;

    /** Null when the platform acted on its own, with no admin behind it. */
    private Long actorId;
    private String actorEmail;
    private String actorName;

    private String targetType;
    private Long targetId;
    private String targetLabel;

    private String summary;
    private String details;
    private String ipAddress;

    private LocalDateTime createdAt;

    public static AuditLogResponse from(AuditLog entry) {
        return AuditLogResponse.builder()
                .id(entry.getId())
                .action(entry.getAction())
                // The snapshot, not the relation: an actor's row may be gone.
                .actorId(entry.getActor() != null ? entry.getActor().getId() : null)
                .actorEmail(entry.getActorEmail())
                .actorName(entry.getActorName())
                .targetType(entry.getTargetType())
                .targetId(entry.getTargetId())
                .targetLabel(entry.getTargetLabel())
                .summary(entry.getSummary())
                .details(entry.getDetails())
                .ipAddress(entry.getIpAddress())
                .createdAt(entry.getCreatedAt())
                .build();
    }
}
