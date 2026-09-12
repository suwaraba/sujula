package com.sujula.service.auth.impl;

import com.sujula.dto.response.auth.AuthResponses;
import com.sujula.model.auth.AccountDataRequest;
import com.sujula.model.auth.OAuthAccount;
import com.sujula.model.auth.UserSession;
import com.sujula.model.user.User;
import org.springframework.stereotype.Component;

/**
 * Turns entities into the DTOs the API returns.
 *
 * <p>The only place that conversion happens, and it lives behind the service
 * layer by design: a controller never sees an entity, so it cannot accidentally
 * serialise a password hash, an authenticator secret or a lazy association whose
 * transaction has already closed. Each method below is also the explicit list of
 * what a given response is allowed to contain — adding a column to {@code User}
 * does not silently widen an API.
 */
@Component
public class AuthMapper {

    /**
     * The account as its owner sees it.
     *
     * <p>Absent on purpose: password, authenticator secret, verification and
     * reset tokens, failed-attempt counters, lockout timestamps. Those run the
     * account; they do not describe it, and several would be useful to an
     * attacker who had got as far as reading one response.
     */
    public AuthResponses.Profile toProfile(User user) {
        return new AuthResponses.Profile(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                fullName(user),
                user.getPhone(),
                user.getRole(),
                user.isEmailVerified(),
                user.isPhoneVerified(),
                user.isTotpEnabled(),
                user.getPreferredCurrency(),
                user.getPreferredLanguage(),
                user.getDetectedCountryCode(),
                user.getProfileImageUrl(),
                user.getCreatedAt());
    }

    /**
     * One device in the session list.
     *
     * @param currentSessionId marks the row the caller is using, so a client can
     *                         label it and avoid presenting "sign out" on the
     *                         session that would end the very request doing it
     */
    public AuthResponses.Session toSession(UserSession session, Long currentSessionId) {
        return new AuthResponses.Session(
                session.getId(),
                session.getDeviceLabel(),
                session.getUserAgent(),
                session.getIpAddress(),
                session.getCountryCode(),
                session.getId().equals(currentSessionId),
                session.isActive(),
                session.getCreatedAt(),
                session.getLastSeenAt(),
                session.getExpiresAt(),
                session.getRevokedAt(),
                session.getRevokedReason() == null ? null : session.getRevokedReason().name());
    }

    public AuthResponses.LinkedAccount toLinkedAccount(OAuthAccount account) {
        return new AuthResponses.LinkedAccount(
                account.getProvider(), account.getEmail(), account.getLinkedAt());
    }

    public AuthResponses.DataRequest toDataRequest(AccountDataRequest request) {
        return new AuthResponses.DataRequest(
                request.getReference(),
                request.getType(),
                request.getStatus(),
                request.getRequestedAt(),
                request.getCompletedAt(),
                request.getDownloadUrl(),
                request.getDownloadExpiresAt(),
                request.getFailureReason());
    }

    private static String fullName(User user) {
        String first = user.getFirstName() == null ? "" : user.getFirstName().trim();
        String last = user.getLastName() == null ? "" : user.getLastName().trim();
        return (first + " " + last).trim();
    }
}
