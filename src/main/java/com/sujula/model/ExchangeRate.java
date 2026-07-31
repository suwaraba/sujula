package com.sujula.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "exchange_rates",
        indexes = {
                @Index(name = "idx_er_from_currency_rate_date", columnList = "fromCurrency, currency, rateDate", unique = true),
                @Index(name = "idx_er_country", columnList = "country")
        })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExchangeRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Base currency the rate converts from, e.g. "GMD"
    @Column(nullable = false, length = 3)
    private String fromCurrency;

    // Target currency the rate converts to, e.g. "USD"
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(length = 2)
    private String country;

    @Column(nullable = false, precision = 18, scale = 8)
    private BigDecimal rate;

    @Column(nullable = false)
    private LocalDate rateDate;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}

