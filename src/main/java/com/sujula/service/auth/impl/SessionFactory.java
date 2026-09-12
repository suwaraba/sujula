package com.sujula.service.auth.impl;

import com.sujula.dto.response.auth.AuthResponses;
import com.sujula.model.auth.UserSession;
import com.sujula.model.constant.SessionRevocationReason;
import com.sujula.model.user.User;
import com.sujula.repository.auth.UserSessionRepository;
import com.sujula.service.auth.AuthProperties;
import com.sujula.service.auth.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Creates and rotates the session rows that keep a device signed in.
 *
 * <p>Separated out because four different flows need it — registration, sign-in,
 * refresh and the OAuth callback — and duplicating the token/session dance in
 * each is how one of them ends up subtly different from the others.
 */
@Slf4j
@Component
public class SessionFactory {

    private static final List<String> IP_HEADERS = List.of(
            "CF-Connecting-IP", "True-Client-IP", "X-Forwarded-For", "X-Real-IP");

    private final UserSessionRepository sessions;
    private final TokenService tokenService;
    private final AuthProperties properties;

    public SessionFactory(UserSessionRepository sessions, TokenService tokenService,
                          AuthProperties properties) {
        this.sessions = sessions;
        this.tokenService = tokenService;
        this.properties = properties;
    }

    /**
     * Opens a session and issues the pair of tokens for it.
     *
     * <p>Trims the account back to its session cap first, oldest-idle first. Not
     * housekeeping: an unbounded session list is an unbounded set of live
     * credentials, and the device someone last used a year ago is the one most
     * likely to have been lost.
     */
    public AuthResponses.Tokens open(User user, String deviceLabel, AuthResponses.Profile profile) {
        trimToCap(user.getId());

        TokenService.RefreshToken refresh = tokenService.generateRefreshToken();
        LocalDateTime now = LocalDateTime.now();

        UserSession session = UserSession.builder()
                .user(user)
                .refreshTokenHash(refresh.hash())
                .deviceLabel(label(deviceLabel))
                .userAgent(truncate(header("User-Agent"), 400))
                .ipAddress(clientIp())
                .createdAt(now)
                .lastSeenAt(now)
                .expiresAt(now.plus(properties.getJwt().getRefreshTokenTtl()))
                .build();

        UserSession saved = sessions.save(session);
        String access = tokenService.mintAccessToken(user, saved.getId());

        return AuthResponses.Tokens.of(access, tokenService.accessTokenSeconds(),
                refresh.value(), saved.getId(), profile);
    }

    /**
     * Issues the next pair for an existing session and moves the chain forward.
     *
     * <p>The token just used becomes {@code previousTokenHash}, which is what
     * makes a later replay of it recognisable rather than merely stale.
     */
    public AuthResponses.Tokens rotate(UserSession session, AuthResponses.Profile profile) {
        TokenService.RefreshToken next = tokenService.generateRefreshToken();

        session.setPreviousTokenHash(session.getRefreshTokenHash());
        session.setRefreshTokenHash(next.hash());
        session.setLastSeenAt(LocalDateTime.now());
        String ip = clientIp();
        if (ip != null) {
            session.setIpAddress(ip);
        }
        UserSession saved = sessions.save(session);

        String access = tokenService.mintAccessToken(session.getUser(), saved.getId());
        return AuthResponses.Tokens.of(access, tokenService.accessTokenSeconds(),
                next.value(), saved.getId(), profile);
    }

    private void trimToCap(Long userId) {
        int cap = properties.getJwt().getMaxSessionsPerUser();
        List<UserSession> active = sessions.findActiveOldestFirst(userId);
        int excess = active.size() - (cap - 1);
        for (int i = 0; i < excess && i < active.size(); i++) {
            active.get(i).revoke(SessionRevocationReason.ADMIN);
            log.info("[Auth] Session {} for user {} dropped: account is at its {}-session cap",
                    active.get(i).getId(), userId, cap);
        }
        if (excess > 0) {
            sessions.saveAll(active.subList(0, Math.min(excess, active.size())));
        }
    }

    /**
     * What the user will recognise in their device list.
     *
     * <p>A client-supplied label wins, because "Aminata's iPhone" beats anything
     * derivable. Failing that the user agent is reduced to something a person can
     * read: nobody identifies a device from a ninety-character UA string.
     */
    private String label(String requested) {
        if (requested != null && !requested.isBlank()) {
            return truncate(requested.trim(), 120);
        }
        String ua = header("User-Agent");
        if (ua == null || ua.isBlank()) {
            return "Unknown device";
        }
        String platform = ua.contains("Android") ? "Android"
                : (ua.contains("iPhone") || ua.contains("iPad")) ? "iOS"
                : ua.contains("Windows") ? "Windows"
                : ua.contains("Mac OS") ? "macOS"
                : ua.contains("Linux") ? "Linux" : "Unknown";
        String browser = ua.contains("Edg/") ? "Edge"
                : ua.contains("Chrome/") ? "Chrome"
                : ua.contains("Firefox/") ? "Firefox"
                : ua.contains("Safari/") ? "Safari" : "App";
        return browser + " on " + platform;
    }

    /**
     * The caller's address, preferring the headers a CDN sets.
     *
     * <p>X-Forwarded-For is a list appended to by each hop; the first entry is
     * the original client. It is spoofable by the client itself, so this is
     * recorded for the user to recognise their own devices — it is not evidence
     * and nothing is authorised by it.
     */
    private String clientIp() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        for (String name : IP_HEADERS) {
            String value = request.getHeader(name);
            if (value != null && !value.isBlank()) {
                String first = value.split(",")[0].trim();
                if (!first.isEmpty()) {
                    return truncate(first, 45);
                }
            }
        }
        return truncate(request.getRemoteAddr(), 45);
    }

    private String header(String name) {
        HttpServletRequest request = currentRequest();
        return request == null ? null : request.getHeader(name);
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;   // a scheduled job or a test has no request
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
