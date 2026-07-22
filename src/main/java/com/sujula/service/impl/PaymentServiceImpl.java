package com.sujula.service.impl;

import com.sujula.exception.BadRequestException;
import com.sujula.exception.ResourceNotFoundException;
import com.sujula.model.Order;
import com.sujula.model.Payment;
import com.sujula.model.enums.OrderStatus;
import com.sujula.model.enums.PaymentStatus;
import com.sujula.payment.StripePaymentAdapter;
import com.sujula.repository.OrderRepository;
import com.sujula.repository.PaymentRepository;
import com.sujula.service.EmailService;
import com.sujula.service.NotificationService;
import com.sujula.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * Gateway-agnostic payment implementation.
 *
 * COD flow:
 *   1. Buyer places order → initiate("COD") → Payment(PENDING)
 *   2. Driver delivers    → confirmCod()    → Payment(PAID), Order(DELIVERED)
 *
 * Gateway flow (Wave / Orange / future):
 *   1. Frontend calls initiate(method) → gatewayResponse contains redirect URL
 *   2. Provider posts webhook → recordWebhook(txId, PAID) → Order advances to CONFIRMED
 *
 * The service purposely contains no gateway-specific HTTP calls; those belong
 * in dedicated adapter classes (e.g. WavePaymentAdapter) injected by Spring
 * when the relevant profile is active.  This keeps the core logic testable
 * without live credentials.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentServiceImpl implements PaymentService {

    private static final Set<String> VALID_METHODS =
            Set.of("COD", "WAVE", "ORANGE", "STRIPE", "PAYPAL", "BANK_TRANSFER");

    private final PaymentRepository   paymentRepository;
    private final OrderRepository     orderRepository;
    private final EmailService        emailService;
    private final NotificationService notificationService;
    private final StripePaymentAdapter stripeAdapter;

    // ── Initiate ──────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public Payment initiate(Long orderId, String method, String currency) {

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));

        if (order.getStatus() == OrderStatus.CANCELLED
                || order.getStatus() == OrderStatus.REFUNDED) {
            throw new BadRequestException(
                    "Cannot initiate payment for an order in status: " + order.getStatus());
        }

        String normalised = (method != null) ? method.toUpperCase().trim() : "COD";
        if (!VALID_METHODS.contains(normalised)) {
            throw new BadRequestException(
                    "Unsupported payment method: " + method
                    + ". Accepted: " + String.join(", ", VALID_METHODS));
        }

        // Return existing PENDING payment if one already exists (idempotent)
        return paymentRepository.findByOrderId(orderId).orElseGet(() -> {
            BigDecimal amount = order.getTotal();

            // IMPORTANT: the order's own currency is the authoritative value.
            // It was set at checkout time to match exactly what the customer saw.
            // The `currency` parameter is only a last-resort fallback for edge cases
            // (e.g., legacy orders that have no currency set).
            // Using order.getCurrency() guarantees the Stripe charge amount and
            // currency are always consistent with the displayed order total.
            String effectiveCcy = (order.getCurrency() != null && !order.getCurrency().isBlank())
                    ? order.getCurrency().toUpperCase()
                    : (currency != null && !currency.isBlank() ? currency.toUpperCase() : "GMD");

            String clientSecret    = null;
            String gatewayResponse = null;
            String transactionId   = null;
            if (!"COD".equals(normalised)) {
                switch (normalised) {
                    case "STRIPE" -> {
                        StripePaymentAdapter.CreateResult result =
                                stripeAdapter.createPaymentIntent(order, effectiveCcy);
                        // transactionId  = PaymentIntent id   → used to look up on webhook
                        // clientSecret   = PI client_secret   → returned to frontend for Stripe.js
                        // gatewayResponse is intentionally left null here; it will be filled
                        // only when the webhook fires (so it never overwrites clientSecret).
                        transactionId = result.paymentIntentId();
                        clientSecret  = result.clientSecret();
                    }
                    default -> {
                        // Wave / Orange / other gateways — not yet integrated.
                        // Do NOT put a placeholder in clientSecret — that would be passed to
                        // Stripe.js and cause a crash.  Log a warning instead.
                        log.warn("[Payment] Gateway '{}' is not yet integrated. "
                                + "No client_secret will be set.", normalised);
                    }
                }
            }

            Payment payment = Payment.builder()
                    .order(order)
                    .status(PaymentStatus.PENDING)
                    .amount(amount)
                    .currency(effectiveCcy)
                    .paymentMethod(normalised)
                    .transactionId(transactionId)
                    .clientSecret(clientSecret)
                    .gatewayResponse(gatewayResponse)
                    .build();

            return paymentRepository.save(payment);
        });
    }

    // ── Webhook ───────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public Payment recordWebhook(String transactionId, PaymentStatus newStatus,
                                 String gatewayPayload) {
        if (transactionId == null || transactionId.isBlank()) {
            throw new BadRequestException("transactionId must not be blank");
        }

        Payment payment = paymentRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Payment not found for transaction: " + transactionId));

        // Guard against replays / duplicate webhooks
        if (payment.getStatus() == newStatus) {
            log.info("[Payment] Duplicate webhook ignored for txId={} status={}", transactionId, newStatus);
            return payment;
        }
        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            throw new BadRequestException("Cannot update a REFUNDED payment");
        }

        payment.setStatus(newStatus);
        // Store the raw webhook payload in gatewayResponse for audit purposes.
        // clientSecret is intentionally NOT touched here — it must survive
        // webhook processing so the frontend can retry if needed.
        payment.setGatewayResponse(gatewayPayload);

        if (newStatus == PaymentStatus.PAID) {
            payment.setPaidAt(LocalDateTime.now());
            advanceOrderOnPayment(payment.getOrder());
        }

        Payment saved = paymentRepository.save(payment);
        log.info("[Payment] Webhook processed: txId={} → status={}", transactionId, newStatus);
        return saved;
    }

    // ── COD confirmation (called by driver after delivery) ────────────────────

    @Override
    @Transactional
    public Payment confirmCod(Long orderId) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No payment record found for order: " + orderId));

        if (!"COD".equalsIgnoreCase(payment.getPaymentMethod())) {
            throw new BadRequestException(
                    "confirmCod is only valid for COD payments. Method: " + payment.getPaymentMethod());
        }
        if (payment.getStatus() == PaymentStatus.PAID) {
            return payment; // idempotent
        }
        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new BadRequestException(
                    "COD payment cannot be confirmed in status: " + payment.getStatus());
        }

        payment.setStatus(PaymentStatus.PAID);
        payment.setPaidAt(LocalDateTime.now());
        Payment saved = paymentRepository.save(payment);
        // Advance the order and send confirmation email (same path as Stripe webhook)
        advanceOrderOnPayment(payment.getOrder());
        return saved;
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Payment findByOrderId(Long orderId) {
        return paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No payment record found for order: " + orderId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Payment> findAll(Pageable pageable) {
        return paymentRepository.findAll(pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Payment> findByStatus(PaymentStatus status, Pageable pageable) {
        return paymentRepository.findByStatus(status, pageable);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * After a payment is confirmed, advance the order to CONFIRMED, send the
     * customer confirmation email, and fire an in-app notification.
     *
     * Called from both the Stripe webhook path ({@link #recordWebhook}) and the
     * COD confirmation path ({@link #confirmCod}).
     */
    private void advanceOrderOnPayment(Order order) {
        if (order == null) return;
        // Only advance from PENDING — don't interfere with admin-managed statuses
        if (order.getStatus() == OrderStatus.PENDING) {
            order.setStatus(OrderStatus.CONFIRMED);
            orderRepository.save(order);

            // Send confirmation email NOW that payment is verified (best-effort)
            try {
                String email = order.getContactEmail();
                String name  = order.getDisplayName();
                if (email != null && !email.isBlank()) {
                    emailService.sendOrderConfirmationEmail(email, name, order.getOrderNumber());
                }
            } catch (Exception ex) {
                log.warn("[Payment] Failed to send order confirmation email for {}: {}",
                        order.getOrderNumber(), ex.getMessage());
            }

            // In-app notification (authenticated customers only)
            try {
                if (order.getCustomer() != null) {
                    notificationService.send(
                            order.getCustomer().getId(),
                            "Payment Confirmed",
                            "Your payment for order " + order.getOrderNumber()
                                    + " has been received. Your order is now being processed.",
                            "ORDER", order.getOrderNumber());
                }
            } catch (Exception ex) {
                log.warn("[Payment] Failed to send payment confirmation notification: {}", ex.getMessage());
            }
        }
    }
}
