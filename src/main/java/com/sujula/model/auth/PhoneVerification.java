package com.sujula.model.auth;

import com.sujula.model.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * An outstanding phone-number challenge.
 *
 * <p>Phone matters more than email in this market: it is how a courier reaches a
 * buyer and how mobile money identifies a vendor, so the number a user claims
 * has to be proved rather than typed.
 *
 * <p>The code is hashed, attempts are counted, and the row carries the phone it
 * was issued for — a user who requests a code for one number and then edits their
 * profile to another must not be able to confirm the first code against the
 * second.
 */
@Entity
@Table(name = "phone_verifications",
       indexes = {
           @Index(name = "idx_phoneverify_user", columnList = "user_id"),
           @Index(name = "idx_phoneverify_exp",  columnList = "expires_at")
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PhoneVerification {

    /** A code is burned after this many wrong guesses, not merely rate limited. */
    public static final int MAX_ATTEMPTS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** The number this challenge proves, in E.164. */
    @Column(nullable = false, length = 30)
    private String phone;

    @Column(name = "code_hash", nullable = false, length = 72)
    private String codeHash;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    @Builder.Default
    private int attempts = 0;

    private LocalDateTime confirmedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public boolean isUsable() {
        return confirmedAt == null
                && attempts < MAX_ATTEMPTS
                && expiresAt != null && expiresAt.isAfter(LocalDateTime.now());
    }
}
