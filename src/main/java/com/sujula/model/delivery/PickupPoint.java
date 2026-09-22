package com.sujula.model.delivery;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.user.User;

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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A physical collection / handover point.
 * Operators apply → admin approves → point becomes operational.
 */
@Entity
@Table(name = "pickup_points",
       indexes = {
           @Index(name = "idx_pp_status",    columnList = "status"),
           @Index(name = "idx_pp_operator",  columnList = "operator_user_id"),
           @Index(name = "idx_pp_country",   columnList = "countryCode")
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PickupPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;


    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "operator_user_id")
    private User operatorUser;

    // ── Application / approval ────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PartnerStatus status = PartnerStatus.PENDING;

    @Column(columnDefinition = "TEXT")
    private String adminNote;

    // ── Location ──────────────────────────────────────────────────────────────

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String addressStreet;

    private String addressApartment;

    @Column(nullable = false)
    private String city;

    private String state;

    /**
     * Nullable, because most addresses in this market do not have one.
     *
     * <p>Was NOT NULL, which forced every Gambian counter to store an empty
     * string - the seed already worked around it that way, which is the tell. A
     * constraint every real row has to lie to satisfy is a constraint that
     * enforces nothing and hides the one case where a postal code is genuinely
     * missing versus genuinely blank.
     *
     * <p>This is also why {@code latitude} and {@code longitude} are required on
     * an application while a postal code is not: the position is what a driver
     * navigates to, and it is the field that actually exists here.
     */
    private String postalCode;

    @Column(nullable = false, length = 2)
    private String countryCode;         // ISO 3166-1 alpha-2

    private Double latitude;
    private Double longitude;

    // ── Contact ───────────────────────────────────────────────────────────────

    private String contactPhone;
    private String contactEmail;
    private String managerName;

    /** Storefront / operator profile image URL (Cloudflare R2). */
    private String profileImageUrl;

    /** Free-text operating hours: "Mon–Fri 08:00–20:00, Sat 09:00–16:00" */
    private String openingHours;

    // ── Capacity ─────────────────────────────────────────────────────────────
    //
    // A pickup point here is usually a shop counter or a back room, not a depot.
    // Capacity is a real physical limit measured in parcels somebody can find
    // again, and a point that keeps accepting past it is a point where things go
    // missing.

    /** How many parcels this counter can hold at once. */
    @Column(nullable = false)
    @Builder.Default
    private Integer capacity = 50;

    /**
     * How many are on the shelf right now.
     *
     * <p>Derived from the parcels stored here and kept on the row so the public
     * search does not need a join per result. {@code PickupCounter} is the only
     * writer, and it recounts rather than increments - a counter that is nudged
     * drifts, and what it drifts into is accepting parcels there is no room for.
     */
    @Setter(lombok.AccessLevel.NONE)
    @Column(nullable = false)
    @Builder.Default
    private Integer storedParcels = 0;

    // ── Closing for a while ──────────────────────────────────────────────────

    /**
     * Shut until this moment, without being shut down.
     *
     * <p>Distinct from {@link #active} and from {@link #status}, and all three
     * matter. Status is what the platform decided, active is whether the point
     * exists at all, and this is the operator saying they are away for a funeral
     * or it is Koriteh. A point closed this way keeps the parcels it already has
     * and stops being offered new ones.
     */
    private LocalDateTime closedUntil;

    @Column(length = 300)
    private String closureReason;

    // ── Storage and money ────────────────────────────────────────────────────

    /**
     * How long a parcel may sit before it goes back to the seller.
     *
     * <p>Per point rather than platform-wide: a counter in a market with no
     * spare shelf needs a shorter window than one in a shop with a store room,
     * and a deadline nobody can meet is a deadline that gets ignored.
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer storageDays = 7;

    /**
     * What the operator earns for handling one parcel, in their own currency.
     *
     * <p>Frozen onto each parcel when it is accepted rather than read at payout,
     * so an operator who took a parcel at one rate is paid that rate.
     */
    @Column(precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal commissionPerParcel = BigDecimal.ZERO;

    @Column(length = 3)
    @Builder.Default
    private String commissionCurrency = "GMD";

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    /** Cumulative earnings credited to this point. Protected — updated via PickupPointTransaction. */
    @Setter(lombok.AccessLevel.NONE)
    @Column(precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal totalEarnings = BigDecimal.ZERO;

    // ── Aggregate statistics ──────────────────────────────────────────────────

    @Builder.Default
    private Integer totalTransactions = 0;

    /** Resets to 0 at the start of each calendar month by a scheduled job. */
    @Builder.Default
    private Integer monthlyDeliveries = 0;

    // ── Relations ─────────────────────────────────────────────────────────────

    @JsonIgnore
    @OneToMany(mappedBy = "pickupPoint", fetch = FetchType.LAZY)
    @Builder.Default
    private List<Delivery> deliveries = new ArrayList<>();

    // ── Audit ─────────────────────────────────────────────────────────────────

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /**
     * Whether this counter can take another parcel today.
     *
     * <p>Four separate questions, and a point fails on any one of them: it has
     * to be approved, switched on, not closed for the week, and have shelf
     * space. Collapsing them into one flag is how a suspended point keeps
     * receiving goods.
     */
    public boolean canAcceptParcels() {
        return status == PartnerStatus.APPROVED
                && active
                && !isTemporarilyClosed()
                && hasSpace();
    }

    public boolean isTemporarilyClosed() {
        return closedUntil != null && closedUntil.isAfter(LocalDateTime.now());
    }

    public boolean hasSpace() {
        return storedParcels == null || capacity == null || storedParcels < capacity;
    }

    /** Set by {@code PickupCounter} after recounting. Never incremented in place. */
    public void applyStoredCount(int counted) {
        this.storedParcels = counted;
    }

    // ── Package-private helpers ───────────────────────────────────────────────

    /** Called by PickupPointOperatorServiceImpl only — do not call directly. */
    public void creditEarning(BigDecimal amount) {
        this.totalEarnings    = this.totalEarnings.add(amount);
        this.totalTransactions = this.totalTransactions + 1;
        this.monthlyDeliveries = this.monthlyDeliveries + 1;
    }

    /** Called by scheduled job at start of each month. */
    public void resetMonthlyDeliveries() {
        this.monthlyDeliveries = 0;
    }

    /** Called by AdminPickupPointPayoutServiceImpl — deducts a paid-out amount from earnings. */
    public void debitEarning(BigDecimal amount) {
        this.totalEarnings = this.totalEarnings.subtract(amount);
    }

    /** Reverses a failed payout by restoring the deducted amount. */
    public void refundEarning(BigDecimal amount) {
        this.totalEarnings = this.totalEarnings.add(amount);
    }
}
