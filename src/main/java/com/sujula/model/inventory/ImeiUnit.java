package com.sujula.model.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.sujula.model.constant.ImeiGrade;
import com.sujula.model.constant.ImeiStatus;
import com.sujula.model.products.Product;
import com.sujula.model.products.ProductVariant;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;

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
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One physical handset, tracked by its IMEI.
 *
 * <p>Phones are most of what moves on this marketplace and most of them are
 * second-hand, which makes them the one product where a count is not enough. A
 * buyer in Madrid picking a phone for their brother is choosing a specific
 * handset with a specific history, and two units of the same model are not
 * interchangeable: one was opened once and the other has a scratched screen.
 *
 * <p>The IMEI is unique across the whole platform, not per seller. A handset
 * cannot be on two shelves, and a code appearing in a second shop is either a
 * typo or a phone that has been sold twice - both worth refusing at the
 * constraint rather than discovering at dispatch.
 */
@Entity
@Table(name = "imei_units",
       indexes = {
           @Index(name = "idx_imei_vendor",         columnList = "vendor_id"),
           @Index(name = "idx_imei_variant",        columnList = "variant_id"),
           @Index(name = "idx_imei_vendor_status",  columnList = "vendor_id, status"),
           @Index(name = "idx_imei_order_item",     columnList = "order_item_id")
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImeiUnit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Fifteen digits, Luhn-valid, unique platform-wide.
     *
     * <p>Unique globally rather than per vendor: the same handset in two shops
     * is a phone somebody has sold twice, and the constraint is the cheapest
     * place to find out.
     */
    @Column(nullable = false, unique = true, length = 15)
    private String imei;

    /** Dual-SIM handsets carry a second. Not unique-constrained: it is optional. */
    @Column(length = 15)
    private String imei2;

    /** The serial as printed, where it differs from the IMEI. */
    @Column(length = 40)
    private String serialNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id", nullable = false)
    private Vendor vendor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** Which configuration this handset is - 128GB black, and so on. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id")
    private ProductVariant variant;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ImeiStatus status = ImeiStatus.IN_STOCK;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    @Builder.Default
    private ImeiGrade grade = ImeiGrade.A_GRADE;

    /**
     * The fault, when the grade admits one.
     *
     * <p>Required for FOR_PARTS and enforced by the service. "Sold for parts"
     * with no reason is the listing that generates the dispute.
     */
    @Column(length = 300)
    private String gradeNote;

    /** What the seller paid, in their own currency. Never shown to a buyer. */
    @Column(precision = 12, scale = 2)
    private BigDecimal costPrice;

    /** Battery health as a percentage, where the seller measured it. */
    private Integer batteryHealth;

    private LocalDate warrantyExpiresOn;

    /** The order this handset went out on, once it has. */
    @Column(length = 40)
    private String soldOnOrderNumber;

    private LocalDateTime soldAt;

    /**
     * The order line this handset was picked for.
     *
     * <p>Set when the seller binds it while packing, which is the moment the
     * abstract "one Galaxy A16" on an order becomes a specific handset with a
     * specific history. This side is the authority: {@code OrderItem} keeps a
     * comma-separated snapshot for the receipt, but anything asking <em>which
     * line is this handset on</em> asks here, because this is the end that can
     * be indexed and queried.
     *
     * <p>Null for everything on the shelf, and for every handset sold before a
     * seller was asked to bind one.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_item_id")
    private com.sujula.model.order.OrderItem orderItem;

    /** When the seller bound it to that line. */
    private LocalDateTime assignedAt;

    @Column(length = 300)
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "registered_by_user_id")
    private User registeredBy;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public boolean isSellable() {
        return status != null && status.isSellable();
    }
}
