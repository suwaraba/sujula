package com.sujula.model.user;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.products.Product;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "vendors",
       indexes = @Index(name = "idx_vendor_status", columnList = "status"))
@Getter
@Setter          
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Vendor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(nullable = false, length = 100)
    private String storeName;

    @Column(unique = true, nullable = false, length = 120)
    private String storeSlug;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(unique = true, length = 150)
    private String storeEmail;

    @Column(length = 25)
    private String storePhone;

    @Column(length = 255)
    private String website;

    @Column(length = 500)
    private String logoUrl;

    @Column(length = 500)
    private String bannerUrl;

    @Column(length = 255)
    private String addressStreet;

    @Column(length = 100)
    private String addressCity;

    @Column(length = 100)
    private String addressState;

    @Column(length = 20)
    private String addressPostalCode;
    @Column(length = 2)
    private String addressCountryCode;  // ISO 3166-1 alpha-2

    private Double latitude;
    private Double longitude;

    /**
     * How well the pickup address was placed on a map.
     *
     * <p>This address is a delivery-side fact: it is where a driver is sent to
     * collect, and it is one end of every distance this store's shipping is
     * priced from. So the same thing that matters for a buyer's address matters
     * here — a pin on the centre of Serrekunda and a pin on the shop door look
     * identical once they are two numbers, and the leg priced from the first is
     * wrong by kilometres.
     *
     * <p>It is emphatically not a payment-side fact. Nothing here decides which
     * currency this vendor settles in or which methods a buyer is offered; that
     * is {@link #settlementCurrency} and the payer's own context, and the two
     * questions are answered from different fields on purpose.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    @Builder.Default
    private com.sujula.model.constant.GeocodeConfidence geocodeConfidence =
            com.sujula.model.constant.GeocodeConfidence.NONE;

    private LocalDateTime geocodedAt;

    /**
     * A second address to collect from, when goods do not leave the shop front.
     *
     * <p>Null means "collect from the store address". A vendor whose storefront
     * is a stall at Serrekunda market and whose stock is in a compound two
     * kilometres away needs these to be two things, and pricing a collection
     * from the wrong one is wrong on every order they take.
     */
    @Column(length = 255)
    private String pickupStreet;

    @Column(length = 100)
    private String pickupCity;

    @Column(length = 100)
    private String pickupState;

    @Column(length = 20)
    private String pickupPostalCode;

    @Column(length = 2)
    private String pickupCountryCode;

    private Double pickupLatitude;
    private Double pickupLongitude;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private com.sujula.model.constant.GeocodeConfidence pickupGeocodeConfidence;

    /** Notes for the driver: which gate, who to ask for, what the shop looks like. */
    @Column(length = 400)
    private String pickupInstructions;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private PartnerStatus status = PartnerStatus.PENDING;

    /**
     * The single currency this vendor trades and settles in — ISO 4217.
     *
     * <p>Source of truth for everything vendor-facing: their listings must be
     * priced in it, their order figures are reported in it, and payouts are made
     * in it. Buyers may view the storefront in any currency, but that conversion
     * never propagates back into the vendor's own view of their business.
     */
    @Column(nullable = false, length = 3)
    @Builder.Default
    private String settlementCurrency = "GMD";

    @Setter(lombok.AccessLevel.NONE)
    @Column(precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal balance = BigDecimal.ZERO;

    /**
     * What this store is charged when nothing more specific applies.
     *
     * <p>A cache of the live {@code CommissionRate} row rather than the
     * authority. The rate that settles an order is the one in force on the day
     * it was placed, snapshotted onto the vendor order at checkout — changing
     * this number cannot re-price anything that has already happened, which is
     * the property the commission table exists to give.
     */
    @Setter(lombok.AccessLevel.NONE)
    @Column(precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal defaultCommissionRate = BigDecimal.valueOf(10.00);

    /**
     * Set while this store's money is not going out.
     *
     * <p>Written when a store is suspended, and read before any payout is
     * created — so the hold is structural rather than a step somebody remembers.
     * A suspension that stopped new orders but kept paying out last month's
     * would be a suspension that costs the platform money on exactly the stores
     * it has decided not to trust.
     *
     * <p>The money is held, not refused. The seller earned it, and a hold that
     * reads as a cancellation is how a suspension becomes a complaint about
     * theft.
     */
    /**
     * What the platform decided about this store, in words the seller is shown.
     *
     * <p>Carries the rejection or suspension reason. On the store rather than
     * only in the audit log because the seller reads it: an audit row explains a
     * decision to the platform, and this explains it to the person it was made
     * about.
     */
    @Column(length = 1000)
    private String adminNote;

    private LocalDateTime payoutsHeldAt;

    /** Why, in words the seller is shown. */
    @Column(length = 500)
    private String payoutsHeldReason;

    /** Whether this store's money is currently held. */
    public boolean arePayoutsHeld() {
        return payoutsHeldAt != null;
    }

    /** Set by the admin commission endpoint after writing an effective-dated row. */
    public void applyDefaultCommissionRate(BigDecimal rate) {
        this.defaultCommissionRate = rate;
    }

    @Column(precision = 4, scale = 2)  
    @Builder.Default
    private BigDecimal rating = BigDecimal.ZERO;

    @Builder.Default
    private Integer totalReviews = 0;

    @Builder.Default
    private Integer totalSold = 0;

    // --- Legal ---
    private String businessRegistrationNumber;
    private String taxNumber;

    // --- Policies, as the buyer reads them before deciding ---

    /**
     * What this store will take back, and on what terms.
     *
     * <p>Per store rather than per platform. A phone dealer in Banjul and a
     * tailor in Dakar cannot have the same returns policy, and a marketplace
     * that imposes one either drives out the tailor or lies to the buyer.
     */
    @Column(columnDefinition = "TEXT")
    private String returnPolicy;

    /** How and from where this store ships, in the seller's own words. */
    @Column(columnDefinition = "TEXT")
    private String shippingPolicy;

    /** Anything the buyer should know before ordering — made to order, deposits. */
    @Column(columnDefinition = "TEXT")
    private String storePolicy;

    /**
     * Working days between an order being paid for and being ready to collect.
     *
     * <p>This is the seller's half of a delivery estimate and the courier's half
     * is the other; quoting a buyer in Madrid a date without it promises a
     * shipping speed for goods that have not been packed. Two days by default,
     * which is what most sellers here actually manage.
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer handlingDays = 2;

    /**
     * Set when the vendor asked to stop taking orders for a while.
     *
     * <p>Distinct from SUSPENDED, which is the platform's decision about them.
     * Travelling, restocking or grieving is the seller's own, and conflating the
     * two puts a note on their storefront saying they were suspended.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean vacationMode = false;

    @Column(length = 300)
    private String vacationMessage;

    @JsonIgnore
    @OneToMany(mappedBy = "vendor", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Product> products = new ArrayList<>();

    @JsonIgnore
    @OneToMany(mappedBy = "vendor", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<BankAccount> bankAccounts = new ArrayList<>();

    /** When a driver may collect, one row per weekday. */
    @JsonIgnore
    @OneToMany(mappedBy = "vendor", cascade = CascadeType.ALL, orphanRemoval = true,
               fetch = FetchType.LAZY)
    @Builder.Default
    private List<com.sujula.model.store.StoreOperatingHours> operatingHours = new ArrayList<>();


    // Payouts are not mapped from here: they belong to the owning User, so that
    // drivers and pickup-point operators settle through the same table. Read a
    // vendor's payouts by their user id.

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
    
}
