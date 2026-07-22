package com.sujula.model.delivery;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "delivery_routes",
       indexes = {
           @Index(name = "idx_route_driver_date", columnList = "driver_id, route_date", unique = true)
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryRoute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    @Column(name = "route_date", nullable = false)
    private LocalDate routeDate;

    /** JSON array of delivery IDs e.g. "[1,2,3]" */
    @Column(columnDefinition = "TEXT")
    private String deliveryIds;

    /** JSON array of delivery IDs in optimized order */
    @Column(columnDefinition = "TEXT")
    private String optimizedOrder;

    private Double totalEstimatedDistanceKm;

    private Integer estimatedDurationMinutes;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
