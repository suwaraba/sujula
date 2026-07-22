package com.sujula.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "gift_cards",
        indexes = {
            @Index(name = "idx_gc_code",       columnList = "code",              unique = true),
            @Index(name = "idx_gc_purchaser",  columnList = "purchasedByUserId"),
            @Index(name = "idx_gc_redeemer",   columnList = "redeemedByUserId")
        })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GiftCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 25)
    private String code;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal initialAmount;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal remainingBalance;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "GMD";

    @Column(length = 200)
    private String issuedToEmail;

    private Long purchasedByUserId;

    private LocalDateTime expiresAt;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    private Long redeemedByUserId;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
