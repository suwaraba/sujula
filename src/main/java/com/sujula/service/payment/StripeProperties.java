package com.sujula.service.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * Stripe, for card payments. A {@code sk_test_…} key is a complete, free
 * sandbox: hosted checkout, test cards, refunds and webhooks all behave as they
 * will live, and no money moves.
 *
 * <p>The webhook signing secret is not here. It lives with every other
 * provider's, under {@code sujula.webhooks.secrets.stripe}, because that is
 * what the webhook intake verifies against.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "sujula.payment.stripe")
public class StripeProperties {

    /** {@code sk_test_…} or {@code sk_live_…}. Blank leaves Stripe unregistered. */
    private String secretKey = "";

    /**
     * Where the buyer lands after paying, when checkout did not name a return
     * URL. Blank falls back to {@code app.frontend.url}.
     */
    private String successUrl = "";

    /** Where the buyer lands after abandoning the hosted page. Blank = the success URL. */
    private String cancelUrl = "";

    private String apiBase = "https://api.stripe.com";
}
