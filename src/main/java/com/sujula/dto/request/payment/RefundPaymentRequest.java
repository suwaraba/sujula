package com.sujula.dto.request.payment;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Returns money to the buyer, in full or in part. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundPaymentRequest {

    /** Omitted means the whole refundable balance. */
    @DecimalMin(value = "0.01")
    private BigDecimal amount;

    @Size(max = 500)
    private String reason;
}
