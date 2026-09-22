package com.sujula.model.products;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sujula.model.user.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A question a shopper asked about a product, and the seller's answer.
 *
 * <p>Worth more here than on most marketplaces. A buyer in Madrid cannot pick
 * the phone up, and the person who will actually receive it is not in the
 * conversation at all — so "does this charger have a UK plug" is not idle
 * curiosity, it is the only way to find out. The answers stay on the listing
 * because the next diaspora buyer has the same question.
 *
 * <p><strong>Published is a consequence, not an input.</strong> Questions are
 * public text attached to someone else's shopfront, which is a standing
 * invitation to abuse — contact details, defamation, a competitor's link. A row
 * is visible only once somebody approved it, and the default of a new row is
 * not-approved.
 */
@Entity
@Table(name = "product_questions",
       indexes = {
           @Index(name = "idx_question_product",   columnList = "product_id"),
           @Index(name = "idx_question_moderation", columnList = "approvedAt"),
           @Index(name = "idx_question_asker",     columnList = "asked_by_user_id")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /**
     * Who asked.
     *
     * <p>Required — questions are authenticated, because anonymous public text on
     * a seller's page is a spam channel with no cost to the sender.
     */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asked_by_user_id", nullable = false)
    private User askedBy;

    @Column(nullable = false, length = 500)
    private String question;

    @Column(length = 2000)
    private String answer;

    /**
     * Who answered. The vendor usually; an admin where support stepped in.
     *
     * <p>Recorded so a shopper can tell "the seller says" from "the platform
     * says", which are different weights of assurance.
     */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "answered_by_user_id")
    private User answeredBy;

    private LocalDateTime answeredAt;

    /**
     * When a moderator approved this for display. Null means not visible.
     *
     * <p>A timestamp rather than a boolean, because "approved" and "when, by
     * whom" are the same question once anybody disputes a takedown.
     */
    private LocalDateTime approvedAt;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by_user_id")
    private User approvedBy;

    /** Why it was refused, for the person who asked. */
    @Column(length = 300)
    private String rejectedReason;

    private LocalDateTime rejectedAt;

    @Column(updatable = false, nullable = false)
    private LocalDateTime createdAt;

    /** Visible to the public: approved, and not subsequently refused. */
    public boolean isPublished() {
        return approvedAt != null && rejectedAt == null;
    }

    public boolean isAnswered() {
        return answer != null && !answer.isBlank();
    }

    /** Still waiting on a moderator. */
    public boolean isPending() {
        return approvedAt == null && rejectedAt == null;
    }
}
