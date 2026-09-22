package com.sujula.model.auth;

import com.sujula.model.constant.DataRequestStatus;
import com.sujula.model.constant.DataRequestType;
import com.sujula.model.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A data-protection request: give me my data, or erase me.
 *
 * <p>A row rather than an immediate action, for two reasons. Both operations can
 * take longer than a request should, so they run as jobs. And both need to be
 * answerable afterwards — a regulator asking when an erasure was requested and
 * when it completed is a question the platform has to be able to answer, which a
 * fire-and-forget delete cannot.
 *
 * <p>The idempotency the API promises is this table's job: asking twice while
 * one is open returns the open one rather than queueing a second.
 */
@Entity
@Table(name = "account_data_requests",
       indexes = {
           @Index(name = "idx_datareq_user",   columnList = "user_id"),
           @Index(name = "idx_datareq_status", columnList = "status")
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountDataRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Quoted back to the user so support can find one request out of thousands. */
    @Column(nullable = false, unique = true, length = 40)
    private String reference;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DataRequestType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private DataRequestStatus status = DataRequestStatus.PENDING;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    /**
     * Where an export can be collected. Time-limited: an export is a complete
     * copy of someone's life on the platform and must not sit on a permanent URL.
     */
    @Column(length = 1000)
    private String downloadUrl;

    private LocalDateTime downloadExpiresAt;

    @Column(length = 500)
    private String failureReason;
}
