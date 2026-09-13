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

    /**
     * This product's own delivery leg, in the order's display currency.
     *
     * <p>Priced per line rather than per order because each product ships from
     * its own vendor's location: two lines in the same basket can travel
     * different distances, weigh different amounts and cost different amounts to
     * deliver. The order's {@code shippingCost} is the sum of these.
     */
    @Column(nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal deliveryCost = BigDecimal.ZERO;



    private String productName;
    private String productSku;
    private String variantSku;
    private String selectedOptions;     // "Size: Large, Color: Red"
    private String productImageUrl;

    /**
     * Which physical handsets went out on this line.
     *
     * <p>Snapshots rather than foreign keys, for the same reason the product
     * name is one: the buyer's receipt has to keep saying which phone they were
     * sent years after anything else about it has changed. It is also what makes
     * a warranty claim or a stolen-handset report resolvable - "a Galaxy A16" is
     * not an answer, and the IMEI is.
     *
     * <p>A list, not a field, because a line of quantity two is two handsets.
     * One column would have held the first and silently lost the second, which
     * is the receipt nobody can settle a warranty claim against. Comma-separated
     * in one column rather than a child table: this is a snapshot for reading
     * back, never a thing to query by - {@link com.sujula.model.inventory.ImeiUnit}
     * is where a handset is looked up, and it carries the reference to this line.
     *
     * <p>Empty for anything that is not tracked handset by handset, which is
     * most of the catalogue.
     */
    @jakarta.persistence.Column(length = 255)
    private String assignedImeis;

    private java.time.LocalDateTime imeiAssignedAt;

    /** The handsets bound to this line, in the order they were bound. */
    public java.util.List<String> assignedImeiList() {
        if (assignedImeis == null || assignedImeis.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(assignedImeis.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    /** Appends one, keeping the column the derived thing it is. */
    public void bindImei(String imei) {
        java.util.List<String> bound = new java.util.ArrayList<>(assignedImeiList());
        bound.add(imei);
        this.assignedImeis = String.join(",", bound);
        this.imeiAssignedAt = java.time.LocalDateTime.now();
    }
}
