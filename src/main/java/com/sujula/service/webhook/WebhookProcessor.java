package com.sujula.service.webhook;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.model.constant.NotificationEvent;
import com.sujula.model.constant.OrderStatus;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.constant.WebhookStatus;
import com.sujula.model.order.Order;
import com.sujula.model.order.Payment;
import com.sujula.model.store.KycDocument;
import com.sujula.model.webhook.WebhookEvent;
import com.sujula.repository.PaymentRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.repository.store.KycDocumentRepository;
import com.sujula.repository.webhook.WebhookEventRepository;
import com.sujula.service.NotificationService;
import com.sujula.service.payment.PaymentSettlementService;
import com.sujula.service.payment.ProviderPaymentTransitionPolicy;
import com.sujula.service.payment.ProviderPaymentTransitionPolicy.Decision;
import com.sujula.service.reference.CurrencyCatalogue;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What a verified webhook actually does.
 *
 * <p>Kept apart from {@link WebhookIntake} because they answer different
 * questions. Intake asks "is this really from them"; this asks "what does it
 * mean". A handler that failed here has still had its event stored and verified,
 * which is why it can be tried again.
 *
 * <p>Two refusals run through the whole class and both are deliberate:
 *
 * <p><b>A provider cannot make an order paid for more than it costs.</b> The
 * amount on the event is checked against the payment, and a mismatch is a
 * failure rather than an update — an event that says a 108 EUR order was paid 1
 * EUR is either a bug or an attack, and crediting it is the same mistake either
 * way.
 *
 * <p><b>A provider cannot approve a seller.</b> An identity check coming back
 * clean moves a document to "checked" and tells a person; whether the store
 * opens is still somebody's decision, because the cost of being wrong is a
 * driver's goods in a stranger's hands.
 */
@Slf4j
@Component
public class WebhookProcessor {

    private final WebhookEventRepository events;
    private final PaymentRepository payments;
    private final OrderRepository orders;
    private final KycDocumentRepository kycDocuments;
    private final NotificationService notifications;
    private final ObjectMapper mapper;
    private final CurrencyCatalogue currencies;
    private final PaymentSettlementService settlements;

    public WebhookProcessor(WebhookEventRepository events, PaymentRepository payments,
                            OrderRepository orders,
                            KycDocumentRepository kycDocuments,
                            NotificationService notifications, ObjectMapper mapper,
                            CurrencyCatalogue currencies,
                            PaymentSettlementService settlements) {
        this.events = events;
        this.payments = payments;
        this.orders = orders;
        this.kycDocuments = kycDocuments;
        this.notifications = notifications;
        this.mapper = mapper;
        this.currencies = currencies;
        this.settlements = settlements;
    }

    @Transactional
    public void runOne(Long eventRowId) {
        WebhookEvent event = events.findByIdForUpdate(eventRowId).orElse(null);
        if (event == null || !event.isPending()) {
            return;
        }
        event.setAttempts(event.getAttempts() + 1);

        JsonNode body;
        try {
            body = mapper.readTree(event.getPayload());
        } catch (RuntimeException e) {
            finish(event, WebhookStatus.FAILED, null,
                    "The body is not JSON: " + e.getMessage());
            return;
        }

        switch (event.getKind()) {
            case PSP -> handlePayment(event, body);
            case MESSAGING -> handleMessaging(event, body);
            case KYC -> handleIdentity(event, body);
        }
    }

    // ── Money ────────────────────────────────────────────────────────────────

    private void handlePayment(WebhookEvent event, JsonNode body) {
        String type = lower(event.getEventType());
        boolean failure = false;
        if ("stripe".equalsIgnoreCase(event.getProvider())) {
            StripeEvents.Action action = StripeEvents.classify(type, body);
            if (action == StripeEvents.Action.AWAITING_FUNDS) {
                finish(event, WebhookStatus.IGNORED, null,
                        "Checkout completed while payment was still pending; awaiting the asynchronous result.");
                return;
            }
            if (action == StripeEvents.Action.REFUND_EVIDENCE) {
                finish(event, WebhookStatus.IGNORED, null,
                        "Stripe refund evidence was recorded; this callback has not moved money.");
                return;
            }
            if (action == StripeEvents.Action.IGNORE) {
                finish(event, WebhookStatus.IGNORED, null,
                        type == null
                                ? "Stripe event type is missing; no financial action was taken."
                                : "Unsupported Stripe event type " + event.getEventType()
                                        + "; no financial action was taken.");
                return;
            }
            failure = action == StripeEvents.Action.FAILURE;
            body = StripeEvents.flatten(body, mapper, currencies);
        } else {
            if (type == null) {
                finish(event, WebhookStatus.IGNORED, null,
                        "Payment event type is missing; no financial action was taken.");
                return;
            }
            switch (type) {
                case "payment.succeeded", "payment.paid", "payment.completed" -> failure = false;
                case "payment.failed", "payment.declined" -> failure = true;
                case "charge.refunded", "payment.refunded" -> {
                    finish(event, WebhookStatus.IGNORED, null,
                            "A refund event was recorded as evidence and has not moved anything.");
                    return;
                }
                default -> {
                    finish(event, WebhookStatus.IGNORED, null,
                            "Nothing on this platform acts on \"" + event.getEventType() + "\".");
                    return;
                }
            }
        }
        String reference = text(body, "reference", "transactionId", "transaction_id",
                "paymentReference", "payment_reference");
        String providerId = text(body, "id", "paymentId", "payment_id", "intentId", "intent_id");

        Long orderId = null;
        if (reference != null) {
            orderId = payments.findOrderIdByReference(reference).orElse(null);
        }
        if (orderId == null && providerId != null) {
            orderId = payments.findOrderIdByTransactionId(providerId).orElse(null);
        }
        if (orderId == null) {
            // Not a failure. Providers send events for things this platform
            // never created, and recording "not ours" stops somebody later
            // assuming a missing effect was a bug.
            finish(event, WebhookStatus.IGNORED, reference,
                    "No payment on this platform matches " + (reference == null ? providerId
                                                                                : reference));
            return;
        }

        Order order = orders.findByIdForPaymentUpdate(orderId).orElse(null);
        Payment payment = order == null ? null
                : payments.findByOrderIdForUpdate(orderId).orElse(null);
        if (payment == null || (reference != null && !reference.equals(payment.getReference())
                && (providerId == null || !providerId.equals(payment.getTransactionId())))) {
            finish(event, WebhookStatus.IGNORED, reference,
                    "The payment identity changed before it could be locked; no action was taken.");
            return;
        }

        if (order.getStatus() == OrderStatus.CANCELLED
                || order.getStatus() == OrderStatus.REFUNDED) {
            finish(event, WebhookStatus.IGNORED, payment.getReference(),
                    "Order " + order.getOrderNumber() + " is " + order.getStatus()
                            + "; the late provider event was retained without changing money state.");
            return;
        }

        if (failure) {
            applyFailure(event, payment, body);
            return;
        }

        BigDecimal amount = decimal(body, "amount", "amountPaid", "amount_paid", "value");
        String currency = text(body, "currency", "currencyCode", "currency_code");
        String mismatch = exactProviderPaymentFailure(payment, amount, currency);
        if (mismatch != null) {
            finish(event, WebhookStatus.FAILED, payment.getReference(), mismatch);
            return;
        }

        Decision decision = ProviderPaymentTransitionPolicy.decide(
                payment.getStatus(), PaymentStatus.PAID);
        if (decision == Decision.NO_OP) {
            // Already settled, by an earlier delivery of this event or by the
            // return from checkout. Not a failure and not a second credit.
            finish(event, WebhookStatus.PROCESSED, payment.getReference(),
                    "Already marked paid — nothing to do.");
            return;
        }
        if (decision == Decision.REJECT) {
            finish(event, WebhookStatus.IGNORED, payment.getReference(),
                    "A success event cannot reopen payment state " + payment.getStatus() + ".");
            return;
        }

        if (providerId != null && payment.getTransactionId() == null) {
            payment.setTransactionId(providerId);
        }
        settlements.settle(payment, null, providerId, null);
        payments.save(payment);

        finish(event, WebhookStatus.PROCESSED, payment.getReference(),
                "Applied the canonical paid settlement at the payment's amount and currency.");
    }

    /**
     * A success event must prove the exact stored contract. No absent values,
     * cross-currency equality, provider-side rounding or one-minor-unit
     * tolerance can create a paid transition.
     */
    private String exactProviderPaymentFailure(Payment payment, BigDecimal amount,
                                               String reportedCurrency) {
        String storedCurrency = payment.getCurrency();
        if (!currencies.isSupported(storedCurrency)) {
            return "The stored payment currency is missing or unsupported; nothing has been credited.";
        }
        String expectedCurrency = currencies.require(storedCurrency);
        BigDecimal expected = payment.getAmount();
        if (expected == null) {
            return "The stored payment amount is missing; nothing has been credited.";
        }
        BigDecimal canonicalExpected = currencies.round(expected, expectedCurrency);
        if (canonicalExpected.compareTo(expected) != 0) {
            return String.format(
                    "The stored payment amount %s is not expressible in %s; nothing has been credited.",
                    expected, expectedCurrency);
        }

        if (reportedCurrency == null || reportedCurrency.isBlank()) {
            return "The success event has no currency; nothing has been credited.";
        }
        if (!currencies.isSupported(reportedCurrency)) {
            return "The event currency " + reportedCurrency
                    + " is unsupported; nothing has been credited.";
        }
        String actualCurrency = currencies.require(reportedCurrency);
        if (!actualCurrency.equals(expectedCurrency)) {
            return String.format(
                    "The event is in %s and the payment is in %s. Currencies are not "
                            + "interchangeable here and nothing has been credited.",
                    actualCurrency, expectedCurrency);
        }

        if (amount == null) {
            return "The success event has no valid amount; nothing has been credited.";
        }
        BigDecimal canonicalAmount = currencies.round(amount, actualCurrency);
        if (canonicalAmount.compareTo(amount) != 0) {
            return String.format(
                    "The event amount %s is not expressible in %s; nothing has been credited.",
                    amount, actualCurrency);
        }
        if (canonicalAmount.compareTo(canonicalExpected) != 0) {
            // An event even one minor unit away from the contract is a bug or
            // an attack, and crediting it is the same mistake either way.
            return String.format(
                    "The event says %s and the payment is for %s. Not marking it paid — a "
                            + "provider cannot decide an order cost something else.",
                    canonicalAmount, canonicalExpected);
        }
        return null;
    }

    private void applyFailure(WebhookEvent event, Payment payment, JsonNode body) {
        String reason = text(body, "failureReason", "failure_reason", "message", "reason");
        Decision decision = ProviderPaymentTransitionPolicy.decide(
                payment.getStatus(), PaymentStatus.FAILED);
        if (decision == Decision.NO_OP) {
            finish(event, WebhookStatus.PROCESSED, payment.getReference(),
                    "Payment was already failed; no duplicate transition was applied.");
            return;
        }
        if (decision == Decision.REJECT) {
            // A failure event arriving after a success is out-of-order delivery,
            // which providers do. Un-paying an order on it would cancel goods
            // that are already being packed.
            finish(event, WebhookStatus.IGNORED, payment.getReference(),
                    "A failure event cannot regress payment state " + payment.getStatus() + ".");
            return;
        }
        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailureReason(reason == null ? "The provider reported a failure." : reason);
        payments.save(payment);
        payment.getOrder().setPaymentStatus(PaymentStatus.FAILED);
        orders.save(payment.getOrder());
        finish(event, WebhookStatus.PROCESSED, payment.getReference(),
                "Marked failed: " + payment.getFailureReason());
    }

    // ── Messages ─────────────────────────────────────────────────────────────

    private void handleMessaging(WebhookEvent event, JsonNode body) {
        String type = lower(event.getEventType());
        String recipient = text(body, "recipient", "to", "email", "destination", "address");

        if (type != null && (type.contains("bounce") || type.contains("fail")
                             || type.contains("undeliver"))) {
            // Worth acting on, and this is the one that matters here: the
            // recipient's release code reaches the buyer by email for them to
            // pass on. A bounce means the code did not arrive, and somebody in
            // Serrekunda is standing in front of a driver with nothing to read
            // out.
            log.warn("[Webhook] Message to {} bounced — anything time-sensitive sent there has "
                    + "not arrived", recipient == null ? "an unknown address" : recipient);
            finish(event, WebhookStatus.PROCESSED, recipient,
                    "Recorded a bounce. Anything time-sensitive sent to that address did not "
                            + "arrive — a release code that bounced is somebody unable to collect.");
            return;
        }
        if (type != null && type.contains("complaint")) {
            finish(event, WebhookStatus.PROCESSED, recipient,
                    "Recorded a complaint. Sending more marketing to that address is how a domain "
                            + "loses its reputation.");
            return;
        }
        finish(event, WebhookStatus.PROCESSED, recipient,
                "Delivery receipt recorded.");
    }

    // ── Identity ─────────────────────────────────────────────────────────────

    private void handleIdentity(WebhookEvent event, JsonNode body) {
        String reference = text(body, "reference", "documentId", "document_id", "externalId",
                "external_id");
        Long documentId = asLong(reference);

        KycDocument document = documentId == null ? null
                : kycDocuments.findById(documentId).orElse(null);
        if (document == null) {
            finish(event, WebhookStatus.IGNORED, reference,
                    "No document on this platform matches " + reference + ".");
            return;
        }

        String outcome = lower(text(body, "result", "status", "outcome", "decision"));
        boolean clean = outcome != null
                && (outcome.contains("pass") || outcome.contains("clear")
                    || outcome.contains("approve") || outcome.contains("success"));

        // The provider does not approve anybody. It says what it found; a person
        // decides whether the store opens, because the cost of being wrong is a
        // driver's goods in a stranger's hands.
        String note = clean
                ? "The identity provider found no problem with this document."
                : "The identity provider flagged this document: "
                  + (outcome == null ? "no result given" : outcome);

        if (document.getVendor() != null && document.getVendor().getUser() != null && !clean) {
            notifications.send(document.getVendor().getUser().getId(),
                    "Your document needs another look",
                    "The check on one of your documents did not come back clean. Somebody will "
                            + "review it and be in touch — you do not need to do anything yet.",
                    NotificationEvent.ACCOUNT_UPDATE, String.valueOf(document.getId()));
        }

        finish(event, WebhookStatus.PROCESSED, reference,
                note + " The document is unchanged and still waiting on a person: a provider does "
                        + "not decide whether a seller may trade.");
    }

    // ── Shared ───────────────────────────────────────────────────────────────

    private void finish(WebhookEvent event, WebhookStatus status, String subject, String outcome) {
        event.setStatus(status);
        event.setSubjectReference(subject);
        event.setOutcome(truncate(outcome, 500));
        event.setProcessedAt(LocalDateTime.now());
        if (status == WebhookStatus.FAILED) {
            event.setFailureReason(truncate(outcome, 2000));
        }
        events.save(event);
        log.info("[Webhook] {} {} from {}: {}", event.getKind(), event.getEventId(),
                event.getProvider(), status);
    }

    /**
     * Records a handler failure on its own transaction.
     *
     * <p>REQUIRES_NEW because the transaction that failed is rolling back, and a
     * reason written inside it would roll back too — leaving an event stuck in
     * RECEIVED with nothing saying why.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long eventRowId, String reason, int maxAttempts) {
        events.findByIdForUpdate(eventRowId).ifPresent(event -> {
            if (event.getStatus().isFinished()) {
                return;
            }
            int attempts = event.getAttempts() + 1;
            event.setAttempts(attempts);
            boolean giveUp = attempts >= maxAttempts;
            event.setStatus(giveUp ? WebhookStatus.FAILED : WebhookStatus.RETRYING);
            event.setFailureReason(truncate(reason, 2000));
            if (giveUp) {
                event.setProcessedAt(LocalDateTime.now());
            }
            events.save(event);
        });
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private String text(JsonNode root, String... names) {
        if (root == null) return null;
        for (String name : names) {
            JsonNode value = root.get(name);
            if (value != null && value.isValueNode() && !value.asString().isBlank()) {
                return value.asString();
            }
            for (String wrapper : List.of("data", "payload", "object")) {
                JsonNode nested = root.path(wrapper).get(name);
                if (nested != null && nested.isValueNode() && !nested.asString().isBlank()) {
                    return nested.asString();
                }
            }
        }
        return null;
    }

    private BigDecimal decimal(JsonNode root, String... names) {
        String found = text(root, names);
        if (found == null) return null;
        try {
            return new BigDecimal(found.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long asLong(String value) {
        if (value == null) return null;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
