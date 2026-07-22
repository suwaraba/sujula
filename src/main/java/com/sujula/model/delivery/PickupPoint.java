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

    @Column(nullable = false)
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
