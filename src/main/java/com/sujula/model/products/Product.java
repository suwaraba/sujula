package com.sujula.model.products;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.sujula.model.Review;
import com.sujula.model.constant.DeliveryScope;
import com.sujula.model.constant.ProductCondition;
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
    private Integer stock = 0;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id", nullable = false)
    private Vendor vendor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id")
    private Brand brand;

    /**
     * Whether a buyer can see this listing.
     *
     * <p>A mirror of {@code status == PUBLISHED}, not an independent fact.
     * Every public catalogue query in this system filters on it, and rewriting
     * all of them to read the enum would be a large change to the one part of
     * the codebase that is under load — so the column stays and
     * {@code ProductLifecycle} is the only thing allowed to write it.
     *
     * <p>The risk of a denormalised mirror is that it drifts, and the answer is
     * that it has exactly one writer and a test asserting the two can never
     * disagree. Set it anywhere else and a suspended listing keeps selling.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    /**
     * The listing's place in the moderation ladder.
     *
     * <p>The authority. {@link #active} follows from it, never the other way
     * round: a seller who could flip a boolean would be a seller who can
     * publish without being approved.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private com.sujula.model.constant.ProductStatus status =
            com.sujula.model.constant.ProductStatus.DRAFT;

    /** When the seller last sent it for review. */
    private LocalDateTime submittedForReviewAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by_user_id")
    private com.sujula.model.user.User reviewedBy;

    private LocalDateTime reviewedAt;

    /**
     * Why a moderator refused it, in words the seller can act on.
     *
     * <p>Kept after a later approval rather than cleared. A seller arguing about
     * why their listing took a week needs the history, and so does anyone
     * checking whether moderation is being applied consistently.
     */
    @Column(length = 500)
    private String rejectionReason;

    /** First time it went on sale. Not reset by an unpublish. */
    private LocalDateTime publishedAt;

    private LocalDateTime unpublishedAt;

    /**
     * When the seller finished with it.
     *
     * <p>Archived rather than deleted whenever anybody has ordered it: an order
     * line points here, and a buyer's receipt, invoice and review all have to
     * keep resolving years later. A listing nobody ever ordered is genuinely
     * deleted.
     */
    private LocalDateTime archivedAt;

    /**
     * A digest of the fields a moderator actually looked at.
     *
     * <p>What makes "this edit needs re-reviewing" answerable without storing a
     * second copy of the listing. Taken when approval is granted; compared on
     * every edit. Changing the stock does not match it and does not re-trigger;
     * rewriting the description does.
     */
    @Column(length = 64)
    private String approvedContentHash;

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

    /**
     * New, refurbished, used.
     *
     * <p>A column rather than a line in the description, because it is a filter
     * buyers actually use and because a diaspora buyer choosing a gift for
     * someone at home cannot pick the item up and look at it. What the listing
     * says is all they have.
     *
     * <p>Defaults to NEW: the overwhelming majority of listings, and the
     * assumption a buyer makes when nothing says otherwise — so a vendor who
     * forgets to set it has not accidentally advertised used goods as new by
     * leaving it null and having the filter skip them.
     */
    @Enumerated(EnumType.STRING)
    // product_condition, not condition: CONDITION is a reserved word in MySQL,
    // so the generated DDL would fail there — and pass on H2, which does not
    // reserve it. That asymmetry is how a schema bug reaches production green.
    @Column(name = "product_condition", length = 20, nullable = false)
    @Builder.Default
    private ProductCondition condition = ProductCondition.NEW;

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

    @Builder.Default
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ProductOption> options = new ArrayList<>();

    // Free-form specification attributes (Battery: 5000 mAh, RAM: 16 GB…)
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<ProductAttribute> attributes = new ArrayList<>();

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Review> reviews = new ArrayList<>();

    // ── Inventory management fields ───────────────────────────────────────────
    private Integer lowStockThreshold=3;

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
