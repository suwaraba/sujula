package com.sujula.dto.request.payment;

import com.sujula.model.constant.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Chooses how an order will be paid. Re-sending it with another method switches method. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InitiatePaymentRequest {

    @NotNull
    private PaymentMethod method;

    /**
     * Where the gateway should send the buyer back to after a hosted checkout.
     * Ignored by methods that have no gateway leg.
     */
    @Size(max = 500)
    private String returnUrl;

    @Size(max = 500)
    private String note;
}
