package com.sujula.model.admin;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sujula.model.constant.ModerationCaseStatus;
import com.sujula.model.constant.ModerationReason;
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
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One allegation that somebody broke a rule.
 *
 * <p>Every policy decision on the platform goes through one of these, whatever
 * raised it: a buyer reporting a review, a seller reporting a buyer, a support
 * agent noticing a pattern, or a rule that fires on its own. Having one queue
 * rather than four is what lets "this seller has three open cases" be a fact
 * rather than something somebody happens to remember.
 *
 * <p>The subject is a type and an id rather than a foreign key to each possible
 * table. That is deliberate and it is the one place in this schema where a
 * polymorphic reference earns itself: a product can be deleted, a review hidden
 * and a user erased, and the case has to survive all three — a case that
 * vanished with its subject would take the reason somebody was banned with it.
 */
@Entity
@Table(name = "moderation_cases",
       indexes = {
           @Index(name = "idx_case_status",  columnList = "status"),
           @Index(name = "idx_case_subject", columnList = "subject_type, subject_id"),
           @Index(name = "idx_case_vendor",  columnList = "vendor_id"),
           @Index(name = "idx_case_ref",     columnList = "reference", unique = true)
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ModerationCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Two agents picking the same case out of a queue is the ordinary race. */
    @Version
    private Long version;

    @Column(nullable = false, unique = true, length = 24)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ModerationCaseStatus status = ModerationCaseStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ModerationReason reason;

    // ── What it is about ─────────────────────────────────────────────────────

    /** USER, VENDOR, PRODUCT, REVIEW, ORDER. */
    @Column(nullable = false, length = 20)
    private String subjectType;

    @Column(nullable = false)
    private Long subjectId;

    /**
     * What the subject was called at the time.
     *
     * <p>Carried rather than joined, and it is the whole reason the case
     * survives its subject: "Samsung Galaxy A16, listed by Kombo Electronics"
     * still reads correctly after the listing is deleted, and a join would leave
     * a case about nothing.
     */
    @Column(length = 300)
    private String subjectLabel;

    /**
     * The account that answers for it.
     *
     * <p>Kept separately from the subject because a case about a listing is
     * really a case about the seller who put it up, and the queue is worked by
     * seller rather than by row.
     */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accountable_user_id")
    private User accountable;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id")
    private com.sujula.model.user.Vendor vendor;

    // ── Where it came from ───────────────────────────────────────────────────

    /**
     * Who raised it. Null where the platform did.
     *
     * <p>A rule that fires on its own has no reporter, and inventing one would
     * make an automated flag look like a complaint from a person.
     */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "raised_by_user_id")
    private User raisedBy;

    /** USER_REPORT, REVIEW_REPORT, DISPUTE, AUTOMATED, STAFF. */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String source = "STAFF";

    @Column(length = 2000)
    private String description;

    /**
     * Files, one URL per line.
     *
     * <p>Read only as a set, with this row, so a table would be three joins for
     * something that is never queried on its own.
     */
    @Column(columnDefinition = "TEXT")
    private String evidenceUrls;

    // ── Working it ───────────────────────────────────────────────────────────

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to_user_id")
    private User assignedTo;

    private LocalDateTime assignedAt;

    /**
     * When this should have been answered by.
     *
     * <p>Set when the case is raised and frozen. A deadline recomputed from
     * today's policy would move every time the policy did, and a queue sorted by
     * a moving deadline is one where the oldest case is never the most urgent.
     */
    private LocalDateTime dueBy;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by_user_id")
    private User resolvedBy;

    private LocalDateTime resolvedAt;

    /** UPHELD, DISMISSED, or what was decided in words. */
    @Column(length = 24)
    private String outcome;

    @Column(length = 2000)
    private String resolutionNote;

    /** The sanction this issued, where it issued one. */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sanction_id")
    private Sanction sanction;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /** Whether this case is past the day it should have been answered. */
    public boolean isOverdue(LocalDateTime now) {
        return status != null && status.isOpen() && dueBy != null && dueBy.isBefore(now);
    }
}
