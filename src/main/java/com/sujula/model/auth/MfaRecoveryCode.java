package com.sujula.model.auth;

import com.sujula.model.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A single-use code that gets a user past multi-factor authentication when they
 * no longer have the authenticator — a lost phone is the ordinary case, and
 * without these it locks the account permanently.
 *
 * <p>Stored as a BCrypt hash and marked used rather than deleted, so "this code
 * was already spent" is distinguishable from "this code was never issued".
 */
@Entity
@Table(name = "mfa_recovery_codes",
       indexes = @Index(name = "idx_recovery_user", columnList = "user_id"))
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MfaRecoveryCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "code_hash", nullable = false, length = 72)
    private String codeHash;

    private LocalDateTime usedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public boolean isSpent() {
        return usedAt != null;
    }
}
