package com.sujula.model.inventory;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.sujula.model.constant.StockMovementReason;
import com.sujula.model.products.Product;
import com.sujula.model.products.ProductVariant;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;

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
 * One change to a stock figure, and why.
 *
 * <p>The same shape as the custody chain, for the same reason. A stock count
 * that can be set directly is a count nobody can explain: a seller looking at
 * eleven on the screen and nine on the shelf needs to know what happened, and
 * "the number is eleven" is not an answer. So the movement is the record and
 * the count is its consequence - every write goes through the ledger, and the
 * ledger is what a reconciliation reads.
 *
 * <p>Which means it has to include sales. An audit that showed a seller's
 * manual corrections and quietly omitted the orders that took the stock would
 * be wrong in exactly the case they are looking at it for.
 *
 * <p>Rows here are append-only. A wrong movement is corrected by another
 * movement, never by editing this one - the history of the mistake is usually
 * the interesting part.
 */
@Entity
@Table(name = "stock_movements",
       indexes = {
           @Index(name = "idx_movement_variant", columnList = "variant_id"),
           @Index(name = "idx_movement_product", columnList = "product_id"),
           @Index(name = "idx_movement_vendor",  columnList = "vendor_id"),
           @Index(name = "idx_movement_when",    columnList = "vendor_id, recordedAt")
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Denormalised from the product, deliberately.
     *
     * <p>Every read of this table is "show me this seller's movements", and
     * reaching it through variant to product to vendor is three joins on the
     * one query a back office runs constantly.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id", nullable = false)
    private Vendor vendor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** Null when the product has no variants and the stock lives on the product. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id")
    private ProductVariant variant;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private StockMovementReason reason;

    /**
     * Signed: negative took stock away, positive put it back.
     *
     * <p>Signed rather than an absolute with a direction flag, because a ledger
     * whose rows sum to the current figure is a ledger you can check. Two
     * columns that have to be read together to know which way a row went is one
     * misread away from a reconciliation that says the opposite of the truth.
     */
    @Column(nullable = false)
    private Integer quantityChange;

    /** The figure before, so a row makes sense without replaying the whole ledger. */
    @Column(nullable = false)
    private Integer stockBefore;

    @Column(nullable = false)
    private Integer stockAfter;

    /**
     * What caused it, where there is one: an order number, a job reference, an
     * IMEI. Text rather than a foreign key, because the causes live in
     * different tables and a row must survive its cause being deleted.
     */
    @Column(length = 60)
    private String reference;

    @Column(length = 300)
    private String note;

    /**
     * Who did it. Null for something the system did on its own - a sale
     * deducting stock has an order behind it rather than a person.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by_user_id")
    private User recordedBy;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime recordedAt;
}
