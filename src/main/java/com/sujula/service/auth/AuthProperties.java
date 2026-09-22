package com.sujula.service.auth;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Deployment settings for token issuance, multi-factor and verification codes. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "sujula.auth")
public class AuthProperties {

    private final Jwt jwt = new Jwt();
    private final Mfa mfa = new Mfa();
    private final PhoneVerification phone = new PhoneVerification();
    private final OAuth oauth = new OAuth();

    @Getter
    @Setter
    public static class Jwt {
        /**
         * HMAC signing key. Must be at least 32 bytes: anything shorter is not a
         * 256-bit key and the signature is weaker than it looks.
         *
         * <p>Unset, a random key is generated at startup and every token from the
         * previous run stops verifying — tolerable while developing, and a reason
         * the application refuses to start without one under the prod profile.
         */
        private String secret = "";

        private String issuer = "sujula";

        /**
         * How long an access token is good for.
         *
         * <p>Short on purpose. An access token is a bearer credential and the
         * server does not track it, so this is also the blast radius of a stolen
         * one. Ten minutes keeps that small while letting the common case — a
         * request that needs no database round trip to authorise — stay cheap.
         */
        private Duration accessTokenTtl = Duration.ofMinutes(10);

        /** How long a device stays signed in without using the application. */
        private Duration refreshTokenTtl = Duration.ofDays(30);

        /** Sessions a single account may hold at once; the oldest is dropped past this. */
        private int maxSessionsPerUser = 10;
    }

    @Getter
    @Setter
    public static class Mfa {
        /** Steps of drift accepted either side of now, for clock skew. One step is 30s. */
        private int allowedDriftSteps = 1;

        private int recoveryCodeCount = 10;

        /** Label shown in the authenticator app beside the account. */
        private String issuerLabel = "Sujula";
    }

    @Getter
    @Setter
    public static class PhoneVerification {
        private Duration codeTtl = Duration.ofMinutes(10);

        /** Codes one account may request per hour, before it is told to wait. */
        private int maxPerHour = 5;

        /**
         * Return the code in the API response instead of sending it.
         *
         * <p>For local development only, where no SMS provider exists. It refuses
         * to be on under the prod profile, because it turns phone verification
         * into a formality anyone can complete for any number.
         */
        private boolean exposeCodeInResponse = false;
    }

    @Getter
    @Setter
    public static class OAuth {
        private final Provider google = new Provider();
        private final Provider apple = new Provider();

        @Getter
        @Setter
        public static class Provider {
            private String clientId = "";
            private String clientSecret = "";
            private String redirectUri = "";
            private String tokenUri = "";
            private String jwksUri = "";

            public boolean isConfigured() {
                return !clientId.isBlank() && !clientSecret.isBlank();
            }
        }
    }
}
