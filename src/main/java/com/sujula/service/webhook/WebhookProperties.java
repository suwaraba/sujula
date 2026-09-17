package com.sujula.service.webhook;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * The shared secrets a provider signs with, and how strict to be about time.
 *
 * <p>Per provider, because they are per provider: a secret shared between two
 * of them means either can forge the other's events, and rotating one rotates
 * both.
 *
 * <p>A provider with no secret configured is <em>refused</em>, not waved
 * through. That is the single most important line in this file. A platform that
 * accepted unsigned webhooks from an unconfigured provider would accept
 * "payment succeeded" from anybody who could guess the path — and the default
 * state of a config map is empty.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "sujula.webhooks")
public class WebhookProperties {

    /**
     * Secret per provider, keyed by the path segment: {@code stripe}, {@code wave}.
     *
     * <p>Empty by default. See the class note: empty means every provider is
     * refused until somebody configures one, which is the safe direction for a
     * value that decides whether money is believed.
     */
    private Map<String, String> secrets = new LinkedHashMap<>();

    /**
     * How far out of date a signed timestamp may be.
     *
     * <p>This is the replay window. A signature is valid forever once captured,
     * so the timestamp is what stops somebody re-sending yesterday's "payment
     * succeeded" — and the tolerance has to cover ordinary clock drift and a
     * provider's own retry delay without opening the window wider than it needs
     * to be.
     */
    private Duration tolerance = Duration.ofMinutes(5);

    /** Bodies larger than this are refused before being read into memory. */
    private int maxBodyBytes = 1_048_576;

    /** How many times a failed handler is retried before a person is needed. */
    private int maxAttempts = 5;

    public Optional<String> secretFor(String provider) {
        if (provider == null) {
            return Optional.empty();
        }
        String secret = secrets.get(provider.toLowerCase(java.util.Locale.ROOT));
        return secret == null || secret.isBlank() ? Optional.empty() : Optional.of(secret);
    }

    public boolean isConfigured(String provider) {
        return secretFor(provider).isPresent();
    }
}
