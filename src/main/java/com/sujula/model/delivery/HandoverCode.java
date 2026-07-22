package com.sujula.model.delivery;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One-time 6-digit OTP used to confirm a physical package handover.
 * A new code is generated for each leg of the delivery chain.
 *
 * Lifecycle: GENERATED → recipient provides code → USED (marked used=true).
 * Codes expire after a configurable TTL (default 24 h).
 */
@Entity
@Table(name = "handover_codes",
       indexes = {
           @Index(name = "idx_hc_delivery",  columnList = "delivery_id"),
           @Index(name = "idx_hc_code",      columnList = "code"),
           @Index(name = "idx_hc_type_used", columnList = "codeType, used")
       }
)
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HandoverCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Optimistic lock — prevents concurrent invalidation + creation races. */
    @Version
    private Long version;

    /** The delivery this code belongs to. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_id", nullable = false)
    private Delivery delivery;

    /** Which leg of the chain this code covers. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private com.sujula.model.constant.HandoverCodeType codeType;

    /** 6-digit numeric OTP (stored as String to preserve leading zeros). */
    @Column(nullable = false, length = 6)
    private String code;

    /** True once the recipient has presented and the system has validated the code. */
    @Column(nullable = false)
    @Builder.Default
    private boolean used = false;

    /** UserId of the party that consumed (verified) this code. */
    private Long usedByUserId;

    private LocalDateTime usedAt;

    /** Hard expiry — codes become invalid after this timestamp even if unused. */
    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
