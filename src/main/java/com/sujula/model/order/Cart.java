package com.sujula.model.order;

import com.sujula.model.constant.CouponScope;
import com.sujula.model.products.Coupon;
import com.sujula.model.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Entity
@Table(name = "carts",
       indexes = {
           @Index(name = "idx_cart_user",       columnList = "user_id",   unique = true),
           @Index(name = "idx_cart_session",    columnList = "sessionId", unique = true),
           @Index(name = "idx_cart_expires_at", columnList = "expiresAt")
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Cart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", unique = true)
    private User user;

    /**
     * Guest cart identifier. Must be a server-generated, high-entropy value
     * (UUIDv4) delivered to the browser as an HttpOnly cookie — never a
     * client-chosen string, since possession of it grants full cart access.
     */
    @Column(unique = true, length = 36)
    private String sessionId;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<CartItem> items = new ArrayList<>();

    /**
     * Currency the shopper wants totals presented in. Item prices stay in each
     * vendor's own listing currency; conversion happens at read time.
     */
    @Column(nullable = false, length = 3)
    @Builder.Default
    private String displayCurrency = "GMD";

    /**
     * Applied coupons. At most one {@link CouponScope#PLATFORM} coupon plus at
     * most one {@link CouponScope#VENDOR} coupon per vendor.
     */
    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<CartCoupon> appliedCoupons = new ArrayList<>();

    /** TTL for guest carts. Null for user carts, which never expire. */
    private LocalDateTime expiresAt;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    // ── Helpers ───────────────────────────────────────────────────────────────

    public boolean isGuestCart() {
        return user == null;
    }

    /** The platform-wide coupon, if one is applied. */
    public Optional<CartCoupon> platformCoupon() {
        return appliedCoupons.stream()
                .filter(cc -> cc.getCoupon() != null && cc.getCoupon().getScope() == CouponScope.PLATFORM)
                .findFirst();
    }

    /** The coupon applied to a specific vendor's items, if any. */
    public Optional<CartCoupon> vendorCoupon(Long vendorId) {
        return appliedCoupons.stream()
                .filter(cc -> cc.getVendor() != null && cc.getVendor().getId().equals(vendorId))
                .findFirst();
    }

    /**
     * Backwards-compatible accessor for callers written against the old
     * single-coupon model — returns the platform coupon only.
     */
    public Coupon getCoupon() {
        return platformCoupon().map(CartCoupon::getCoupon).orElse(null);
    }

    /**
     * Backwards-compatible mutator. Passing {@code null} clears every applied
     * coupon; otherwise the platform-scoped coupon is replaced.
     */
    public void setCoupon(Coupon coupon) {
        if (coupon == null) {
            appliedCoupons.clear();
            return;
        }
        platformCoupon().ifPresent(appliedCoupons::remove);
        appliedCoupons.add(CartCoupon.builder()
                .cart(this)
                .coupon(coupon)
                .vendor(null)
                .build());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Cart other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
