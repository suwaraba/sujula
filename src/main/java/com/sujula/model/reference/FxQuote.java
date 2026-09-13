package com.sujula.model.reference;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sujula.model.user.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A rate, held still long enough for someone to pay at it.
 *
 * <p>Between seeing a total and completing a card form a buyer spends a minute
 * or two, and an indicative rate can move in that time. Without a held quote the
 * platform has two choices, both bad: recompute at capture, so the amount
 * charged differs from the amount agreed; or charge the old figure at the new
 * rate and absorb the difference silently on every order. A quote makes the
 * commitment explicit and bounded — this rate, this pair, until this moment.
 *
 * <p><strong>The id is the credential.</strong> Quotes are created before anyone
 * signs in, because prices are shown before anyone signs in. A guest holds theirs
 * by possessing an unguessable id; a quote created while signed in is bound to
 * that account as well. This is the same arrangement as a delivery context, and
 * deliberately so — two different answers to "who may read this" would be one
 * more thing to get wrong.
 */
@Entity
@Table(name = "fx_quotes",
       indexes = {
           @Index(name = "idx_fx_quote_user",    columnList = "user_id"),
           @Index(name = "idx_fx_quote_expires", columnList = "expiresAt")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FxQuote {

    /** Random, not sequential: the next shopper's quote must not be one increment away. */
    @Id
    @Column(length = 64, updatable = false)
    private String id;

    /** The signed-in holder, when there is one. Null for a guest. */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /** What is being converted from. */
    @Column(nullable = false, length = 3)
    private String baseCurrency;

    /** What is being converted to — the currency the buyer is charged in. */
    @Column(nullable = false, length = 3)
    private String quoteCurrency;

    /**
     * Units of {@link #quoteCurrency} per one unit of {@link #baseCurrency}.
     *
     * <p>Eight decimal places, because a rate is not money. Rounding a rate to
     * the currency's own scale before multiplying would throw away most of its
     * precision — XOF has none at all, which would round every rate to a whole
     * number — and the error compounds across every line of an order.
     */
    @Column(nullable = false, precision = 18, scale = 8)
    private BigDecimal rate;

    /**
     * The amount this quote was requested for, when one was named.
     *
     * <p>Optional. A client asking "what is 4500 GMD in sterling" gets the
     * converted figure back and can show it; one asking only for the pair gets
     * the rate. Kept so the quote records what was actually promised.
     */
    @Column(precision = 18, scale = 4)
    private BigDecimal baseAmount;

    @Column(precision = 18, scale = 4)
    private BigDecimal quoteAmount;

    /** When the underlying indicative rate was published. */
    private LocalDateTime rateFetchedAt;

    @Column(updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    /**
     * When this quote was used to place an order, if it was.
     *
     * <p>Recorded rather than deleted. A quote is the evidence of what rate was
     * promised, and an order priced against one should still be able to point at
     * it when somebody asks months later why they were charged what they were.
     */
    private LocalDateTime consumedAt;

    public boolean isExpired() {
        return expiresAt == null || expiresAt.isBefore(LocalDateTime.now());
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }

    /** Live: still inside its window and not yet spent. */
    public boolean isUsable() {
        return !isExpired() && !isConsumed();
    }

    public boolean isAnonymous() {
        return user == null;
    }

    public boolean belongsTo(Long userId) {
        return user != null && user.getId() != null && user.getId().equals(userId);
    }
}
