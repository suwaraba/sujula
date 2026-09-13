package com.sujula.model.order;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sujula.model.constant.DeliveryMode;
import com.sujula.model.user.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A priced cart, held still long enough to be paid for.
 *
 * <p>This is the contract between "what the buyer agreed to" and "what they were
 * charged". Between seeing a total and completing a card form a shopper spends a
 * minute or two, and in that time three things move independently: the exchange
 * rate, the vendor's listed price, and the stock. A checkout that re-priced from
 * the live catalogue would present one figure and take another, which is the
 * single most corrosive thing a marketplace can do to a first-time buyer who has
 * just sent a month's income to a country they are not in.
 *
 * <p>So the quote freezes the whole breakdown — per line, per vendor, and the
 * rate each conversion used — and checkout prices from it rather than from the
 * catalogue. What moved in between is the platform's problem, not the buyer's,
 * for as long as the quote lives.
 *
 * <p><strong>It expires.</strong> Fifteen minutes is long enough to fill in a
 * card and short enough that the platform is not honouring a rate the market
 * left behind an hour ago.
 */
@Entity
@Table(name = "cart_quotes",
       indexes = {
           @Index(name = "idx_cart_quote_cart",    columnList = "cart_id"),
           @Index(name = "idx_cart_quote_expires", columnList = "expiresAt")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CartQuote {

    /** Opaque and unguessable: checkout is authorised by holding this. */
    @Id
    @Column(length = 64, updatable = false)
    private String id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    /** The signed-in buyer, when there is one. Null for a guest. */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /**
     * A fingerprint of the cart's contents when this was priced.
     *
     * <p>What makes the quote refuse to pay for a different basket. A shopper who
     * quotes, opens a second tab, adds a television and then checks out with the
     * first quote would otherwise buy the television at the old total.
     */
    @Column(nullable = false, length = 64)
    private String cartFingerprint;

    @Column(nullable = false, length = 3)
    private String displayCurrency;

    /** Where the goods are going, frozen — shipping was priced from it. */
    @Column(length = 64)
    private String deliveryContextId;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    @Builder.Default
    private DeliveryMode deliveryMode = DeliveryMode.HOME_DELIVERY;

    private Long pickupPointId;

    // ── The figures, all in displayCurrency ──────────────────────────────────

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

    @Column(nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal discount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal shipping = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal tax = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total;

    /**
     * Whether every figure could be converted.
     *
     * <p>False when a rate was missing for some vendor's currency. Such a quote
     * exists so a client can show what is wrong, and checkout refuses it: a total
     * that silently omitted one vendor's goods would undercharge and the platform
     * would owe the difference.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean complete = true;

    @OneToMany(mappedBy = "quote", cascade = CascadeType.ALL, orphanRemoval = true,
               fetch = FetchType.LAZY)
    @Builder.Default
    private List<CartQuoteLine> lines = new ArrayList<>();

    @Column(updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    /** The order this quote was spent on, if it was. */
    private Long consumedOrderId;

    private LocalDateTime consumedAt;

    public boolean isExpired() {
        return expiresAt == null || expiresAt.isBefore(LocalDateTime.now());
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }

    /** Payable: still inside its window, not yet spent, and fully priced. */
    public boolean isUsable() {
        return !isExpired() && !isConsumed() && complete;
    }

    public boolean isAnonymous() {
        return user == null;
    }

    public boolean belongsTo(Long userId) {
        return user != null && user.getId() != null && user.getId().equals(userId);
    }
}
