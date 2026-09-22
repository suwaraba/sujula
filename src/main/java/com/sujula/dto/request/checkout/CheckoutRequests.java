package com.sujula.dto.request.checkout;

import com.sujula.model.constant.PaymentMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** What checkout accepts. */
public final class CheckoutRequests {

    private CheckoutRequests() {}

    /**
     * Turns a held quote into an order and a payment intent.
     *
     * @param quoteId       the quote the buyer agreed to. Required: checkout
     *                      prices from a quote rather than from the live cart,
     *                      so that the figure agreed and the figure charged are
     *                      the same figure
     * @param addressId     the buyer's saved address for the parcel. The
     *                      delivery context on the quote says where it goes; this
     *                      says who to hand it to and on what street
     * @param paymentMethod how the buyer intends to pay. Offered from the payer's
     *                      country, never the recipient's
     */
    public record Checkout(
            @NotBlank @Size(max = 64) String quoteId,
            Long addressId,
            @NotNull PaymentMethod paymentMethod,
            @Size(max = 500) String notes) {}

    /**
     * Asks for a fresh payment intent on an order whose first attempt failed.
     *
     * <p>A separate operation from checkout because the order already exists and
     * its stock is already reserved. Recreating the order would double-reserve.
     */
    public record RetryPayment(
            @NotNull PaymentMethod paymentMethod) {}
}
