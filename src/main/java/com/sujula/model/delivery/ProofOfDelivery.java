package com.sujula.model.delivery;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "proof_of_delivery",
       indexes = {
           @Index(name = "idx_pod_delivery", columnList = "delivery_id", unique = true)
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProofOfDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "delivery_id", unique = true, nullable = false)
    private Long deliveryId;

    @Column(nullable = false)
    private String imageUrl;

    private Double latitude;
    private Double longitude;

    private String signatureUrl;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(nullable = false)
    private LocalDateTime submittedAt;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
