package com.sujula.controller;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sujula.model.constant.WebhookKind;
import com.sujula.model.constant.WebhookStatus;
import com.sujula.service.webhook.WebhookIntake;
import com.sujula.service.webhook.WebhookProperties;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;

/**
 * Where providers tell us things.
 *
 * <p>Unauthenticated in the ordinary sense — there is no session and no bearer
 * token — and defended instead by a signature over the body and a signed
 * timestamp. That is the only mechanism available: the caller is a machine in
 * somebody else's data centre with no account here.
 *
 * <p>The body is taken as {@code byte[]} rather than a bound object, and that is
 * not an oversight. The provider signed their own bytes; anything Spring parses
 * and re-serialises has different whitespace and a signature that no longer
 * matches. Binding a DTO here would break verification on the first provider
 * who pretty-prints.
 *
 * <p>Answers are deliberately uninformative. "Rejected" is all a caller gets,
 * whether their signature was wrong, their clock was off, or their provider is
 * not configured — telling somebody probing which of those they got right is
 * telling them how to make progress.
 */
@Slf4j
@RestController
@RequestMapping("/webhooks")
@Tag(name = "webhooks", description = "Signed callbacks from payment, messaging and identity providers")
public class WebhookController {

    private final WebhookIntake intake;
    private final WebhookProperties properties;

    public WebhookController(WebhookIntake intake, WebhookProperties properties) {
        this.intake = intake;
        this.properties = properties;
    }

    @PostMapping(value = "/psp/{provider}", consumes = MediaType.ALL_VALUE)
    @Operation(summary = "A payment provider reporting what happened to a payment",
               description = "The highest-consequence endpoint on the platform: a forged event "
                       + "here marks an unpaid order paid and ships goods nobody bought. Signed "
                       + "with HMAC-SHA256 over <timestamp>.<body>, compared in constant time, "
                       + "with the timestamp inside the signature so a captured request cannot be "
                       + "replayed tomorrow. An amount that disagrees with the payment is refused "
                       + "rather than applied — a provider does not get to decide an order cost "
                       + "something else.")
    public ResponseEntity<Map<String, Object>> psp(
            @PathVariable String provider,
            @RequestBody(required = false) byte[] body,
            @RequestHeader(value = "X-Sujula-Signature", required = false) String signature,
            @RequestHeader(value = "X-Sujula-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "Stripe-Signature", required = false) String stripeSignature) {
        if (signature == null && timestamp == null && stripeSignature != null
                && "stripe".equalsIgnoreCase(provider)) {
            // Stripe signs exactly this scheme — HMAC-SHA256 over <t>.<body> — and
            // only packs both halves into one header: t=<seconds>,v1=<hex>.
            StripeSignature parsed = StripeSignature.parse(stripeSignature);
            signature = parsed.v1();
            timestamp = parsed.t();
        }
        return receive(WebhookKind.PSP, provider, body, signature, timestamp);
    }

    /**
     * The two halves of a {@code Stripe-Signature} header.
     *
     * <p>Only the first {@code v1} is taken. Stripe sends a second one only while
     * a signing secret is being rolled, and the configured secret is the new one.
     */
    record StripeSignature(String t, String v1) {
        static StripeSignature parse(String header) {
            String t = null;
            String v1 = null;
            for (String part : header.split(",")) {
                String[] pair = part.trim().split("=", 2);
                if (pair.length != 2) continue;
                if (pair[0].equals("t") && t == null) t = pair[1];
                if (pair[0].equals("v1") && v1 == null) v1 = pair[1];
            }
            return new StripeSignature(t, v1);
        }
    }

    @PostMapping(value = "/messaging/{provider}", consumes = MediaType.ALL_VALUE)
    @Operation(summary = "Delivery receipts for messages this platform sent",
               description = "Bounces, deliveries and complaints. The one that matters here is a "
                       + "bounce: a recipient's release code reaches the buyer by email for them "
                       + "to pass on, so an address that bounced is somebody standing in front of "
                       + "a driver with nothing to read out.")
    public ResponseEntity<Map<String, Object>> messaging(
            @PathVariable String provider,
            @RequestBody(required = false) byte[] body,
            @RequestHeader(value = "X-Sujula-Signature", required = false) String signature,
            @RequestHeader(value = "X-Sujula-Timestamp", required = false) String timestamp) {
        return receive(WebhookKind.MESSAGING, provider, body, signature, timestamp);
    }

    @PostMapping(value = "/sms/{provider}", consumes = MediaType.ALL_VALUE)
    @Operation(summary = "SMS delivery receipts, for a deployment that sends SMS",
               description = "The same handler as /webhooks/messaging. With an SMS provider "
                       + "configured, phone-verification codes and the recipient's release code "
                       + "go out by text; receipts posted here are recorded like any other "
                       + "message's. Drivers and pickup operators still read their codes in the app.")
    public ResponseEntity<Map<String, Object>> sms(
            @PathVariable String provider,
            @RequestBody(required = false) byte[] body,
            @RequestHeader(value = "X-Sujula-Signature", required = false) String signature,
            @RequestHeader(value = "X-Sujula-Timestamp", required = false) String timestamp) {
        return receive(WebhookKind.MESSAGING, provider, body, signature, timestamp);
    }

    @PostMapping(value = "/kyc/{provider}", consumes = MediaType.ALL_VALUE)
    @Operation(summary = "An outsourced identity check coming back",
               description = "Never trusted to approve anybody. A clean result records that the "
                       + "provider found no problem; whether the store opens is still a person's "
                       + "decision, because the cost of being wrong is a driver's goods in a "
                       + "stranger's hands.")
    public ResponseEntity<Map<String, Object>> kyc(
            @PathVariable String provider,
            @RequestBody(required = false) byte[] body,
            @RequestHeader(value = "X-Sujula-Signature", required = false) String signature,
            @RequestHeader(value = "X-Sujula-Timestamp", required = false) String timestamp) {
        return receive(WebhookKind.KYC, provider, body, signature, timestamp);
    }

    // ── Shared ───────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> receive(WebhookKind kind, String provider,
                                                        byte[] body, String signature,
                                                        String timestamp) {
        byte[] payload = body == null ? new byte[0] : body;

        if (payload.length > properties.getMaxBodyBytes()) {
            // Refused before anything reads it. An unauthenticated endpoint that
            // parses arbitrarily large bodies is a way to exhaust this process
            // from outside.
            log.warn("[Webhook] {} from {} was {} bytes, over the limit",
                    kind, provider, payload.length);
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of("status", "rejected"));
        }

        WebhookIntake.Accepted accepted =
                intake.receive(kind, provider, payload, signature, timestamp);

        if (accepted.status() == WebhookStatus.REJECTED) {
            // 401 rather than 400: this is an authentication failure, and a
            // provider whose secret has been rotated needs to see that rather
            // than think their payload was malformed.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("status", "rejected"));
        }

        // 200 for a duplicate as much as for a new event. A provider retrying is
        // working correctly, and the answer that makes them stop is the one that
        // says we have it.
        return ResponseEntity.ok(Map.of(
                "status", accepted.status() == WebhookStatus.DUPLICATE ? "duplicate" : "accepted",
                "message", accepted.message()));
    }
}
