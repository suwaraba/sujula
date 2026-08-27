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

    @Setter(lombok.AccessLevel.NONE)
    @Column(precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal defaultCommissionRate = BigDecimal.valueOf(10.00);

    @Column(precision = 4, scale = 2)  
    @Builder.Default
    private BigDecimal rating = BigDecimal.ZERO;

    @Builder.Default
    private Integer totalReviews = 0;

    @Builder.Default
    private Integer totalSold = 0;

    @Builder.Default
    private Boolean acive;

    // --- Legal ---
    private String businessRegistrationNumber;
    private String taxNumber;

    @JsonIgnore
    @OneToMany(mappedBy = "vendor", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Product> products = new ArrayList<>();

    @JsonIgnore
    @OneToMany(mappedBy = "vendor", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<BankAccount> bankAccounts = new ArrayList<>();


    @JsonIgnore
    @OneToMany(mappedBy = "vendor", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Payout> payouts = new ArrayList<>();

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
    
}
