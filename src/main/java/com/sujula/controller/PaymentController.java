package com.sujula.controller;

import com.sujula.dto.request.payment.ConfirmPaymentRequest;
import com.sujula.dto.request.payment.InitiatePaymentRequest;
import com.sujula.dto.request.payment.PaymentCallbackRequest;
import com.sujula.dto.request.payment.RefundPaymentRequest;
import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.payment.PaymentMethodOption;
import com.sujula.dto.response.payment.PaymentResponse;
import com.sujula.exceptions.BadRequestException;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.user.User;
import com.sujula.service.PaymentService;
import com.sujula.service.payment.PaymentProperties;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Payment endpoints, grouped by who is calling: the buyer paying, the provider
 * confirming, the staff member taking the money in person, and the admin
 * reconciling.
 */
@RestController
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentProperties properties;

    public PaymentController(PaymentService paymentService, PaymentProperties properties) {
        this.paymentService = paymentService;
        this.properties = properties;
    }

    // ── Buyer (authenticated) ────────────────────────────────────────────────

    @GetMapping("/api/user/orders/{orderId}/payment/methods")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<PaymentMethodOption>> methods(Authentication authentication,
                                                             @PathVariable Long orderId) {
        return ResponseEntity.ok(paymentService.availableMethods(orderId, currentUserId(authentication)));
    }

    @PostMapping("/api/user/orders/{orderId}/payment")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PaymentResponse> pay(Authentication authentication,
                                               @PathVariable Long orderId,
                                               @Valid @RequestBody InitiatePaymentRequest request) {
        PaymentResponse payment = paymentService.initiate(orderId, currentUserId(authentication), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(payment);
    }

    @GetMapping("/api/user/orders/{orderId}/payment")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PaymentResponse> myPayment(Authentication authentication, @PathVariable Long orderId) {
        return ResponseEntity.ok(paymentService.findForOrder(orderId, currentUserId(authentication)));
    }

    // ── Buyer (guest) ────────────────────────────────────────────────────────
    // Order number plus the checkout email, never one without the other, so a
    // guest order cannot be found by guessing order numbers.

    @GetMapping("/api/guest/orders/{orderNumber}/payment/methods")
    public ResponseEntity<List<PaymentMethodOption>> guestMethods(@PathVariable String orderNumber,
                                                                  @RequestParam String email) {
        return ResponseEntity.ok(paymentService.availableMethodsForGuest(orderNumber, email));
    }

    @PostMapping("/api/guest/orders/{orderNumber}/payment")
    public ResponseEntity<PaymentResponse> guestPay(@PathVariable String orderNumber,
                                                    @RequestParam String email,
                                                    @Valid @RequestBody InitiatePaymentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(paymentService.initiateForGuest(orderNumber, email, request));
    }

    @GetMapping("/api/guest/orders/{orderNumber}/payment")
    public ResponseEntity<PaymentResponse> guestPayment(@PathVariable String orderNumber,
                                                        @RequestParam String email) {
        return ResponseEntity.ok(paymentService.findForGuest(orderNumber, email));
    }

    // ── Provider callback ────────────────────────────────────────────────────

    /**
     * Applies a provider's result to the payment it names.
     *
     * <p>Authenticated by a shared secret rather than a session, since the
     * caller is a machine. Providers whose payloads carry their own signature
     * should be terminated in a provider-specific adapter that verifies it and
     * calls {@code PaymentService.handleCallback} directly; this endpoint is the
     * generic path for providers without one.
     */
    @PostMapping("/api/payments/callback")
    public ResponseEntity<PaymentResponse> callback(
            @RequestHeader(value = "X-Sujula-Signature", required = false) String signature,
            @Valid @RequestBody PaymentCallbackRequest request) {
        requireCallbackSecret(signature);
        return ResponseEntity.ok(paymentService.handleCallback(request));
    }

    // ── In-person collection (driver, pickup operator, store) ────────────────

    /**
     * Records money taken at handover. Role-gated rather than owner-gated: the
     * collector is staff, not the buyer.
     */
    @PostMapping("/api/staff/orders/{orderId}/payment/collect")
    @PreAuthorize("hasAnyRole('ADMIN','DELIVERY','PICKUP_OPERATOR','VENDOR')")
    public ResponseEntity<PaymentResponse> collect(Authentication authentication,
                                                   @PathVariable Long orderId,
                                                   @RequestBody(required = false) ConfirmPaymentRequest request) {
        return ResponseEntity.ok(
                paymentService.collectInPerson(orderId, request, currentUserId(authentication)));
    }

    // ── Admin ────────────────────────────────────────────────────────────────

    @PostMapping("/api/admin/orders/{orderId}/payment/confirm-transfer")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PaymentResponse> confirmTransfer(Authentication authentication,
                                                           @PathVariable Long orderId,
                                                           @RequestBody(required = false) ConfirmPaymentRequest request) {
        return ResponseEntity.ok(
                paymentService.confirmTransfer(orderId, request, currentUserId(authentication)));
    }

    @PostMapping("/api/admin/orders/{orderId}/payment/refund")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PaymentResponse> refund(Authentication authentication,
                                                  @PathVariable Long orderId,
                                                  @Valid @RequestBody(required = false) RefundPaymentRequest request) {
        return ResponseEntity.ok(paymentService.refund(orderId, request, currentUserId(authentication)));
    }

    @PostMapping("/api/admin/orders/{orderId}/payment/cancel")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PaymentResponse> cancel(@PathVariable Long orderId,
                                                  @RequestParam(required = false) String reason) {
        return ResponseEntity.ok(paymentService.cancel(orderId, reason));
    }

    @PostMapping("/api/admin/orders/{orderId}/payment/fail")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PaymentResponse> fail(@PathVariable Long orderId,
                                                @RequestParam(required = false) String reason) {
        return ResponseEntity.ok(paymentService.markFailed(orderId, reason));
    }

    @GetMapping("/api/admin/orders/{orderId}/payment")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PaymentResponse> adminPayment(@PathVariable Long orderId) {
        return ResponseEntity.ok(paymentService.findForOrder(orderId, null));
    }

    @GetMapping("/api/admin/payments")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PagedResponse<PaymentResponse>> findAll(
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(PagedResponse.of(status == null
                ? paymentService.findAll(pageable)
                : paymentService.findByStatus(status, pageable)));
    }

    @GetMapping("/api/admin/payments/vendor/{vendorId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PagedResponse<PaymentResponse>> findByVendor(
            @PathVariable Long vendorId,
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PagedResponse.of(
                paymentService.findByVendor(vendorId, status, PageRequest.of(page, size))));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * A callback that can mark orders paid is refused outright unless a secret
     * is configured — an open endpoint here would let anyone settle any order.
     */
    private void requireCallbackSecret(String presented) {
        String expected = properties.getCallbackSecret();
        if (expected == null || expected.isBlank()) {
            throw new BadRequestException(
                    "Payment callbacks are disabled: set sujula.payment.callback-secret to enable them");
        }
        if (presented == null || !MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
            throw new AccessDeniedException("Invalid payment callback signature");
        }
    }

    private Long currentUserId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof User user && user.getId() != null) {
            return user.getId();
        }
        throw new AccessDeniedException("Authentication is required");
    }
}
