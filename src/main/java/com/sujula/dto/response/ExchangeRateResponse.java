package com.sujula.dto.response;

import com.sujula.model.ExchangeRate;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
public class ExchangeRateResponse {
    private Long id;
    private String fromCurrency;
    private String currency;
    private String country;
    private BigDecimal rate;
    private LocalDate rateDate;
    private LocalDateTime createdAt;

    public static ExchangeRateResponse from(ExchangeRate exchangeRate) {
        return ExchangeRateResponse.builder()
                .id(exchangeRate.getId())
                .fromCurrency(exchangeRate.getFromCurrency())
                .currency(exchangeRate.getCurrency())
                .country(exchangeRate.getCountry())
                .rate(exchangeRate.getRate())
                .rateDate(exchangeRate.getRateDate())
                .createdAt(exchangeRate.getCreatedAt())
                .build();
    }
}
