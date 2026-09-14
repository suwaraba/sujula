package com.sujula.model.admin;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sujula.model.constant.ModerationReason;
import com.sujula.model.constant.SanctionType;
import com.sujula.model.user.User;

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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Something the platform did to somebody, and why.
 *
 * <p><strong>The sanction is the record; the state of the account is the
 * consequence.</strong> The same shape as the custody chain and the money
 * ledger, and here for the same reason: a user who is locked out will ask why,
 * and "somebody set a flag" is not an answer. Every lock has a row naming who
 * decided it, on what grounds, under which case, and until when.
 *
 * <p>Append-only. A sanction that turns out to have been wrong is <em>lifted</em>
 * — {@link #liftedAt} is stamped and the reason recorded — rather than deleted,
 * because "we suspended you for a week and then agreed we should not have" is a
 * different history from "nothing happened", and only one of them is true.
 *
 * <p>Expiry is a fact about the row rather than a job that has to run. A
 * suspension with {@code expiresAt} in the past is over whether or not anything
 * swept it, so an outage cannot leave somebody locked out for an extra week.
 */
@Entity
@Table(name = "sanctions",
       indexes = {
           @Index(name = "idx_sanction_user",    columnList = "user_id"),
           @Index(name = "idx_sanction_active",  columnList = "user_id, lifted_at, expires_at"),
           @Index(name = "idx_sanction_case",    columnList = "moderation_case_id")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Sanction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Who it was applied to. */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private SanctionType type;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ModerationReason reason;

    /**
     * What the person is told, in words they can read.
     *
     * <p>Required. A suspension whose reason nobody wrote down is one nobody can
     * defend, and the first person to need it is the administrator being asked
     * about it three months later by somebody who has lost their livelihood.
     */
    @Column(nullable = false, length = 1000)
    private String reasonText;

    /**
     * Which capability is restricted, for a FEATURE_RESTRICTION.
     *
     * <p>A {@code Permission} name. Null for the types that stop the whole
     * account, where naming one thing would be misleading.
     */
    @Column(length = 40)
    private String restrictedPermission;

    /** When it stops by itself. Null for a warning or a ban. */
    private LocalDateTime expiresAt;

    /** Set when somebody decided it should not stand. */
    private LocalDateTime liftedAt;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lifted_by_user_id")
    private User liftedBy;

    @Column(length = 1000)
    private String liftedReason;

    /** The case this came out of, where there was one. */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "moderation_case_id")
    private ModerationCase moderationCase;

    /**
     * Who decided.
     *
     * <p>Nullable only because the platform itself issues some of these — an
     * automatic restriction after repeated failures — and "nobody" is then the
     * honest answer rather than a made-up administrator.
     */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "issued_by_user_id")
    private User issuedBy;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    /**
     * Whether this is biting right now.
     *
     * <p>Computed rather than stored, so it cannot disagree with the dates. A
     * cached "active" column is one that survives its own expiry the first time
     * a sweep job fails.
     */
    public boolean isActiveAt(LocalDateTime when) {
        if (liftedAt != null) {
            return false;
        }
        return expiresAt == null || expiresAt.isAfter(when);
    }

    /** Whether this one, while active, stops the account working. */
    public boolean locksTheAccount() {
        return type != null && type.locksTheAccount();
    }
}
