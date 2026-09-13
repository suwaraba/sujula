package com.sujula.service.security;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.sujula.model.user.User;
import com.sujula.service.auth.AuthProperties;
import com.sujula.service.auth.TotpService;

import lombok.extern.slf4j.Slf4j;

/**
 * Makes the caller prove, again and now, that they are who the token says.
 *
 * <p>A bearer token answers "was somebody holding this credential an hour ago".
 * For most of an API that is the right question. For the handful of operations
 * that redirect money or hand somebody else the keys it is not: a laptop left
 * open, a session lifted off a shared phone, or a stolen token all pass that
 * test, and the loss is not a wrong shipping address but a vendor's entire
 * settlement stream pointed at another account.
 *
 * <p>So these operations ask for the password again, and for the authenticator
 * code as well when the account has one. Asking only for the password of an
 * MFA-protected account would make the sensitive endpoint weaker than the sign-in
 * that reached it, which is the failure mode worth naming: a step-up that steps
 * down.
 *
 * <h2>Why this is not a token</h2>
 *
 * <p>A short-lived "step-up token" minted by one endpoint and presented to
 * another is the more familiar shape, and it is a second bearer credential to
 * store, transport and leak. Re-verifying inside the request that does the
 * dangerous thing keeps the proof and the act in one transaction, where they
 * cannot come apart.
 */
@Slf4j
@Service
public class StepUpVerifier {

    /**
     * Compared against when there is no password to compare against, so that an
     * account with no local credential costs the same time as one with.
     */
    private static final String TIMING_EQUALISER =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final PasswordEncoder passwordEncoder;
    private final TotpService totpService;
    private final AuthProperties properties;

    public StepUpVerifier(PasswordEncoder passwordEncoder, TotpService totpService,
                          AuthProperties properties) {
        this.passwordEncoder = passwordEncoder;
        this.totpService = totpService;
        this.properties = properties;
    }

    /**
     * Verifies the confirmation the caller sent, or throws.
     *
     * @param user     the signed-in account, already loaded
     * @param password what they typed into the confirmation box
     * @param totpCode their authenticator code, required when MFA is enabled
     * @param what     the operation, for the log line and the message
     *
     * @throws BadCredentialsException if either factor is wrong or missing.
     *         One exception for both, with one message: telling somebody their
     *         password was right and only the code was wrong confirms the
     *         password.
     */
    public void verify(User user, String password, String totpCode, String what) {
        if (user == null) {
            throw new BadCredentialsException("Confirm your password to continue.");
        }

        boolean passwordOk = user.getPassword() != null
                && password != null
                && passwordEncoder.matches(password, user.getPassword());

        if (user.getPassword() == null || password == null) {
            // Burn the same time either way. An account created through OAuth has
            // no password, and a faster refusal would say so.
            passwordEncoder.matches(password == null ? "" : password, TIMING_EQUALISER);
        }

        boolean secondFactorOk = !user.isTotpEnabled()
                || (totpCode != null && !totpCode.isBlank()
                    && totpService.verify(user.getTotpSecret(), totpCode,
                            properties.getMfa().getAllowedDriftSteps()));

        if (!passwordOk || !secondFactorOk) {
            log.warn("[StepUp] Refused {} for user {}", what, user.getId());
            throw new BadCredentialsException(
                    user.isTotpEnabled()
                            ? "Confirm your password and authenticator code to " + what + "."
                            : "Confirm your password to " + what + ".");
        }

        log.info("[StepUp] User {} re-authenticated to {}", user.getId(), what);
    }

    /**
     * Whether this account will be asked for an authenticator code as well.
     *
     * <p>For the client, so the confirmation dialog has the right number of
     * boxes in it before the request is sent rather than after it is refused.
     */
    public boolean requiresSecondFactor(User user) {
        return user != null && user.isTotpEnabled();
    }
}
