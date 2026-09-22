package com.sujula.dto.request.payment;

import com.sujula.model.constant.PaymentStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * A provider telling us what happened to a gateway payment.
 *
 * <p>Providers each have their own payload shape and signature scheme; the
 * adapter that receives the raw webhook is responsible for verifying it and
 * translating it into this normalised form before the service sees it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentCallbackRequest {

    /** The provider's reference, matched against {@code Payment.transactionId}. */
    @NotBlank
    @Size(max = 200)
    private String transactionId;

    @NotNull
    private PaymentStatus status;

    /** Amount the provider says it took; checked against the amount due when present. */
    private BigDecimal amount;

    /** Provider's reason for a failure, passed on to the buyer. */
    @Size(max = 500)
    private String failureReason;

    /** Raw provider payload, stored verbatim for audit. */
    private String rawPayload;
}
