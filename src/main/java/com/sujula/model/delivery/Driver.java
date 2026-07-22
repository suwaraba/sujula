package com.sujula.model.delivery;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sujula.model.constant.DriverStatus;
import com.sujula.model.constant.VehicleType;
import com.sujula.model.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;


@Entity
@Table(name = "drivers",
       indexes = {
           @Index(name = "idx_driver_status",   columnList = "status"),
           @Index(name = "idx_driver_country",  columnList = "countryCode"),
           @Index(name = "idx_driver_avail",    columnList = "available, status")
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Driver {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private DriverStatus status = DriverStatus.PENDING;

    @Column(columnDefinition = "TEXT")
    private String adminNote;


    @Column(length = 100)
    private String licenseNumber;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private VehicleType vehicleType;

    @Column(length = 20)
    private String vehiclePlate;

    @Column(length = 100)
    private String vehicleModel;

    @Column(length = 50)
    private String vehicleColor;

    @Column(length = 20)
    private String phone;

    @Column(length = 500)
    private String avatarUrl;


    private String zone;            // city / district the driver normally covers

    @Column(length = 2)
    private String countryCode;     // ISO 3166-1 alpha-2


    private Double  currentLatitude;
    private Double  currentLongitude;
    private LocalDateTime lastLocationAt;

    @Column(nullable = false)
    @Builder.Default
    private boolean available = false;


    @Column(precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal commissionRate = BigDecimal.ZERO;

    @Setter(AccessLevel.NONE)   // Only updated through DriverEarning records
    @Column(precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal totalEarnings = BigDecimal.ZERO;


    @Builder.Default
    private Integer totalDeliveries = 0;

    @Column(precision = 4, scale = 2)
    @Builder.Default
    private BigDecimal averageRating = BigDecimal.ZERO;

    @Builder.Default
    private Integer totalRatings = 0;


    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Column(nullable = false)
    private int maxWeight;


    public void creditEarning(BigDecimal amount) {
        this.totalEarnings = this.totalEarnings.add(amount);
        this.totalDeliveries = this.totalDeliveries + 1;
    }
}
