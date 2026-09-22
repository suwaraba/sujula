package com.sujula.dto.request.payment;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Records money that arrived outside a gateway: a bank transfer matched by
 * finance, or cash taken at a handover.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmPaymentRequest {

    /**
     * Amount actually received. Optional — omitted means "the full amount due".
     * A short payment is rejected rather than silently accepted, so this is
     * only worth sending when the collector counted the money.
     */
    private BigDecimal amountReceived;

    /** Bank slip number, teller reference or receipt number — the evidence trail. */
    @Size(max = 200)
    private String collectionReference;

    @Size(max = 500)
    private String note;
}
