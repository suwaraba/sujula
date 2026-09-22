package com.sujula.service.notification.impl;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.sujula.service.notification.SmsProperties;
import com.sujula.service.notification.SmsSender;

import lombok.extern.slf4j.Slf4j;

/**
 * Sends texts through Twilio's REST API.
 *
 * <p>One form POST with basic auth, so no SDK: a dependency that pulls in its
 * own HTTP stack to make one call is more to keep patched than the call is
 * worth.
 *
 * <p>A trial account sends only to numbers verified in the Twilio console and
 * prefixes every message with "Sent from your Twilio trial account". Both are
 * fine for testing and both go away on upgrade with no change here.
 */
@Slf4j
@Component
@ConditionalOnExpression("'${sujula.sms.twilio.account-sid:}'.length() > 0")
public class TwilioSmsSender implements SmsSender {

    private final SmsProperties.Twilio config;
    private final RestClient http;

    public TwilioSmsSender(SmsProperties properties) {
        this.config = properties.getTwilio();
        if (config.getAuthToken().isBlank()
                || (config.getFromNumber().isBlank() && config.getMessagingServiceSid().isBlank())) {
            throw new IllegalStateException("sujula.sms.twilio.account-sid is set but auth-token, or "
                    + "both from-number and messaging-service-sid, are blank. Set them or unset the SID.");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getTimeout());
        factory.setReadTimeout(properties.getTimeout());
        String basic = Base64.getEncoder().encodeToString(
                (config.getAccountSid() + ":" + config.getAuthToken()).getBytes(StandardCharsets.UTF_8));
        this.http = RestClient.builder()
                .baseUrl(config.getBaseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .build();
        log.info("[SMS] Twilio configured for account {}…", config.getAccountSid().substring(
                0, Math.min(6, config.getAccountSid().length())));
    }

    @Override
    public boolean send(String toPhone, String body) {
        if (toPhone == null || toPhone.isBlank()) {
            return false;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", toPhone);
        form.add("Body", body);
        if (!config.getMessagingServiceSid().isBlank()) {
            form.add("MessagingServiceSid", config.getMessagingServiceSid());
        } else {
            form.add("From", config.getFromNumber());
        }
        try {
            http.post()
                    .uri("/2010-04-01/Accounts/{sid}/Messages.json", config.getAccountSid())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
            log.info("[SMS] Sent a text to {}", LoggedSmsSender.mask(toPhone));
            return true;
        } catch (RuntimeException failed) {
            // The message is not logged: it carries a code.
            log.warn("[SMS] Twilio refused a text to {}: {}", LoggedSmsSender.mask(toPhone),
                    failed.getMessage());
            return false;
        }
    }

    @Override
    public boolean isConfigured() {
        return true;
    }
}
