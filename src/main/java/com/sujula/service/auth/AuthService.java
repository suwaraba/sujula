package com.sujula.service.auth;

import com.sujula.dto.request.auth.AuthRequests;
import com.sujula.dto.response.auth.AuthResponses;

/**
 * Everything under {@code /auth}: getting in, staying in, getting out, and
 * proving you are who you said.
 *
 * <p>Two rules hold across the whole interface. Requests and responses are DTOs
 * and nothing else — no entity crosses this boundary in either direction, so the
 * controllers stay free of persistence concerns and the wire format is a
 * decision rather than an accident of the schema. And every method that acts on
 * an existing account takes the caller's own id from the authenticated
 * principal, never from a parameter a caller could change.
 */
public interface AuthService {

    // ── Getting in ───────────────────────────────────────────────────────────

    /** Creates a CUSTOMER and signs them in. Other roles are reached by approval. */
    AuthResponses.Tokens register(AuthRequests.Register request);

    /**
     * Signs in, or says that a second factor is still needed.
     *
     * <p>A wrong password and a missing authenticator code are different answers:
     * the first is a failure, the second is a step. Returning both as the same
     * error leaves a client unable to prompt correctly.
     */
    AuthResponses.LoginResult login(AuthRequests.Login request);

    /**
     * Exchanges a refresh token for a new pair and rotates it.
     *
     * <p>Presenting a token that has already been rotated away ends the session
     * instead of refreshing it — see {@code UserSession} for why that is the
     * right response rather than an over-reaction.
     */
    AuthResponses.Tokens refresh(AuthRequests.Refresh request);

    /** Ends the one device holding this refresh token. */
    void logout(AuthRequests.Logout request);

    /** Ends every session this account has, including the one asking. */
    int logoutAll(Long userId);

    // ── Proving an address ───────────────────────────────────────────────────

    void verifyEmail(AuthRequests.VerifyEmail request);

    void resendVerificationEmail(AuthRequests.ResendVerification request);

    AuthResponses.PhoneChallenge requestPhoneVerification(Long userId, AuthRequests.PhoneVerificationRequest request);

    AuthResponses.Profile confirmPhoneVerification(Long userId, AuthRequests.PhoneVerificationConfirm request);

    // ── Passwords ────────────────────────────────────────────────────────────

    void forgotPassword(AuthRequests.ForgotPassword request);

    void resetPassword(AuthRequests.ResetPassword request);

    /** Changes a password and, unless told otherwise, signs every other device out. */
    void changePassword(Long userId, Long currentSessionId, AuthRequests.ChangePassword request);

    // ── Multi-factor ─────────────────────────────────────────────────────────

    /** Issues a secret. Multi-factor is not on until a code proves the app holds it. */
    AuthResponses.MfaSetup beginMfaSetup(Long userId);

    /** Turns it on and returns the recovery codes, once. */
    AuthResponses.MfaActivation activateMfa(Long userId, Long currentSessionId, AuthRequests.MfaActivate request);

    void disableMfa(Long userId, AuthRequests.MfaConfirm request);

    /** Replaces the whole set; any code from the previous batch stops working. */
    AuthResponses.MfaActivation regenerateRecoveryCodes(Long userId, AuthRequests.MfaConfirm request);

    // ── External identity ────────────────────────────────────────────────────

    /** Exchanges a provider's authorisation code, linking or creating an account. */
    AuthResponses.Tokens completeOAuthLogin(String provider, AuthRequests.OAuthCallback request);
}
