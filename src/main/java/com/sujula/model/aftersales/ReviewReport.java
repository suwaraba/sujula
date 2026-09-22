package com.sujula.model.aftersales;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sujula.model.Review;
import com.sujula.model.constant.ReviewReportReason;
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
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Somebody said a review breaks the rules.
 *
 * <p><strong>A report hides nothing.</strong> That is the whole design. Sellers
 * report reviews they dislike, and a marketplace where a one-star review
 * disappears because the seller objected has no reviews worth reading — which
 * costs the honest sellers most, since their ratings stop meaning anything.
 * So this row queues a person to look, and the review stays up while they do.
 *
 * <p>One report per person per review, enforced in the table. Otherwise a seller
 * with two accounts reports twice and a naive "reports &gt; 1" rule hides an
 * honest review.
 */
@Entity
@Table(name = "review_reports",
       uniqueConstraints = @UniqueConstraint(name = "uq_review_report_once",
               columnNames = {"review_id", "reported_by_user_id"}),
       indexes = {
           @Index(name = "idx_review_report_review", columnList = "review_id"),
           @Index(name = "idx_review_report_open",   columnList = "reviewed_at")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReviewReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "review_id", nullable = false)
    private Review review;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reported_by_user_id", nullable = false)
    private User reportedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReviewReportReason reason;

    @Column(length = 1000)
    private String detail;

    /**
     * When a moderator looked, whatever they decided.
     *
     * <p>Null means the queue still has it. Set means somebody read it, and
     * whether the review came down is on the review rather than here — a report
     * records that a complaint was made, not its outcome.
     */
    private LocalDateTime reviewedAt;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by_user_id")
    private User reviewedBy;

    @Column(length = 500)
    private String moderatorNote;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
