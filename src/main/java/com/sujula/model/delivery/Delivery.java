package com.sujula.model.delivery;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sujula.model.constant.DeliveryStatus;
import com.sujula.model.order.OrderItem;

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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "deliveries",
       indexes = {
           @Index(name = "idx_del_status",        columnList = "status"),
           @Index(name = "idx_del_driver",         columnList = "driver_id"),
           @Index(name = "idx_del_driver_status",  columnList = "driver_id, status"),
           @Index(name = "idx_del_pp",             columnList = "pickup_point_id"),
           @Index(name = "idx_del_tracking",       columnList = "trackingNumber")
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Delivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnoreProperties({"deliveries", "items", "statusHistory", "totals", "payment", "vendor"})
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_item_id", nullable = false, unique = true)
    private OrderItem orderItem;

    // Assigned driver profile — null until dispatched
    @JsonIgnoreProperties({"user", "deliveries"})
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id")
    private Driver driver;

    // Intermediate hub in a hub-and-spoke model
    @JsonIgnoreProperties({"operatorUser", "deliveries"})
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pickup_point_id")
    private PickupPoint pickupPoint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private DeliveryStatus status = DeliveryStatus.PENDING;

    @Column(unique = true)
    private String trackingNumber;

    // ── Recipient info (denormalised for driver convenience) ──────────────────

    private String recipientName;
    private String recipientPhone;

    /** Straight-line distance in km from pickup origin to delivery address. */
    @Column(precision = 8, scale = 3)
    private BigDecimal distanceKm;

    /** Amount the driver earns for completing this delivery. Set by admin at assignment time. */
    @Column(precision = 10, scale = 2)
    private BigDecimal earningAmount;

    // ── Lifecycle timestamps ──────────────────────────────────────────────────

    private LocalDateTime estimatedDeliveryDate;
    /** Fine-grained ETA provided after dispatch. */
    private LocalDateTime estimatedDeliveryAt;
    private LocalDateTime actualDeliveredAt;
    private boolean contactlessRequested;
    private LocalDateTime assignedAt;
    private LocalDateTime pickedUpAt;
    private LocalDateTime arrivedAtPickupPointAt;
    private LocalDateTime outForDeliveryAt;
    private LocalDateTime deliveredAt;

    @Column(columnDefinition = "TEXT")
    private String notes;

    private String deliveryProofImageUrl;    // photo taken on delivery
    private String recipientSignatureUrl;    // optional digital signature

    // ── Append-only GPS + status event trail ─────────────────────────────────

    @OneToMany(mappedBy = "delivery", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<DeliveryTracking> trackingEvents = new ArrayList<>();

    // ── Audit ─────────────────────────────────────────────────────────────────

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
