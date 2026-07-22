package com.sujula.model.products;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.sujula.model.Review;
import com.sujula.model.constant.DeliveryScope;
import com.sujula.model.user.Vendor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "products")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(unique = true, nullable = false)
    private String slug;

    private String shortDescription;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(precision = 10, scale = 2)
    private BigDecimal compareAtPrice;


    @Column(nullable = false, length = 3)
    private String priceCurrency;

    // Base SKU for variant-less products; variants carry their own SKU
    private String sku;

    // Base stock for variant-less products; variants carry their own stockQuantity
    @Column(nullable = false)
    @Builder.Default
    private Integer stockQuantity = 0;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id", nullable = false)
    private Vendor vendor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id")
    private Brand brand;


    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(nullable = false)
    @Builder.Default
    private boolean featured = false;

    private Double weightKg;
    private String dimensions;      // "30cm × 20cm × 10cm"

    // Country of origin — ISO 3166-1 alpha-2
    @Column
    private String country;
   

    private Double latitude;
    private Double longitude;

    private Integer score; 

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private DeliveryScope deliveryScope = DeliveryScope.REGIIONAL;

    @Column(precision = 4, scale = 2)  
    @Builder.Default
    private BigDecimal rating = BigDecimal.ZERO;

    @Builder.Default
    private Integer totalReviews = 0;

    @Builder.Default
    private Integer totalSold = 0;

    // Proper image entities replacing @ElementCollection
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<ProductImage> images = new ArrayList<>();

    // Configurable option–value variants (Size=L + Color=Red → one ProductVariant)
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<ProductVariant> variants = new ArrayList<>();

    // Free-form specification attributes (Battery: 5000 mAh, RAM: 16 GB…)
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<ProductAttribute> attributes = new ArrayList<>();

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Review> reviews = new ArrayList<>();

    // ── Inventory management fields ───────────────────────────────────────────
    private Integer lowStockThreshold;

    @Column(nullable = false)
    @Builder.Default
    private boolean allowBackorder = false;

    private LocalDateTime lastRestockedAt;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
