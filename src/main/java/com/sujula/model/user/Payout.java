package com.sujula.model.user;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.sujula.model.constant.PayoutStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "vendor_payouts")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payout {
    // Owned by a User, not a Vendor, on purpose: drivers and pickup-point
    // operators earn on this platform too, and settle through this same table.
    // Vendor previously declared a payouts collection mapped by a "vendor"
    // property that has never existed here, which stopped Hibernate building the
    // entity manager at all.

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * The store this pays out, when it is a store being paid.
     *
     * <p>Nullable because the note above is still true: drivers and pickup-point
     * operators settle through this same table and have no vendor row. A payout
     * with a vendor is a shop's; one without is somebody else's earnings.
     *
     * <p>The user link stays authoritative for who is actually paid. This is how
     * the money is attributed, which is a different question - a store can
     * change hands.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id")
    private com.sujula.model.user.Vendor vendor;

    /**
     * Who asked, when a person did.
     *
     * <p>Null for a payout the platform ran on its own schedule. That
     * distinction is worth keeping: "the seller asked for this on the 3rd" and
     * "the monthly run picked it up" are answers to different questions from
     * support.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by_user_id")
    private User requestedBy;

    private LocalDateTime requestedAt;

    /**
     * The period this settles, as {@code YYYY-MM}, when it settles one.
     *
     * <p>Null for an on-demand request, which settles whatever was available at
     * the moment it was asked for rather than a calendar month. A statement is
     * built for a period; a payout is not necessarily.
     */
    @Column(length = 7)
    private String period;

    /** Why it failed, in words the seller can act on. */
    @Column(length = 300)
    private String failureReason;

    /**
     * What is transferred, in the vendor's own currency.
     *
     * <p>Never a figure converted for the payout. A seller in Banjul is paid
     * dalasi whatever their buyers paid in, and the conversion already happened
     * once, at checkout, at a rate the order still carries (C2).
     */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    @Builder.Default
    private String currency = "USD";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private PayoutStatus status = PayoutStatus.REQUESTED;

    private String reference;
    private String notes;

    private LocalDateTime processedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "processed_by")
    private User processedBy;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
