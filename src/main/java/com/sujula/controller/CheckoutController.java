package com.sujula.controller;

import com.sujula.dto.request.checkout.CheckoutRequests;
import com.sujula.dto.response.checkout.CheckoutResponses;
import com.sujula.service.checkout.CheckoutService;
import com.sujula.service.idempotency.IdempotencyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Paying for a basket.
 *
 * <p>Signed in, because an order needs an owner: the refund, the status poll and
 * the retry all resolve through it, and a guest order with no account behind it
 * has nobody to resolve to. Browsing and filling a basket stay open to guests —
 * only the moment of paying requires an account.
 *
 * <p><strong>Idempotency is not optional here.</strong> A checkout retried on a
 * bad connection must not place two orders, and on a marketplace reached over
 * mobile networks that retry is a certainty rather than a risk. The difference
 * is a shopper being charged once or twice for a gift they are sending home.
 */
@RestController
@RequestMapping("/checkout")
@PreAuthorize("isAuthenticated()")
@Tag(name = "checkout", description = "Placing the order and paying for it")
public class CheckoutController {

    private static final String CHECKOUT = "checkout.place";
    private static final String RETRY = "checkout.retry-payment";

    private final CheckoutService checkout;
    private final AuthenticatedCaller caller;
    private final IdempotencyService idempotency;

    public CheckoutController(CheckoutService checkout, AuthenticatedCaller caller,
                              IdempotencyService idempotency) {
        this.checkout = checkout;
        this.caller = caller;
        this.idempotency = idempotency;
    }

    @PostMapping
    @Operation(summary = "Place the order",
               description = "Validates the held quote, reserves stock, creates the order and its "
                       + "per-vendor sub-orders, and opens a payment intent — in that order. The "
                       + "total is reconciled against the quote before anything is charged: if the "
                       + "price moved, nothing is taken and the buyer is asked to re-quote. Send an "
                       + "Idempotency-Key; a retried checkout must not place two orders.")
    public ResponseEntity<CheckoutResponses.Placed> checkout(
            Authentication authentication,
            @Parameter(description = "A unique value per attempt, so a retry is answered rather than repeated")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CheckoutRequests.Checkout request) {

        Long userId = caller.userId(authentication);

        CheckoutResponses.Placed placed = idempotency.execute(
                IdempotencyService.scopeFor(userId, CHECKOUT), idempotencyKey, request,
                HttpStatus.CREATED.value(), CheckoutResponses.Placed.class,
                () -> checkout.checkout(userId, request));

        return ResponseEntity.status(HttpStatus.CREATED).body(placed);
    }

    @PostMapping("/{orderId}/retry-payment")
    @Operation(summary = "A fresh payment intent after a failed attempt",
               description = "The order already exists and its stock is already reserved, so this "
                       + "does not recreate either. Only an unpaid order that still holds its "
                       + "reservation can be retried.")
    public ResponseEntity<CheckoutResponses.PaymentIntent> retryPayment(
            Authentication authentication,
            @PathVariable Long orderId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CheckoutRequests.RetryPayment request) {

        Long userId = caller.userId(authentication);

        CheckoutResponses.PaymentIntent intent = idempotency.execute(
                IdempotencyService.scopeFor(userId, RETRY + ":" + orderId), idempotencyKey, request,
                HttpStatus.OK.value(), CheckoutResponses.PaymentIntent.class,
                () -> checkout.retryPayment(userId, orderId, request));

        return ResponseEntity.ok(intent);
    }

    @GetMapping("/{orderId}/status")
    @Operation(summary = "Has the payment settled",
               description = "Settlement is whatever the webhook said, never what the client hopes. "
                       + "A browser returning from a hosted checkout page knows only that it came "
                       + "back — not that any money moved. Another buyer's order is reported as "
                       + "not found.")
    public ResponseEntity<CheckoutResponses.Status> status(Authentication authentication,
                                                          @PathVariable Long orderId) {
        return ResponseEntity.ok(checkout.status(caller.userId(authentication), orderId));
    }
}
