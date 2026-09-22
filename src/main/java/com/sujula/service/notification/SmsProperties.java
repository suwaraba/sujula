package com.sujula.service.notification;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * The SMS provider. Twilio today, because its trial account costs nothing and
 * sends to Gambian and Senegalese numbers — to verified numbers only, which is
 * the right limit for a test deployment anyway.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "sujula.sms")
public class SmsProperties {

    private final Twilio twilio = new Twilio();

    /** Connect and read, each. A text is sent on the request thread. */
    private Duration timeout = Duration.ofSeconds(5);

    @Getter
    @Setter
    public static class Twilio {
        private String accountSid = "";
        private String authToken = "";
        /** A Twilio number in E.164, or blank when a messaging service is used instead. */
        private String fromNumber = "";
        /** {@code MG…}; takes precedence over {@link #fromNumber} when set. */
        private String messagingServiceSid = "";
        private String baseUrl = "https://api.twilio.com";
    }
}
