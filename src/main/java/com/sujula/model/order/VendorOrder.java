package com.sujula.model.order;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sujula.model.constant.VendorOrderStatus;
import com.sujula.model.products.Coupon;
import com.sujula.model.user.Vendor;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One vendor's slice of a multivendor {@link Order}.
 *
 * <p>Split out from {@code Order} because fulfilment, cancellation and payout
 * all happen per vendor — a buyer's single checkout can leave one vendor's
 * items shipped while another's are still pending, and a cancellation must be
 * able to touch just one vendor's slice without disturbing the rest.
 *
 * <p>Carries amounts twice: {@code *Native} in the vendor's own settlement
 * currency (what the vendor is actually owed — never converted) and the plain
 * fields in the order's display currency (what the buyer saw and paid),
 * mirroring how {@code CartResponse.VendorGroup} prices a still-open cart.
 * {@code nativeCurrency} and the {@code *Native} amounts are null when this
 * vendor's lines spanned more than one listing currency at checkout — payout
 * accounting then falls back to the per-line native amounts on each
 * {@link OrderItem}.
 */
@Entity
@Table(name = "vendor_orders",
       indexes = {
           @Index(name = "idx_vendor_order_order",  columnList = "order_id"),
           @Index(name = "idx_vendor_order_vendor", columnList = "vendor_id"),
           @Index(name = "idx_vendor_order_status", columnList = "status")
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class VendorOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vendor_id", nullable = false)
    private Vendor vendor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private VendorOrderStatus status = VendorOrderStatus.PENDING;

    /** The vendor's own settlement currency; null when this slice spanned several. */
    @Column(length = 3)
    private String nativeCurrency;

    @Column(precision = 12, scale = 2)
    private BigDecimal subtotalNative;

    @Column(precision = 12, scale = 2)
    private BigDecimal discountNative;

    @Column(precision = 12, scale = 2)
    private BigDecimal totalNative;

    // ── Same amounts, in the order's display currency (what the buyer paid) ───

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

    @Column(nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal discount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total;

    /** Vendor-scoped coupon applied to this slice, if any. Snapshot survives coupon deletion. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "coupon_id")
    @JsonIgnore
    private Coupon coupon;

    @Column(length = 50)
    private String couponCode;

    @OneToMany(mappedBy = "vendorOrder", fetch = FetchType.LAZY)
    @Builder.Default
    private List<OrderItem> items = new ArrayList<>();

    private LocalDateTime cancelledAt;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
