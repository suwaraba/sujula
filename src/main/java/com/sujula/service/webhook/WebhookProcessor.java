package com.sujula.service.webhook;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.model.constant.NotificationEvent;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.constant.WebhookStatus;
import com.sujula.model.order.Payment;
import com.sujula.model.store.KycDocument;
import com.sujula.model.webhook.WebhookEvent;
import com.sujula.repository.PaymentRepository;
import com.sujula.repository.store.KycDocumentRepository;
import com.sujula.repository.webhook.WebhookEventRepository;
import com.sujula.service.NotificationService;
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
    private final KycDocumentRepository kycDocuments;
    private final NotificationService notifications;
    private final ObjectMapper mapper;
    private final CurrencyCatalogue currencies;

    public WebhookProcessor(WebhookEventRepository events, PaymentRepository payments,
                            KycDocumentRepository kycDocuments,
                            NotificationService notifications, ObjectMapper mapper,
                            CurrencyCatalogue currencies) {
        this.events = events;
        this.payments = payments;
        this.kycDocuments = kycDocuments;
        this.notifications = notifications;
        this.mapper = mapper;
        this.currencies = currencies;
    }

    @Transactional
    public void runOne(Long eventRowId) {
        WebhookEvent event = events.findById(eventRowId).orElse(null);
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
        if ("stripe".equalsIgnoreCase(event.getProvider())) {
            if (StripeEvents.awaitingFunds(type, body)) {
                finish(event, WebhookStatus.IGNORED, null,
                        "Checkout completed with the payment still pending. The money arrives as "
                                + "checkout.session.async_payment_succeeded, which is what settles it.");
                return;
            }
            body = StripeEvents.flatten(body, mapper, currencies);
        }
        String reference = text(body, "reference", "transactionId", "transaction_id",
                "paymentReference", "payment_reference");
        String providerId = text(body, "id", "paymentId", "payment_id", "intentId", "intent_id");

        Payment payment = null;
        if (reference != null) {
            payment = payments.findByReference(reference).orElse(null);
        }
        if (payment == null && providerId != null) {
            payment = payments.findByTransactionId(providerId).orElse(null);
        }
        if (payment == null) {
            // Not a failure. Providers send events for things this platform
            // never created, and recording "not ours" stops somebody later
            // assuming a missing effect was a bug.
            finish(event, WebhookStatus.IGNORED, reference,
                    "No payment on this platform matches " + (reference == null ? providerId
                                                                                : reference));
            return;
        }

        if (type != null && (type.contains("fail") || type.contains("declin"))) {
            markFailed(event, payment, body);
            return;
        }
        if (type != null && (type.contains("refund") || type.contains("charge.refund"))) {
            // Refunds are decided here, not announced to us. A provider-initiated
            // refund is recorded and flagged rather than applied: the platform's
            // own refund path writes the ledger rows, and a webhook that moved
            // money would bypass every one of them (C3).
            finish(event, WebhookStatus.IGNORED, payment.getReference(),
                    "A refund event was received. Refunds are recorded through the platform's own "
                            + "refund path so the ledger rows are written — this event is kept as "
                            + "evidence and has not moved anything.");
            return;
        }
        if (type != null && !(type.contains("succe") || type.contains("paid")
                              || type.contains("complet"))) {
            finish(event, WebhookStatus.IGNORED, payment.getReference(),
                    "Nothing on this platform acts on \"" + event.getEventType() + "\".");
            return;
        }

        if (payment.isPaid()) {
            // Already settled, by an earlier delivery of this event or by the
            // return from checkout. Not a failure and not a second credit.
            finish(event, WebhookStatus.PROCESSED, payment.getReference(),
                    "Already marked paid — nothing to do.");
            return;
        }

        BigDecimal amount = decimal(body, "amount", "amountPaid", "amount_paid", "value");
        String currency = text(body, "currency", "currencyCode", "currency_code");

        if (amount != null && payment.getAmount() != null
                && amount.compareTo(payment.getAmount()) != 0) {
            // An event that says a 108 EUR order was paid 1 EUR is a bug or an
            // attack, and crediting it is the same mistake either way.
            finish(event, WebhookStatus.FAILED, payment.getReference(), String.format(
                    "The event says %s and the payment is for %s. Not marking it paid — a "
                            + "provider cannot decide an order cost something else.",
                    amount, payment.getAmount()));
            return;
        }
        if (currency != null && payment.getCurrency() != null
                && !currency.equalsIgnoreCase(payment.getCurrency())) {
            finish(event, WebhookStatus.FAILED, payment.getReference(), String.format(
                    "The event is in %s and the payment is in %s. Currencies are not "
                            + "interchangeable here and nothing has been credited.",
                    currency, payment.getCurrency()));
            return;
        }

        payment.setStatus(PaymentStatus.PAID);
        payment.setPaidAt(LocalDateTime.now());
        if (providerId != null && payment.getTransactionId() == null) {
            payment.setTransactionId(providerId);
        }
        payments.save(payment);

        if (payment.getOrder() != null) {
            payment.getOrder().setPaymentStatus(PaymentStatus.PAID);
            if (payment.getOrder().getPaidAt() == null) {
                payment.getOrder().setPaidAt(LocalDateTime.now());
            }
            if (payment.getOrder().getCustomer() != null) {
                notifications.send(payment.getOrder().getCustomer().getId(),
                        "Payment received",
                        "We have your payment for " + payment.getOrder().getOrderNumber()
                                + ". The sellers have been told to start packing.",
                        NotificationEvent.ORDER_UPDATE, payment.getOrder().getOrderNumber());
            }
        }

        finish(event, WebhookStatus.PROCESSED, payment.getReference(),
                "Marked paid, at the amount and currency the payment was created for.");
    }

    private void markFailed(WebhookEvent event, Payment payment, JsonNode body) {
        String reason = text(body, "failureReason", "failure_reason", "message", "reason");
        if (payment.isPaid()) {
            // A failure event arriving after a success is out-of-order delivery,
            // which providers do. Un-paying an order on it would cancel goods
            // that are already being packed.
            finish(event, WebhookStatus.IGNORED, payment.getReference(),
                    "A failure event arrived for a payment that is already settled — events can "
                            + "arrive out of order, and nothing has been undone.");
            return;
        }
        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailureReason(reason == null ? "The provider reported a failure." : reason);
        payments.save(payment);
        if (payment.getOrder() != null) {
            payment.getOrder().setPaymentStatus(PaymentStatus.FAILED);
        }
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
        events.findById(eventRowId).ifPresent(event -> {
            boolean giveUp = event.getAttempts() >= maxAttempts;
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
