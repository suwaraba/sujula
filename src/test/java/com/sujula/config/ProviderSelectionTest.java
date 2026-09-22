package com.sujula.config;

import java.security.KeyPairGenerator;
import java.util.Base64;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.sujula.service.notification.PushSender;
import com.sujula.service.notification.SmsSender;
import com.sujula.service.notification.impl.FcmPushSender;
import com.sujula.service.notification.impl.LoggedPushSender;
import com.sujula.service.notification.impl.LoggedSmsSender;
import com.sujula.service.notification.impl.TwilioSmsSender;
import com.sujula.service.payment.MockPaymentGateway;
import com.sujula.service.payment.PaymentGateway;
import com.sujula.service.payment.StripePaymentGateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Which provider is bound for SMS, push and cards, from configuration alone.
 *
 * <p>Each pair is chosen by complementary conditions, so the thing to prove is
 * that credentials switch the real one in, their absence leaves the stand-in,
 * and the application still boots either way. No network: every provider here
 * is only constructed, never called.
 */
class ProviderSelectionTest {

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    class Unconfigured {

        @Autowired private SmsSender sms;
        @Autowired private PushSender push;

        @Test
        void theStandInsAreBound() {
            assertInstanceOf(LoggedSmsSender.class, sms);
            assertInstanceOf(LoggedPushSender.class, push);
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    class Configured {

        @DynamicPropertySource
        static void credentials(DynamicPropertyRegistry registry) throws Exception {
            registry.add("sujula.sms.twilio.account-sid", () -> "AC00000000000000000000000000000000");
            registry.add("sujula.sms.twilio.auth-token", () -> "test-token");
            registry.add("sujula.sms.twilio.from-number", () -> "+15005550006");
            registry.add("sujula.push.fcm.credentials", ProviderSelectionTest::serviceAccountBase64);
            registry.add("sujula.payment.mock.enabled", () -> "false");
            registry.add("sujula.payment.stripe.secret-key", () -> "sk_test_selection");
        }

        @Autowired private SmsSender sms;
        @Autowired private PushSender push;
        @Autowired private java.util.List<PaymentGateway> gateways;

        @Test
        void theRealProvidersAreBound() {
            assertInstanceOf(TwilioSmsSender.class, sms);
            assertInstanceOf(FcmPushSender.class, push);
            assertEquals(1, gateways.size());
            assertInstanceOf(StripePaymentGateway.class, gateways.get(0));
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    class MockAndStripeBothSet {

        @DynamicPropertySource
        static void credentials(DynamicPropertyRegistry registry) {
            registry.add("sujula.payment.mock.enabled", () -> "true");
            registry.add("sujula.payment.stripe.secret-key", () -> "sk_test_selection");
        }

        @Autowired private java.util.List<PaymentGateway> gateways;

        @Test
        void onlyTheMockIsBoundSoWhichTookACardIsNeverDownToBeanOrder() {
            assertEquals(1, gateways.size());
            assertInstanceOf(MockPaymentGateway.class, gateways.get(0));
        }
    }

    /** A throwaway service-account key in the shape Firebase issues, base64'd as .env holds it. */
    static String serviceAccountBase64() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            String pem = "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----\n";
            String json = "{\"type\":\"service_account\",\"project_id\":\"sujula-test\","
                    + "\"client_email\":\"push@sujula-test.iam.gserviceaccount.com\","
                    + "\"token_uri\":\"https://oauth2.googleapis.com/token\","
                    + "\"private_key\":\"" + pem.replace("\r", "").replace("\n", "\\n") + "\"}";
            return Base64.getEncoder().encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
