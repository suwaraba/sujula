package com.sujula.model.order;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sujula.model.money.FxSnapshot;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One product on a held quote, with the price and the rate it was quoted at.
 *
 * <p>Per line rather than per vendor, because that is the granularity a dispute
 * lands on: a buyer questions the price of the phone, not the subtotal of the
 * seller's slice. Keeping the vendor's own figure beside the converted one means
 * "you were charged 54 euro because the phone is 4500 dalasi and the rate was
 * 0.012" is answerable from one row.
 *
 * <p>The delivery share is here too. Delivery is priced per product — each
 * travels its own distance carrying its own weight — so a line, not an order, is
 * where a leg belongs.
 */
@Entity
@Table(name = "cart_quote_lines",
       indexes = @Index(name = "idx_quote_line_quote", columnList = "quote_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CartQuoteLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quote_id", nullable = false)
    private CartQuote quote;

    @Column(nullable = false)
    private Long productId;

    private Long variantId;

    @Column(nullable = false)
    private Long vendorId;

    @Column(nullable = false)
    private int quantity;

    // ── The vendor's own figures ─────────────────────────────────────────────

    @Column(nullable = false, length = 3)
    private String listingCurrency;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPriceNative;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal lineTotalNative;

    // ── What the buyer sees ──────────────────────────────────────────────────

    @Column(precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(precision = 12, scale = 2)
    private BigDecimal lineTotal;

    /**
     * This line's share of delivery, in the buyer's currency.
     *
     * <p>Priced from the distance between this vendor and the destination and
     * the weight of these units — not apportioned from an order-level figure,
     * because two lines in one basket can leave from two different towns.
     */
    @Column(precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal deliveryCost = BigDecimal.ZERO;

    @Column(precision = 10, scale = 3)
    private BigDecimal distanceKm;

    @Column(precision = 10, scale = 3)
    private BigDecimal billableWeightKg;

    /**
     * Whether this product can reach the destination.
     *
     * <p>On the line, because on a multivendor cart the answer differs per line
     * and a shopper needs to know which item is the problem — not that "the cart"
     * cannot be delivered.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean deliverable = true;

    @Column(length = 200)
    private String issue;

    /** The rate this line's conversion used. C2: never re-read, always recorded. */
    @Embedded
    private FxSnapshot fx;
}
