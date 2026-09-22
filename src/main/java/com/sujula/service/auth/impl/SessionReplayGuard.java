package com.sujula.service.auth.impl;

import com.sujula.model.auth.UserSession;
import com.sujula.model.constant.SessionRevocationReason;
import com.sujula.repository.auth.UserSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ends a session whose refresh token came back after being rotated away.
 *
 * <p>A hit is a replay by definition: rotation is single-use, so the legitimate
 * client already holds the successor and has no reason to present the spent one.
 * Either the token was stolen, or the client's copy was — and neither is a
 * situation in which the session should keep working. The legitimate user signs
 * in again; the thief gets nothing.
 *
 * <p>A bean of its own, and that is the entire point of the class. The
 * revocation has to outlive the {@code BadCredentialsException} thrown
 * immediately afterwards, which is what {@code REQUIRES_NEW} is for — but
 * Spring's transaction management works through a proxy, and a call from one
 * method of a bean to another on {@code this} never reaches it. Written that
 * way, the revocation joined the caller's transaction and was rolled back by the
 * very exception it was meant to survive, leaving the stolen token's session
 * live. Crossing a bean boundary is what makes the propagation mean anything.
 */
@Slf4j
@Component
public class SessionReplayGuard {

    private final UserSessionRepository sessions;

    public SessionReplayGuard(UserSessionRepository sessions) {
        this.sessions = sessions;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeOnReplay(UserSession session) {
        if (!session.isActive()) {
            return;
        }
        // Re-read inside this transaction: the instance handed in belongs to the
        // caller's persistence context, and saving a detached copy here would
        // write through a context that is about to be rolled back.
        sessions.findById(session.getId()).ifPresent(live -> {
            if (!live.isActive()) {
                return;
            }
            live.revoke(SessionRevocationReason.TOKEN_REPLAY);
            sessions.save(live);
            log.warn("[Auth] Refresh token replay on session {} (user {}) — session revoked. "
                            + "The token presented had already been rotated away, so it was not the "
                            + "legitimate client's.",
                    live.getId(), live.getUser().getId());
        });
    }
}
