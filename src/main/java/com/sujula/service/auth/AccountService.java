package com.sujula.service.auth;

import com.sujula.dto.request.auth.MeRequests;
import com.sujula.dto.response.auth.AuthResponses;

import java.util.List;

/**
 * The {@code /me} layer: the account as its owner sees and manages it.
 *
 * <p>Every method takes {@code userId} as its first argument and that id always
 * comes from the authenticated principal — never from anything a caller sent.
 * The one method that accepts an id of its own, {@link #revokeSession}, resolves
 * it with the owner in the same query, so a session belonging to somebody else
 * is not found rather than found-and-refused.
 *
 * <p>DTOs in, DTOs out. No entity crosses this boundary.
 */
public interface AccountService {

    /** Profile, resolved currency and language, permissions, linked accounts, session count. */
    AuthResponses.Me getMe(Long userId);

    /** Partial update. Only the fields present are touched; the rest are left alone. */
    AuthResponses.Profile updateMe(Long userId, MeRequests.UpdateProfile request);

    /**
     * Asks for the account to be erased.
     *
     * <p>Idempotent: asking again while one is open returns the open request
     * rather than starting a second, so a retry on a bad connection cannot queue
     * two erasures.
     */
    AuthResponses.DataRequest requestErasure(Long userId);

    /** Asks for a copy of everything held. Idempotent in the same way. */
    AuthResponses.DataRequest requestExport(Long userId);

    /** The current state of the latest export, including its download link once ready. */
    AuthResponses.DataRequest latestExport(Long userId);

    /** Every device, newest activity first, with the caller's own marked. */
    List<AuthResponses.Session> listSessions(Long userId, Long currentSessionId);

    /** Ends one device. Another user's session is reported as not found. */
    void revokeSession(Long userId, Long sessionId);

    /** What this account may do, resolved from role and vendor standing. */
    AuthResponses.Permissions permissions(Long userId);
}
