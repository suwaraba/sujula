package com.sujula.model.order;

import com.sujula.model.products.Product;
import com.sujula.model.products.ProductVariant;
import com.sujula.model.user.Vendor;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "cart_items",
       uniqueConstraints = @UniqueConstraint(
               name = "uk_cart_product_variant",
               // variantKey (not variant_id) because SQL treats NULL as distinct
               // from NULL, so a nullable variant_id would let duplicate lines
               // through for variant-less products.
               columnNames = {"cart_id", "product_id", "variant_key"}),
       indexes = {
           @Index(name = "idx_cart_item_cart",   columnList = "cart_id"),
           @Index(name = "idx_cart_item_vendor", columnList = "vendor_id")
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CartItem {

    /** Stand-in for "no variant selected" in the uniqueness key. */
    public static final long NO_VARIANT = 0L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    // Null for products without variants
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id")
    private ProductVariant variant;

    /**
     * Denormalised copy of {@code product.vendor}. Lets the cart be grouped and
     * priced per vendor without dereferencing every lazy Product association.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id", nullable = false)
    private Vendor vendor;

    /** Derived from {@link #variant}; maintained by the lifecycle callbacks below. */
    @Column(name = "variant_key", nullable = false)
    @Builder.Default
    private Long variantKey = NO_VARIANT;

    @Column(nullable = false)
    private Integer quantity;

    /**
     * Last known listing price, in {@link #unitPriceCurrency}. This is a display
     * snapshot only — it is re-read from the product on every cart load and the
     * shopper is told when it moves. It is never a price guarantee.
     */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    /** The vendor's listing currency for this line. Never converted in place. */
    @Column(nullable = false, length = 3)
    private String unitPriceCurrency;

    /** When {@link #unitPrice} was last reconciled against the live listing. */
    private LocalDateTime priceCheckedAt;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @PrePersist
    @PreUpdate
    private void syncVariantKey() {
        variantKey = (variant != null && variant.getId() != null) ? variant.getId() : NO_VARIANT;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Line total in the vendor's listing currency. */
    public BigDecimal nativeLineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    public Long variantIdOrNull() {
        return variant != null ? variant.getId() : null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CartItem other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
