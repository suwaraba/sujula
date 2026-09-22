package com.sujula.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sujula.model.constant.AuditAction;
import com.sujula.model.user.User;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One administrative decision, recorded so it can be answered for later.
 *
 * <p>Append-only: nothing updates or deletes a row here. That is the whole
 * value of the table — an admin who blocks a seller, refunds an order or
 * promotes a colleague leaves a mark that neither they nor anyone else can
 * quietly tidy away.
 *
 * <p>The actor is kept twice: as a reference for joining, and as an email and
 * name snapshot for reading. The snapshot is what makes the log survive the
 * account being renamed or deleted — including by the very action being logged,
 * which is the case for a purge.
 */
@Entity
@Table(name = "audit_logs",
       indexes = {
           @Index(name = "idx_audit_actor",  columnList = "actor_user_id"),
           @Index(name = "idx_audit_action", columnList = "action"),
           @Index(name = "idx_audit_target", columnList = "targetType, targetId"),
           @Index(name = "idx_audit_time",   columnList = "createdAt")
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null when the platform itself acted — a startup bootstrap has no human behind it. */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id")
    private User actor;

    /** Who acted, frozen at the time. Survives a rename, a deletion, or a purge. */
    @Column(length = 150)
    private String actorEmail;

    @Column(length = 200)
    private String actorName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AuditAction action;

    /** What was acted on: USER, VENDOR, PAYMENT, ORDER. */
    @Column(length = 30)
    private String targetType;

    private Long targetId;

    /** Who or what the target was, in words — an email, a store name, an order number. */
    @Column(length = 200)
    private String targetLabel;

    /** What happened, in one line, as it should read in an admin's list. */
    @Column(nullable = false, length = 500)
    private String summary;

    /** The reason given, or the before/after values. Free text. */
    @Column(columnDefinition = "TEXT")
    private String details;

    /** Where the action came from. Null when there was no request behind it. */
    @Column(length = 60)
    private String ipAddress;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
