package com.sujula.controller;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.sujula.service.webhook.WebhookSignature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stripe's own signature header, accepted on the ordinary PSP path.
 *
 * <p>Stripe signs HMAC-SHA256 over {@code <t>.<body>} — the scheme the intake
 * already verifies — and differs only in carrying both halves in one header.
 * So the proof that matters is that a correctly signed Stripe event is let in
 * and a wrongly signed one is not; the verification itself is not new.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "sujula.webhooks.secrets.stripe=whsec_test_secret")
class StripeWebhookRoutingTest {

    private static final String SECRET = "whsec_test_secret";

    @Autowired private MockMvc mvc;

    @Test
    void aStripeSignedEventIsAccepted() throws Exception {
        byte[] body = "{\"id\":\"evt_stripe_1\",\"type\":\"checkout.session.completed\"}"
                .getBytes(StandardCharsets.UTF_8);
        String t = String.valueOf(Instant.now().getEpochSecond());

        mvc.perform(post("/webhooks/psp/stripe")
                        .header("Stripe-Signature", "t=" + t + ",v1="
                                + WebhookSignature.sign(SECRET, t, body) + ",v0=ignored")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk());
    }

    @Test
    void aStripeEventSignedWithAnotherSecretIsRefused() throws Exception {
        byte[] body = "{\"id\":\"evt_stripe_2\",\"type\":\"checkout.session.completed\"}"
                .getBytes(StandardCharsets.UTF_8);
        String t = String.valueOf(Instant.now().getEpochSecond());

        mvc.perform(post("/webhooks/psp/stripe")
                        .header("Stripe-Signature", "t=" + t + ",v1="
                                + WebhookSignature.sign("whsec_somebody_else", t, body))
                        .contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theStripeHeaderIsOnlyReadForStripe() throws Exception {
        // Another provider presenting a Stripe-shaped header gets no benefit
        // from it: its own headers are the only ones read.
        byte[] body = "{\"id\":\"evt_3\"}".getBytes(StandardCharsets.UTF_8);
        String t = String.valueOf(Instant.now().getEpochSecond());

        mvc.perform(post("/webhooks/psp/wave")
                        .header("Stripe-Signature", "t=" + t + ",v1="
                                + WebhookSignature.sign(SECRET, t, body))
                        .contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theHeaderIsSplitIntoItsTwoHalves() {
        WebhookController.StripeSignature parsed =
                WebhookController.StripeSignature.parse("t=1700000000,v1=abc,v1=def,v0=zzz");
        assertEquals("1700000000", parsed.t());
        assertEquals("abc", parsed.v1());
    }
}
