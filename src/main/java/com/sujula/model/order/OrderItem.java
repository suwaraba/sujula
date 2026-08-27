package com.sujula.model.order;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sujula.model.products.Product;
import com.sujula.model.products.ProductVariant;
import com.sujula.model.user.Vendor;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "order_items")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Customer-facing parent order — back-reference, not serialised (would cause circular loop)
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    // Full Product entity not serialised — only snapshot fields below are needed.
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    // Null for products that have no variants
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id")
    private ProductVariant variant;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id", nullable = false)
    private Vendor vendor;

    /** This vendor's slice of the order. Assigned once items are grouped at checkout. */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_order_id")
    private VendorOrder vendorOrder;

    @Column(nullable = false)
    private Integer quantity;

    // --- Snapshots at time of purchase (survives later product edits) ---

    /** Unit price in {@link #currency} — the vendor's own listing currency. Never converted in place. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalPrice;

    /** The vendor's listing currency for {@link #unitPrice} / {@link #totalPrice}. */
    @Column(nullable = false, length = 3)
    private String currency;

    /** Same amounts converted into the order's display currency, frozen at checkout. */
    @Column(precision = 12, scale = 2)
    private BigDecimal unitPriceConverted;

    @Column(precision = 12, scale = 2)
    private BigDecimal totalPriceConverted;

    @Column(nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal deliveryCost = BigDecimal.ZERO;



    private String productName;
    private String productSku;
    private String variantSku;
    private String selectedOptions;     // "Size: Large, Color: Red"
    private String productImageUrl;

}
