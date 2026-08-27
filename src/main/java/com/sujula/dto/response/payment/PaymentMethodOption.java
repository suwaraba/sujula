package com.sujula.dto.response.payment;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sujula.model.constant.PaymentChannel;
import com.sujula.model.constant.PaymentMethod;
import lombok.Builder;
import lombok.Data;

/**
 * One payment method as offered for a specific order.
 *
 * <p>Unavailable methods are returned too, with the reason, so a checkout page
 * can explain why cash on delivery is missing from a pickup-point order instead
 * of silently hiding it.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PaymentMethodOption {

    private PaymentMethod method;
    private String label;
    private PaymentChannel channel;

    private boolean available;

    /** Why this method cannot be used for this order; null when it can. */
    private String unavailableReason;

    /** True when choosing it sends the buyer to a gateway checkout. */
    private boolean requiresGateway;

    /** True when the money is handed over to a person at fulfilment time. */
    private boolean payLater;

    /** What the buyer will have to do — shown under the option on the checkout page. */
    private String description;
}
