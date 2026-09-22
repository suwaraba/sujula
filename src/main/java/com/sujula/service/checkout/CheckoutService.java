package com.sujula.service.checkout;

import com.sujula.dto.request.checkout.CheckoutRequests;
import com.sujula.dto.response.checkout.CheckoutResponses;

/**
 * Turning a held quote into an order, reserved stock and a payment intent.
 *
 * <p>The order of operations is the whole design, and it is deliberate:
 * validate, then reserve, then create, then intend. Reserving before validating
 * holds stock for a checkout that was never going to complete; creating before
 * reserving produces orders nobody can fulfil; opening a payment intent before
 * the order exists leaves money arriving for nothing.
 *
 * <p>Idempotent, because a checkout retried on a bad connection must not place
 * two orders. That is not a nicety on a marketplace reached over mobile
 * networks — it is the difference between a shopper being charged once and
 * twice for a gift they are sending home.
 */
public interface CheckoutService {

    /**
     * Places the order.
     *
     * @param userId the buyer. Required — an order has an owner, and the
     *               refund, the status poll and the retry all resolve through it
     */
    CheckoutResponses.Placed checkout(Long userId, CheckoutRequests.Checkout request);

    /**
     * A fresh payment intent for an order whose first attempt failed.
     *
     * <p>The order already exists and its stock is already reserved, so this
     * deliberately does not recreate either. Only an order that is still unpaid
     * and still holds its reservation can be retried.
     */
    CheckoutResponses.PaymentIntent retryPayment(Long userId, Long orderId,
                                                 CheckoutRequests.RetryPayment request);

    /**
     * What a client polls while the gateway makes up its mind.
     *
     * <p>Settlement is whatever the webhook said, never what the client hopes.
     * A browser returning from a hosted checkout page knows only that it came
     * back — not that any money moved.
     */
    CheckoutResponses.Status status(Long userId, Long orderId);
}
