package com.sujula.model.auth;

import com.sujula.model.constant.AuthProvider;
import com.sujula.model.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Links a Sujula account to an external identity.
 *
 * <p>Keyed on the provider's own immutable subject id, not on email. People
 * change the email on a Google account, and matching on email would either lose
 * the link or — far worse — hand one person's account to another who later
 * claimed the same address.
 */
@Entity
@Table(name = "oauth_accounts",
       uniqueConstraints = @UniqueConstraint(
               name = "uk_oauth_provider_subject",
               columnNames = {"provider", "provider_user_id"}),
       indexes = @Index(name = "idx_oauth_user", columnList = "user_id"))
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OAuthAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuthProvider provider;

    /** The provider's subject claim. Immutable for the life of their account. */
    @Column(name = "provider_user_id", nullable = false, length = 191)
    private String providerUserId;

    /** Email as the provider reported it at link time. A record, not an identifier. */
    @Column(length = 191)
    private String email;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime linkedAt;

    private LocalDateTime lastUsedAt;
}
