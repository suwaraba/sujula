package com.sujula.dto.response.auth;

import com.sujula.model.constant.AuthProvider;
import com.sujula.model.constant.DataRequestStatus;
import com.sujula.model.constant.DataRequestType;
import com.sujula.model.constant.Permission;
import com.sujula.model.constant.UserRole;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Everything the {@code /auth} and {@code /me} endpoints return.
 *
 * <p>No entity ever reaches a controller: services build these and hand them
 * back, so the wire format is a deliberate decision rather than whatever the
 * schema happens to look like this week. Two consequences worth naming — a
 * column added to {@code User} does not silently appear in a response, and a
 * lazy association cannot be dragged into serialisation outside its transaction.
 *
 * <p>Nulls are omitted throughout. A response that lists every field it does not
 * have is noise, and on the token responses it would also be misleading: the
 * absence of {@code refreshToken} is meaningful.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class AuthResponses {

    private AuthResponses() {}

    /**
     * A signed-in session.
     *
     * @param accessToken  short-lived, sent as {@code Authorization: Bearer …}
     * @param expiresIn    seconds, so a client can refresh before it lapses rather
     *                     than discovering the fact through a 401
     * @param refreshToken opaque, single-use. Every refresh returns a new one and
     *                     invalidates this; presenting a spent token is treated as
     *                     theft and ends the session.
     */
    public record Tokens(
            String accessToken,
            String tokenType,
            long expiresIn,
            String refreshToken,
            Long sessionId,
            Profile user) {

        /** {@code tokenType} is always "Bearer"; the factory is here so no caller has to say so. */
        public static Tokens of(String accessToken, long expiresIn, String refreshToken,
                                Long sessionId, Profile user) {
            return new Tokens(accessToken, "Bearer", expiresIn, refreshToken, sessionId, user);
        }
    }

    /**
     * The answer to a sign-in attempt.
     *
     * <p>Either tokens, or a statement that a second factor is needed. Modelled
     * as one response with two shapes rather than an error, because "your
     * password was right and I need one more thing" is not a failure and a client
     * that cannot tell it from a wrong password will show the wrong message.
     */
    public record LoginResult(
            boolean mfaRequired,
            Tokens tokens) {

        public static LoginResult authenticated(Tokens tokens) {
            return new LoginResult(false, tokens);
        }

        public static LoginResult mfaChallenge() {
            return new LoginResult(true, null);
        }
    }

    /**
     * The account, as its owner sees it.
     *
     * <p>Carries no password hash, no reset token, no authenticator secret and no
     * lockout counters — those exist to run the account, not to describe it.
     */
    public record Profile(
            Long id,
            String email,
            String firstName,
            String lastName,
            String fullName,
            String phone,
            UserRole role,
            boolean emailVerified,
            boolean phoneVerified,
            boolean mfaEnabled,
            String preferredCurrency,
            String preferredLanguage,
            String countryCode,
            String profileImageUrl,
            LocalDateTime createdAt) {}

    /**
     * The profile plus what the client needs to render for it.
     *
     * @param resolvedCurrency what money will actually be shown in — the stated
     *                         preference when there is one, otherwise inferred
     *                         from where the account appears to be
     * @param vendorId         present only when this account sells
     */
    public record Me(
            Profile profile,
            Set<Permission> permissions,
            String resolvedCurrency,
            String resolvedLanguage,
            Long vendorId,
            String vendorStatus,
            List<LinkedAccount> linkedAccounts,
            int activeSessions) {}

    /**
     * What an account may do, on its own.
     *
     * <p>Separate from {@link Me} because it is what a client re-checks: after a
     * vendor is approved, after a role changes, on a screen that only needs to
     * know which buttons to draw. Answering those with the full {@code Me} would
     * mean four queries for one field.
     *
     * @param canTrade a vendor in good enough standing to sell right now — the
     *                 same predicate checkout uses, so the two cannot drift
     */
    public record Permissions(
            UserRole role,
            Set<Permission> permissions,
            Long vendorId,
            String vendorStatus,
            boolean canTrade) {}

    public record LinkedAccount(AuthProvider provider, String email, LocalDateTime linkedAt) {}

    /**
     * One signed-in device.
     *
     * @param current whether this is the session making the request, so a client
     *                can label it and refuse to let someone revoke themselves by
     *                accident
     */
    public record Session(
            Long id,
            String deviceLabel,
            String userAgent,
            String ipAddress,
            String countryCode,
            boolean current,
            boolean active,
            LocalDateTime createdAt,
            LocalDateTime lastSeenAt,
            LocalDateTime expiresAt,
            LocalDateTime revokedAt,
            String revokedReason) {}

    /**
     * What an authenticator app needs to enrol.
     *
     * <p>Returned once, at setup, and never again. Multi-factor is not on yet at
     * this point: the secret is stored unconfirmed until a code proves the app
     * actually holds it, so a setup someone abandons cannot lock them out.
     */
    public record MfaSetup(String secret, String provisioningUri, String issuer) {}

    /**
     * Recovery codes, shown once.
     *
     * <p>Only the hashes are kept, so this response is the sole opportunity to
     * record them. A lost authenticator with no recovery code is an account
     * nobody can open.
     */
    public record MfaActivation(List<String> recoveryCodes, int remaining) {}

    public record MfaStatus(boolean enabled, int recoveryCodesRemaining) {}

    /**
     * An outstanding phone challenge.
     *
     * @param code present only where the deployment has no SMS provider and is
     *             configured to return it. Refused under the prod profile.
     */
    public record PhoneChallenge(String phone, LocalDateTime expiresAt, int attemptsAllowed, String code) {}

    /**
     * A data-protection request.
     *
     * @param downloadUrl an export, once it is built. Time-limited: this is a
     *                    complete copy of someone's life on the platform.
     */
    public record DataRequest(
            String reference,
            DataRequestType type,
            DataRequestStatus status,
            LocalDateTime requestedAt,
            LocalDateTime completedAt,
            String downloadUrl,
            LocalDateTime downloadExpiresAt,
            String failureReason) {}

    /** Acknowledgement for the endpoints that only confirm something happened. */
    public record Ack(String message) {
        public static Ack of(String message) {
            return new Ack(message);
        }
    }

}
