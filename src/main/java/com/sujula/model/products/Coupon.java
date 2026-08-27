package com.sujula.model.products;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.sujula.model.constant.CouponScope;
import com.sujula.model.constant.CouponType;
import com.sujula.model.user.Vendor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "coupons",
       indexes = {
           @Index(name = "idx_coupon_code",   columnList = "code", unique = true),
           @Index(name = "idx_coupon_vendor", columnList = "vendor_id")
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CouponType type;

    /**
     * Who funds the discount. Platform coupons are prorated across every vendor
     * group in the cart; vendor coupons only touch the issuing vendor's items.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private CouponScope scope = CouponScope.PLATFORM;

    /** The issuing vendor. Required when {@link #scope} is {@code VENDOR}, null otherwise. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id")
    private Vendor vendor;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal value;

    /**
     * Currency that {@link #value}, {@link #minimumOrderAmount} and
     * {@link #maximumDiscountAmount} are denominated in. Required for
     * {@code FIXED_AMOUNT} coupons; ignored for percentage coupons.
     */
    @Column(length = 3)
    private String currency;

    @Column(precision = 10, scale = 2)
    private BigDecimal minimumOrderAmount;

    @Column(precision = 10, scale = 2)
    private BigDecimal maximumDiscountAmount;

    private Integer usageLimit;

    @Column(nullable = false)
    @Builder.Default
    private Integer usageCount = 0;

    private Integer perUserLimit;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    private LocalDateTime startsAt;
    private LocalDateTime expiresAt;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
