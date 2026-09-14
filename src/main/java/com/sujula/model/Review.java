package com.sujula.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sujula.model.products.Product;
import com.sujula.model.user.User;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "reviews",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "product_id"}))
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private Integer rating;  // 1-5

    @Column(length = 150)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String comment;

    @Column(nullable = false)
    @Builder.Default
    private boolean verified = false;  // verified purchase

    /**
     * The seller's answer. One per review, and the service enforces it.
     *
     * <p>A column rather than a thread on purpose. A seller who could reply
     * repeatedly would argue under a one-star review until the argument was
     * longer than the review, and the person deciding whether to buy would be
     * reading a quarrel rather than an account of a phone.
     */
    @Column(columnDefinition = "TEXT")
    private String vendorReply;

    private LocalDateTime vendorRepliedAt;

    /**
     * When the author last changed it.
     *
     * <p>Shown beside the review. A rating that went from five stars to one
     * after the seller stopped answering is a different thing from one that was
     * always one star, and both are worth knowing — so an edit is dated rather
     * than silent.
     */
    private LocalDateTime editedAt;

    /**
     * How many times it has been edited.
     *
     * <p>Not a limit, a record. Somebody rewriting a review nine times is not
     * doing what reviews are for, and a moderator reading it should be able to
     * see that without a history table.
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer editCount = 0;

    /**
     * Gone, without the row going.
     *
     * <p>Soft on purpose. A review is attached to an order, a product's average
     * rating and — once a seller has answered it — somebody else's words. Hard
     * deletion would take the reply with it and leave the rating arithmetic
     * describing a review nobody can read.
     */
    private LocalDateTime deletedAt;

    /**
     * How many people have reported it.
     *
     * <p>Recounted from {@code review_reports} rather than incremented, for the
     * same reason every other summary in this system is: a nudged counter
     * drifts, and what this one drifts into is a queue that shows the wrong
     * reviews to a moderator.
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer reportCount = 0;

    /**
     * Taken down by a moderator, with the reason on the report.
     *
     * <p>Distinct from {@link #deletedAt}, which is the author changing their
     * mind. A seller looking at a review that vanished deserves to know which of
     * the two happened, and so does anybody auditing moderation.
     */
    private LocalDateTime hiddenAt;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    /** Whether this review is still shown to anybody. */
    public boolean isVisible() {
        return deletedAt == null && hiddenAt == null;
    }
}
