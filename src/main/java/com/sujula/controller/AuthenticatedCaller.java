package com.sujula.controller;

import com.sujula.config.JwtAuthenticationFilter;
import com.sujula.model.user.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Names the caller, and only ever the caller.
 *
 * <p>This is where data ownership is anchored for the whole {@code /auth} and
 * {@code /me} surface. Every operation on an account takes its subject from
 * here — from the authenticated principal — and never from a path variable, a
 * query parameter or a request body. There is consequently no id a caller could
 * change to reach somebody else's account, because no such id is ever read.
 *
 * <p>That is a stronger guarantee than checking ownership after the fact. A
 * check can be forgotten on the one endpoint that matters; a parameter that does
 * not exist cannot be tampered with on any of them.
 */
@Component
public class AuthenticatedCaller {

    /** The signed-in user's id. Throws rather than returning null: there is no safe default. */
    public Long userId(Authentication authentication) {
        return user(authentication).getId();
    }

    public User user(Authentication authentication) {
        if (authentication != null
                && authentication.getPrincipal() instanceof User user
                && user.getId() != null) {
            return user;
        }
        throw new AccessDeniedException("Authentication is required");
    }

    /**
     * Which session the request arrived on, when it arrived on a token.
     *
     * <p>Needed by the operations that spare the current device — changing a
     * password signs the others out, and enabling multi-factor does the same —
     * and by the session list, to mark the row the caller is using.
     *
     * <p>Read from the attribute the authentication filter published, not
     * re-derived: the token was verified once on the way in, and verifying it
     * again here would mean a second HMAC per request for a value already known.
     *
     * <p>Null for a cookie-session request, which carries no session id. The
     * callers treat that as "spare nothing", which is the safe reading: a
     * password change that cannot identify the current device signs every device
     * out rather than guessing which one to keep.
     */
    public Long sessionId() {
        HttpServletRequest request = currentRequest();
        return request == null
                ? null
                : (Long) request.getAttribute(JwtAuthenticationFilter.SESSION_ID_ATTRIBUTE);
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }
}
