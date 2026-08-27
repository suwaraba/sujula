package com.sujula.model.order;

import com.sujula.model.products.Coupon;
import com.sujula.model.user.Vendor;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * A coupon applied to a cart.
 *
 * <p>Modelled as its own row rather than a column on {@link Cart} because a
 * multivendor cart may legitimately carry one platform-funded coupon plus one
 * vendor-funded coupon per vendor.
 */
@Entity
@Table(name = "cart_coupons",
       uniqueConstraints = @UniqueConstraint(
               name = "uk_cart_coupon_vendor",
               columnNames = {"cart_id", "vendor_key"}),
       indexes = @Index(name = "idx_cart_coupon_cart", columnList = "cart_id"))
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CartCoupon {

    /** Stand-in for "platform-wide, not tied to a vendor" in the uniqueness key. */
    public static final long PLATFORM_KEY = 0L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "coupon_id", nullable = false)
    private Coupon coupon;

    /** Null for platform-scoped coupons. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id")
    private Vendor vendor;

    /** Derived from {@link #vendor}; keeps the unique constraint NULL-safe. */
    @Column(name = "vendor_key", nullable = false)
    @Builder.Default
    private Long vendorKey = PLATFORM_KEY;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime appliedAt;

    @PrePersist
    @PreUpdate
    private void syncVendorKey() {
        vendorKey = (vendor != null && vendor.getId() != null) ? vendor.getId() : PLATFORM_KEY;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CartCoupon other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
