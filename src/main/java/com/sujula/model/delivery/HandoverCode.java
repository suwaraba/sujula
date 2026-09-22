package com.sujula.model.delivery;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

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
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One-time 6-digit OTP used to confirm a physical package handover.
 * A new code is generated for each leg of the delivery chain.
 *
 * Lifecycle: GENERATED → recipient provides code → USED (marked used=true).
 * Codes expire after a configurable TTL (default 24 h).
 */
@Entity
@Table(name = "handover_codes",
       indexes = {
           @Index(name = "idx_hc_delivery",  columnList = "delivery_id"),
           @Index(name = "idx_hc_code",      columnList = "code"),
           @Index(name = "idx_hc_type_used", columnList = "codeType, used")
       }
)
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HandoverCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Optimistic lock — prevents concurrent invalidation + creation races. */
    @Version
    private Long version;

    /**
     * The parcel this code belongs to, for every link after the shop door.
     *
     * <p>Nullable because {@link com.sujula.model.constant.HandoverCodeType#VENDOR_RELEASE}
     * hangs off a vendor order instead: a seller packs one parcel for the whole
     * slice, and the driver collecting it presents one code rather than one per
     * line. Exactly one of the two is set, which the service enforces - a code
     * belonging to neither would authorise nothing, and one belonging to both
     * would authorise two different handovers.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_id")
    private Delivery delivery;

    /** The vendor order this code releases, for VENDOR_RELEASE. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_order_id")
    private com.sujula.model.order.VendorOrder vendorOrder;

    /**
     * The parcel this code covers, for every link after the shop.
     *
     * <p>A third owner alongside delivery and vendor order, and the one the
     * driver surface actually uses. Exactly one of the three is set.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipment_id")
    private com.sujula.model.shipment.Shipment shipment;

    /**
     * The leg this code opens, where it opens one rather than the whole parcel.
     *
     * <p>A shipment through two hubs has a code per handover, not one code that
     * works at every counter between Banjul and Basse.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "leg_id")
    private com.sujula.model.shipment.ShipmentLeg leg;

    /**
     * How many times the wrong code has been tried against this one.
     *
     * <p>Six digits is a hundred thousand guesses from a determined person and
     * three from somebody who misheard. The count is what tells them apart, and
     * what burns a code being brute-forced at a counter.
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer failedAttempts = 0;

    /**
     * Set when a later code supersedes this one.
     *
     * <p>Reissuing replaces rather than edits, so a leaked code is dead and the
     * fact that it was reissued survives. A seller reissuing constantly is worth
     * seeing.
     */
    private LocalDateTime invalidatedAt;

    /** Which leg of the chain this code covers. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private com.sujula.model.constant.HandoverCodeType codeType;

    /** 6-digit numeric OTP (stored as String to preserve leading zeros). */
    @Column(nullable = false, length = 6)
    private String code;

    /** True once the recipient has presented and the system has validated the code. */
    @Column(nullable = false)
    @Builder.Default
    private boolean used = false;

    /** UserId of the party that consumed (verified) this code. */
    private Long usedByUserId;

    private LocalDateTime usedAt;

    /** Hard expiry — codes become invalid after this timestamp even if unused. */
    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
