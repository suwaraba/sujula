package com.sujula.model.products;

import com.sujula.model.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * One redemption of a coupon, recorded at order placement.
 *
 * <p>Backs the per-user redemption limit that {@code Coupon.perUserLimit}
 * declares. Without this table that limit is unenforceable.
 */
@Entity
@Table(name = "coupon_usages",
       indexes = {
           @Index(name = "idx_coupon_usage_coupon_user", columnList = "coupon_id, user_id"),
           @Index(name = "idx_coupon_usage_order",       columnList = "order_id")
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CouponUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "coupon_id", nullable = false)
    private Coupon coupon;

    /** Null for guest redemptions, which cannot be rate-limited per user. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /** The order the coupon was redeemed on. */
    @Column(name = "order_id")
    private Long orderId;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime usedAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CouponUsage other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
