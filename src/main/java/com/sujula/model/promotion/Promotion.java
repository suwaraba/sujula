package com.sujula.model.promotion;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.sujula.model.constant.PromotionStatus;
import com.sujula.model.constant.PromotionType;
import com.sujula.model.user.Vendor;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
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
 * A seller's own discount, applied without anybody typing a code.
 *
 * <p>Distinct from a coupon, and the difference is who knows about it. A coupon
 * is a credential a buyer presents; a promotion is a price the shop is charging,
 * visible to everyone who looks. They are seeded from the same intent and behave
 * nothing alike: a coupon can be limited per customer, a promotion cannot,
 * because there is nobody to count.
 *
 * <p><strong>The money is the vendor's.</strong> A FIXED discount is denominated
 * in the settlement currency, like every other figure a seller states. What a
 * buyer in Madrid sees is that figure converted at the rate their order was
 * quoted at - the discount is not re-struck in EUR, because then the seller
 * would be funding an amount that moves with the market.
 */
@Entity
@Table(name = "promotions",
       indexes = {
           @Index(name = "idx_promo_vendor",        columnList = "vendor_id"),
           @Index(name = "idx_promo_vendor_status", columnList = "vendor_id, status"),
           @Index(name = "idx_promo_window",        columnList = "startsAt, endsAt")
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Promotion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id", nullable = false)
    private Vendor vendor;

    @Column(nullable = false, length = 150)
    private String name;

    /** Shown on the storefront, so buyers know why the price moved. */
    @Column(length = 300)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PromotionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    @Builder.Default
    private PromotionStatus status = PromotionStatus.DRAFT;

    /** Percentage off, for PERCENT. One to a hundred. */
    @Column(precision = 5, scale = 2)
    private BigDecimal percentOff;

    /** Amount off, for FIXED, in {@link #currency}. */
    @Column(precision = 12, scale = 2)
    private BigDecimal amountOff;

    /**
     * Always the vendor's settlement currency.
     *
     * <p>Stored rather than derived, so a seller who later changes where they
     * bank does not silently re-denominate a discount that is already running.
     */
    @Column(length = 3)
    private String currency;

    /** For BUY_X_GET_Y: buy this many. */
    private Integer buyQuantity;

    /** For BUY_X_GET_Y: get this many. */
    private Integer getQuantity;

    /** For BUY_X_GET_Y: what the free ones cost, as a percentage. 100 means free. */
    @Column(precision = 5, scale = 2)
    private BigDecimal getDiscountPercent;

    /** For BUNDLE: what the whole set costs together. */
    @Column(precision = 12, scale = 2)
    private BigDecimal bundlePrice;

    /** Nothing applies below this basket value, in the vendor's currency. */
    @Column(precision = 12, scale = 2)
    private BigDecimal minimumBasket;

    /** A ceiling on what one order can take off, for a percentage on a large basket. */
    @Column(precision = 12, scale = 2)
    private BigDecimal maximumDiscount;

    /**
     * Which goods it covers. Empty means the whole shop.
     *
     * <p>Ids rather than a join to Product: a promotion outlives the listings it
     * named, and an order placed under it has to keep explaining itself after a
     * product is archived.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "promotion_products",
                     joinColumns = @JoinColumn(name = "promotion_id"))
    @Column(name = "product_id")
    @Builder.Default
    private Set<Long> productIds = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "promotion_categories",
                     joinColumns = @JoinColumn(name = "promotion_id"))
    @Column(name = "category_id")
    @Builder.Default
    private Set<Long> categoryIds = new LinkedHashSet<>();

    /**
     * When it runs. Null start means "as soon as it is activated".
     *
     * <p>Whether it applies is asked of the clock rather than of a field
     * somebody has to flip at midnight, which is why ACTIVE covers both
     * scheduled and running.
     */
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;

    /** How many orders have used it, for the seller's own reporting. */
    @Column(nullable = false)
    @Builder.Default
    private Integer timesApplied = 0;

    private LocalDateTime activatedAt;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /** Whether this promotion is discounting anything at this moment. */
    public boolean isRunningAt(LocalDateTime moment) {
        return status == PromotionStatus.ACTIVE
                && (startsAt == null || !moment.isBefore(startsAt))
                && (endsAt == null || moment.isBefore(endsAt));
    }

    /** Whether it covers the whole shop rather than a named set of goods. */
    public boolean isStoreWide() {
        return (productIds == null || productIds.isEmpty())
                && (categoryIds == null || categoryIds.isEmpty());
    }
}
