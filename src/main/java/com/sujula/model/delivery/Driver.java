package com.sujula.model.delivery;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sujula.model.constant.DriverStatus;
import com.sujula.model.constant.VehicleType;
import com.sujula.model.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;


@Entity
@Table(name = "drivers",
       indexes = {
           @Index(name = "idx_driver_status",   columnList = "status"),
           @Index(name = "idx_driver_country",  columnList = "countryCode"),
           @Index(name = "idx_driver_avail",    columnList = "available, status")
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Driver {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private DriverStatus status = DriverStatus.PENDING;

    @Column(columnDefinition = "TEXT")
    private String adminNote;


    @Column(length = 100)
    private String licenseNumber;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private VehicleType vehicleType;

    @Column(length = 20)
    private String vehiclePlate;

    @Column(length = 100)
    private String vehicleModel;

    @Column(length = 50)
    private String vehicleColor;

    @Column(length = 20)
    private String phone;

    @Column(length = 500)
    private String avatarUrl;


    /**
     * The area the driver wrote on their own application — "Serrekunda", "Bakau".
     *
     * <p>What they say about themselves, kept because it is what they typed and
     * because a dispatcher reads it. It is <em>not</em> what decides where they
     * are offered work: {@link #coverage} is, and it is set by an administrator.
     * The distinction is the same one {@link #available} and {@link #status}
     * draw — this is the driver's own claim, that is the platform's decision —
     * and collapsing them would let a driver widen their own coverage by editing
     * a free-text field.
     */
    private String zone;

    /**
     * The zones an administrator has approved this driver to work in.
     *
     * <p>Empty means they have not been given any yet, which is what an approved
     * driver starts as. Dispatch reads this to decide whom a parcel may be
     * offered to, so it is a delivery-side fact end to end: a zone is a polygon
     * round a destination, never round a payer.
     */
    @JsonIgnore
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "driver_zone_coverage",
               joinColumns = @JoinColumn(name = "driver_id"),
               inverseJoinColumns = @JoinColumn(name = "zone_id"),
               indexes = @Index(name = "idx_dzc_zone", columnList = "zone_id"))
    @Builder.Default
    private java.util.Set<com.sujula.model.logistics.DeliveryZone> coverage = new java.util.LinkedHashSet<>();

    @Column(length = 2)
    private String countryCode;     // ISO 3166-1 alpha-2


    private Double  currentLatitude;
    private Double  currentLongitude;
    private LocalDateTime lastLocationAt;

    /**
     * Whether the driver is taking work right now.
     *
     * <p>Distinct from {@link #status}, and both matter. Status is what the
     * platform decided about them — approved, suspended — and this is what they
     * decided about their own afternoon. A suspended driver who is "available"
     * is still suspended; an approved one who is offline is simply not driving.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean available = false;

    /**
     * When they last went online.
     *
     * <p>Kept apart from {@link #lastLocationAt}, which is the last ping. A
     * driver online for six hours with no ping in five is a phone that has lost
     * signal or an app that has been killed, and the two timestamps are what
     * make that visible.
     */
    private LocalDateTime onlineSince;

    // ── KYC ──────────────────────────────────────────────────────────────────
    //
    // A driver holds other people's goods, frequently a phone worth more than
    // they earn in a month, and meets buyers' families at their homes. The
    // checks below are the whole reason an application is reviewed rather than
    // simply accepted.

    /** National identity document number, as presented. */
    @Column(length = 60)
    private String idDocumentNumber;

    @Column(length = 30)
    private String idDocumentType;

    @Column(length = 500)
    private String idDocumentUrl;

    @Column(length = 500)
    private String licenseDocumentUrl;

    private java.time.LocalDate licenseExpiresOn;

    /** Somebody who will answer if the driver cannot be reached. */
    @Column(length = 120)
    private String nextOfKinName;

    @Column(length = 30)
    private String nextOfKinPhone;

    private LocalDateTime kycSubmittedAt;
    private LocalDateTime kycReviewedAt;

    @Column(length = 400)
    private String kycRejectionReason;

    /**
     * How reliably this driver answers and completes what they accept.
     *
     * <p>Declines move it, which is why the spec says so out loud: a driver who
     * declines everything costs the platform the time between the offer and the
     * next one, and on a parcel somebody is waiting on from abroad that time is
     * the whole service. Not a setter: {@code DriverScore} is the only writer.
     */
    @Setter(AccessLevel.NONE)
    @Column(precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal acceptanceScore = BigDecimal.valueOf(100.00);

    @Setter(AccessLevel.NONE)
    @Builder.Default
    private Integer offersReceived = 0;

    @Setter(AccessLevel.NONE)
    @Builder.Default
    private Integer offersAccepted = 0;

    @Setter(AccessLevel.NONE)
    @Builder.Default
    private Integer offersDeclined = 0;


    @Column(precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal commissionRate = BigDecimal.ZERO;

    @Setter(AccessLevel.NONE)   // Only updated through DriverEarning records
    @Column(precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal totalEarnings = BigDecimal.ZERO;


    @Builder.Default
    private Integer totalDeliveries = 0;

    @Column(precision = 4, scale = 2)
    @Builder.Default
    private BigDecimal averageRating = BigDecimal.ZERO;

    @Builder.Default
    private Integer totalRatings = 0;


    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Column(nullable = false)
    private int maxWeight;


    public void creditEarning(BigDecimal amount) {
        this.totalEarnings = this.totalEarnings.add(amount);
        this.totalDeliveries = this.totalDeliveries + 1;
    }

    /**
     * Records how a driver answered an offer, and re-derives their score.
     *
     * <p>The score is a ratio of the counters rather than a number nudged up and
     * down, so it cannot drift away from the events behind it: recompute it from
     * the counts and it comes back the same.
     */
    public void recordOffer(boolean accepted) {
        this.offersReceived = (this.offersReceived == null ? 0 : this.offersReceived) + 1;
        if (accepted) {
            this.offersAccepted = (this.offersAccepted == null ? 0 : this.offersAccepted) + 1;
        } else {
            this.offersDeclined = (this.offersDeclined == null ? 0 : this.offersDeclined) + 1;
        }
        this.acceptanceScore = this.offersReceived == 0
                ? BigDecimal.valueOf(100.00)
                : BigDecimal.valueOf(this.offersAccepted)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(this.offersReceived), 2,
                                java.math.RoundingMode.HALF_UP);
    }

    /** Whether the platform lets this driver carry anything at all. */
    public boolean canCarry() {
        return status == DriverStatus.APPROVED || status == DriverStatus.ACTIVE;
    }
}
