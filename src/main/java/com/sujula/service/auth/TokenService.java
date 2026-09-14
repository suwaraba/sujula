package com.sujula.service.auth;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sujula.model.user.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Mints and verifies the two credentials this API issues.
 *
 * <p>They are deliberately different kinds of thing. The <strong>access
 * token</strong> is a signed JWT the server does not store: it is cheap, it
 * carries who the caller is, and it cannot be withdrawn — which is why it is
 * short-lived. The <strong>refresh token</strong> is an opaque random string
 * with no meaning outside the database, stored only as a hash, rotated on every
 * use and revocable at any moment.
 *
 * <p>Putting anything sensitive in the JWT would be a mistake: it is signed, not
 * encrypted, and anyone holding it can read every claim. It carries an id, a
 * role and a session — nothing that would matter if printed in a log.
 */
@Slf4j
@Service
public class TokenService {

    private static final int REFRESH_TOKEN_BYTES = 32;   // 256 bits of entropy
    private static final int MIN_SECRET_BYTES = 32;

    private final AuthProperties properties;
    private final Environment environment;
    private final SecureRandom random = new SecureRandom();

    private byte[] signingKey;

    public TokenService(AuthProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @PostConstruct
    void resolveSigningKey() {
        String configured = properties.getJwt().getSecret();
        boolean production = isProductionProfile();

        if (configured != null && !configured.isBlank()) {
            byte[] key = configured.getBytes(StandardCharsets.UTF_8);
            if (key.length < MIN_SECRET_BYTES) {
                throw new IllegalStateException(
                        "sujula.auth.jwt.secret must be at least " + MIN_SECRET_BYTES
                                + " bytes. A shorter value is not a 256-bit key and the signature "
                                + "is weaker than HS256 implies.");
            }
            this.signingKey = key;
            return;
        }

        if (production) {
            throw new IllegalStateException(
                    "sujula.auth.jwt.secret is not set. Under the prod profile a generated key is "
                            + "not acceptable: it changes on every restart, signing every issued "
                            + "token out of existence, and it is not held anywhere two instances "
                            + "could share.");
        }

        this.signingKey = new byte[MIN_SECRET_BYTES];
        random.nextBytes(this.signingKey);
        log.warn("[Auth] No sujula.auth.jwt.secret configured — generated an ephemeral signing key. "
                + "Every access token stops verifying when this process restarts. Set the property "
                + "to keep sessions across restarts.");
    }

    // ── Access tokens ────────────────────────────────────────────────────────

    /**
     * A signed access token for one session.
     *
     * @param sessionId the session it belongs to, so a revoked device's tokens
     *                  can be recognised on the very next request
     */
    public String mintAccessToken(User user, Long sessionId) {
        return mintAccessToken(user, sessionId, null);
    }

    /**
     * The same, for a session an administrator is holding on somebody's behalf.
     *
     * <p>The {@code act} claim names the administrator, and it is on the token
     * rather than only in the database for one reason: every client that reads
     * a token can then show the banner without asking a second endpoint, and a
     * banner that depends on a second request is one that is missing on the
     * screen where it matters. It is also what makes an impersonated request
     * distinguishable in a log after the fact.
     *
     * <p>Named after the actor claim in RFC 8693, which is the same idea:
     * {@code sub} is who the request is acting <em>as</em>, {@code act} is who
     * is actually behind it.
     *
     * @param actingAdminId the administrator behind the session, or null for an
     *                      ordinary one
     */
    public String mintAccessToken(User user, Long sessionId, Long actingAdminId) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.getJwt().getAccessTokenTtl());
        try {
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                    .subject(String.valueOf(user.getId()))
                    .issuer(properties.getJwt().getIssuer())
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(expiry))
                    .jwtID(UUID.randomUUID().toString())
                    .claim("sid", sessionId)
                    .claim("role", user.getRole().name())
                    .claim("typ", "access");
            if (actingAdminId != null) {
                builder.claim("act", actingAdminId);
            }
            JWTClaimsSet claims = builder.build();

            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(), claims);
            jwt.sign(new MACSigner(signingKey));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign an access token", e);
        }
    }

    /**
     * Verifies a token and returns what it asserts, or empty if it does not hold
     * up. Never throws on a bad token: a malformed or expired credential is an
     * ordinary event on a public endpoint, not an exceptional one.
     */
    public Optional<AccessTokenClaims> verifyAccessToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(new MACVerifier(signingKey))) {
                return Optional.empty();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();

            Date expiry = claims.getExpirationTime();
            if (expiry == null || expiry.toInstant().isBefore(Instant.now())) {
                return Optional.empty();
            }
            if (!"access".equals(claims.getStringClaim("typ"))) {
                return Optional.empty();   // a token minted for something else is not an access token
            }
            if (!properties.getJwt().getIssuer().equals(claims.getIssuer())) {
                return Optional.empty();
            }

            Long userId = Long.valueOf(claims.getSubject());
            Number sid = (Number) claims.getClaim("sid");
            if (sid == null) {
                return Optional.empty();
            }
            Number act = (Number) claims.getClaim("act");
            return Optional.of(new AccessTokenClaims(userId, sid.longValue(),
                    claims.getStringClaim("role"), expiry.toInstant(),
                    act == null ? null : act.longValue()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * What a verified access token asserts.
     *
     * @param userId         who the request acts as
     * @param impersonatedBy the administrator behind it, or null for an ordinary
     *                       session. Present so a client can show the banner and
     *                       a log can tell the two apart afterwards.
     */
    public record AccessTokenClaims(Long userId, Long sessionId, String role, Instant expiresAt,
                                    Long impersonatedBy) {

        /** Whether somebody is holding this session on the user's behalf. */
        public boolean isImpersonated() {
            return impersonatedBy != null;
        }
    }

    // ── Refresh tokens ───────────────────────────────────────────────────────

    /**
     * A new refresh token: the value to hand the client, and the hash to store.
     *
     * <p>Opaque and random rather than a JWT. There is nothing for a client to
     * read in it, and being meaningless is the point — it is only ever valid
     * because a row says so, which is what makes revoking it possible at all.
     */
    public RefreshToken generateRefreshToken() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        random.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new RefreshToken(value, sha256(value));
    }

    /**
     * SHA-256, hex encoded.
     *
     * <p>Plain SHA-256 rather than BCrypt on purpose, and the difference matters.
     * BCrypt is deliberately slow to make guessing a human-chosen password
     * expensive. A refresh token is 256 random bits — there is nothing to guess —
     * and it has to be looked up by hash on every refresh, which a per-row salt
     * would make impossible without scanning the table.
     */
    public String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable on this JVM", e);
        }
    }

    /** A refresh token as issued: {@code value} goes to the client, {@code hash} to the database. */
    public record RefreshToken(String value, String hash) {}

    public long accessTokenSeconds() {
        return properties.getJwt().getAccessTokenTtl().toSeconds();
    }

    private boolean isProductionProfile() {
        for (String profile : environment.getActiveProfiles()) {
            if (profile.equalsIgnoreCase("prod") || profile.equalsIgnoreCase("production")) {
                return true;
            }
        }
        return false;
    }
}
