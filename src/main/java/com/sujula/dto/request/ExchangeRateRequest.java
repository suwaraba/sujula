package com.sujula.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class ExchangeRateRequest {

    @NotBlank(message = "fromCurrency is required")
    @Size(min = 3, max = 3, message = "fromCurrency must be a 3-letter ISO currency code")
    private String fromCurrency;

    @NotBlank(message = "currency is required")
    @Size(min = 3, max = 3, message = "currency must be a 3-letter ISO currency code")
    private String currency;

    @Size(min = 2, max = 2, message = "country must be a 2-letter ISO country code")
    private String country;

    @NotNull(message = "rate is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "rate must be greater than 0")
    private BigDecimal rate;

    @NotNull(message = "rateDate is required")
    private LocalDate rateDate;
}
