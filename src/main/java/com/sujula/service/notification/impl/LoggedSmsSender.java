package com.sujula.service.notification.impl;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import com.sujula.service.notification.SmsSender;

import lombok.extern.slf4j.Slf4j;

/**
 * What happens to a text when no SMS provider is configured: nothing, said so.
 *
 * <p>The body is not logged. Everything sent on this channel is a code, and a
 * code in a log file is a parcel released by whoever reads the log. Callers that
 * want a code visible in development log it themselves, under their own
 * profile checks.
 *
 * <p>The complement of {@link TwilioSmsSender}'s condition, so exactly one of
 * the two is ever registered.
 */
@Slf4j
@Component
@ConditionalOnExpression("'${sujula.sms.twilio.account-sid:}'.length() == 0")
public class LoggedSmsSender implements SmsSender {

    public LoggedSmsSender() {
        log.info("[SMS] No provider configured — texts are not sent. Set sujula.sms.twilio.* to enable.");
    }

    @Override
    public boolean send(String toPhone, String body) {
        log.info("[SMS] (no provider configured) would send a text to {}", mask(toPhone));
        return false;
    }

    @Override
    public boolean isConfigured() {
        return false;
    }

    static String mask(String phone) {
        if (phone == null || phone.length() < 4) {
            return "•••";
        }
        return "•••" + phone.substring(phone.length() - 3);
    }
}
