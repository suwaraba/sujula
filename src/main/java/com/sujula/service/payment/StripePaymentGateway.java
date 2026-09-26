package com.sujula.service.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.sujula.exceptions.BadRequestException;
import com.sujula.model.constant.PaymentMethod;
import com.sujula.model.order.Payment;
import com.sujula.service.reference.CurrencyCatalogue;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Card payments through Stripe Checkout.
 *
 * <p>Hosted rather than inline: the buyer is sent to a Stripe page and card
 * numbers never touch this platform, which keeps it out of PCI scope. The
 * payment is settled by the {@code checkout.session.completed} webhook on
 * {@code /webhooks/psp/stripe}, never by the buyer arriving back on the success
 * URL — a redirect is something anybody can type.
 *
 * <p>The buyer is charged in the payment's currency, which is their display
 * currency (C2). The amount goes to Stripe in minor units at the scale
 * {@link CurrencyCatalogue} gives that currency.
 *
 * <p>Registered only while the mock gateway is off. With both, which of the two
 * took a card payment would depend on bean order.
 */
@Slf4j
@Component
@ConditionalOnExpression("'${sujula.payment.stripe.secret-key:}'.length() > 0 "
        + "and '${sujula.payment.mock.enabled:false}' != 'true'")
public class StripePaymentGateway implements PaymentGateway {

    /** Stripe's own bounds on {@code expires_at}. */
    private static final Duration MIN_SESSION = Duration.ofMinutes(30);
    private static final Duration MAX_SESSION = Duration.ofHours(24);

    private final StripeProperties stripe;
    private final PaymentProperties payments;
    private final CurrencyCatalogue currencies;
    private final ObjectMapper mapper;
    private final String frontendUrl;
    private final RestClient http;

    public StripePaymentGateway(StripeProperties stripe, PaymentProperties payments,
                                CurrencyCatalogue currencies, ObjectMapper mapper,
                                @Value("${app.frontend.url:}") String frontendUrl) {
        this.stripe = stripe;
        this.payments = payments;
        this.currencies = currencies;
        this.mapper = mapper;
        this.frontendUrl = frontendUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.http = RestClient.builder()
                .baseUrl(stripe.getApiBase())
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + stripe.getSecretKey())
                .build();
        log.info("[Payment] Stripe configured in {} mode",
                stripe.getSecretKey().startsWith("sk_live_") ? "LIVE" : "test");
    }

    @Override
    public boolean supports(PaymentMethod method) {
        return method == PaymentMethod.CARD;
    }

    @Override
    public String name() {
        return "stripe";
    }

    @Override
    public GatewayCheckout createCheckout(Payment payment, String returnUrl) {
        String success = firstNonBlank(returnUrl, stripe.getSuccessUrl(), frontendUrl);
        if (success == null) {
            throw new BadRequestException("Card payments need somewhere to send the buyer back to. "
                    + "Set sujula.payment.stripe.success-url or app.frontend.url.");
        }
        String cancel = firstNonBlank(stripe.getCancelUrl(), success);
        String orderNumber = payment.getOrder() != null ? payment.getOrder().getOrderNumber()
                                                        : payment.getReference();
        Duration ttl = Duration.ofMinutes(payments.getCheckoutTtlMinutes());
        if (ttl.compareTo(MIN_SESSION) < 0) ttl = MIN_SESSION;
        if (ttl.compareTo(MAX_SESSION) > 0) ttl = MAX_SESSION;

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("mode", "payment");
        form.add("success_url", success);
        form.add("cancel_url", cancel);
        form.add("client_reference_id", payment.getReference());
        form.add("metadata[reference]", payment.getReference());
        form.add("payment_intent_data[metadata][reference]", payment.getReference());
        form.add("expires_at", String.valueOf(Instant.now().plus(ttl).getEpochSecond()));
        form.add("line_items[0][quantity]", "1");
        form.add("line_items[0][price_data][currency]", payment.getCurrency().toLowerCase(Locale.ROOT));
        form.add("line_items[0][price_data][unit_amount]",
                toMinor(payment.getAmount(), payment.getCurrency()).toPlainString());
        form.add("line_items[0][price_data][product_data][name]", "Sujula order " + orderNumber);
        if (payment.getOrder() != null && payment.getOrder().getCustomer() != null
                && payment.getOrder().getCustomer().getEmail() != null) {
            form.add("customer_email", payment.getOrder().getCustomer().getEmail());
        }

        // A checkout reopened for the same payment closes the one before it, so
        // a buyer holding two tabs cannot pay twice. No idempotency key: the new
        // session carries a new expires_at, and Stripe refuses a reused key
        // whose parameters differ.
        expirePrevious(payment);
        JsonNode session = post("/v1/checkout/sessions", form, null);
        log.info("[Payment] Stripe checkout {} opened for {} ({} {})", session.path("id").asString(),
                payment.getReference(), payment.getAmount(), payment.getCurrency());
        return new GatewayCheckout(session.path("id").asString(), session.path("url").asString(),
                null, session.toString());
    }

    @Override
    public GatewayRefund refund(Payment payment, BigDecimal amount, String reason) {
        String intent = paymentIntentOf(payment);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("payment_intent", intent);
        form.add("amount", toMinor(amount, payment.getCurrency()).toPlainString());
        form.add("reason", "requested_by_customer");
        form.add("metadata[reference]", payment.getReference());
        if (reason != null && !reason.isBlank()) {
            form.add("metadata[note]", reason.length() > 450 ? reason.substring(0, 450) : reason);
        }
        // Keyed on what had been refunded before this one, so a retry of the same
        // refund is the same request to Stripe and a second, later refund is not.
        String key = "refund-" + payment.getReference() + "-"
                + (payment.getAmountRefunded() == null ? "0" : payment.getAmountRefunded().toPlainString())
                + "-" + amount.toPlainString();
        JsonNode refund = post("/v1/refunds", form, key);
        return new GatewayRefund(refund.path("id").asString(),
                fromMinor(refund.path("amount").asLong(), payment.getCurrency()), refund.toString());
    }

    private void expirePrevious(Payment payment) {
        String previous = payment.getTransactionId();
        if (previous == null || !previous.startsWith("cs_")) {
            return;
        }
        try {
            post("/v1/checkout/sessions/" + previous + "/expire", new LinkedMultiValueMap<>(), null);
        } catch (RuntimeException alreadyClosed) {
            // Already expired or completed; either way it cannot be paid again.
            log.debug("[Payment] Previous Stripe checkout {} not expired: {}", previous,
                    alreadyClosed.getMessage());
        }
    }

    private String paymentIntentOf(Payment payment) {
        String id = payment.getTransactionId();
        if (id != null && id.startsWith("pi_")) {
            return id;
        }
        if (id == null || !id.startsWith("cs_")) {
            throw new BadRequestException("Payment " + payment.getReference()
                    + " has no Stripe checkout to refund against.");
        }
        JsonNode session = get("/v1/checkout/sessions/" + id);
        String intent = session.path("payment_intent").asString();
        if (intent == null || intent.isBlank()) {
            throw new BadRequestException("Stripe has no charge for " + payment.getReference()
                    + " — it was never paid, so there is nothing to refund.");
        }
        return intent;
    }

    BigDecimal toMinor(BigDecimal amount, String currency) {
        int scale = currencies.minorUnits(currency);
        return currencies.round(amount, currency).movePointRight(scale).setScale(0, RoundingMode.UNNECESSARY);
    }

    BigDecimal fromMinor(long minor, String currency) {
        return BigDecimal.valueOf(minor).movePointLeft(currencies.minorUnits(currency));
    }

    private JsonNode post(String path, MultiValueMap<String, String> form, String idempotencyKey) {
        try {
            RestClient.RequestBodySpec request = http.post().uri(path);
            if (idempotencyKey != null) {
                request = request.header("Idempotency-Key", idempotencyKey);
            }
            String body = request
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            return mapper.readTree(body);
        } catch (RestClientResponseException refused) {
            throw stripeError(refused);
        }
    }

    private JsonNode get(String path) {
        try {
            return mapper.readTree(http.get().uri(path).retrieve().body(String.class));
        } catch (RestClientResponseException refused) {
            throw stripeError(refused);
        }
    }

    private RuntimeException stripeError(RestClientResponseException refused) {
        String message = refused.getStatusText();
        try {
            message = mapper.readTree(refused.getResponseBodyAsString())
                    .path("error").path("message").asString(message);
        } catch (RuntimeException ignored) {
            // Not JSON; keep the status text.
        }
        log.warn("[Payment] Stripe refused a request ({}): {}", refused.getStatusCode(), message);
        return new BadRequestException("The card provider refused the request: " + message);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
