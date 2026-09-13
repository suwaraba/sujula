package com.sujula.service.security;

import com.sujula.model.user.User;
import com.sujula.service.auth.AuthProperties;
import com.sujula.service.auth.TotpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Re-proving who you are, before the API does something irreversible.
 *
 * <p>The case that matters most here is the one that is easy to get wrong in the
 * lenient direction: an account with an authenticator that is allowed through on
 * a password alone. That makes the sensitive endpoint weaker than the sign-in
 * which reached it — a step-up that steps down — and it is exactly what
 * happens if the second factor is checked with an "if the client sent a code"
 * condition instead of "if the account has one".
 */
class StepUpVerifierTest {

    private static final String SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

    private PasswordEncoder passwordEncoder;
    private TotpService totp;
    private StepUpVerifier stepUp;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        totp = new TotpService();
        AuthProperties properties = new AuthProperties();
        stepUp = new StepUpVerifier(passwordEncoder, totp, properties);
    }

    private User user(boolean mfa) {
        User user = new User();
        user.setId(900L);
        user.setPassword(passwordEncoder.encode("correct horse"));
        user.setTotpEnabled(mfa);
        user.setTotpSecret(mfa ? SECRET : null);
        return user;
    }

    private String currentCode() {
        return totp.generate(SECRET, System.currentTimeMillis() / 1000 / 30);
    }

    @Test
    void theRightPasswordPassesOnAnAccountWithoutMfa() {
        assertDoesNotThrow(() ->
                stepUp.verify(user(false), "correct horse", null, "change your payout details"));
    }

    @Test
    void aWrongPasswordIsRefused() {
        assertThrows(BadCredentialsException.class, () ->
                stepUp.verify(user(false), "correct horsé", null, "change your payout details"));
    }

    @Test
    void anMfaAccountIsNotLetThroughOnThePasswordAlone() {
        // The failure this class exists to prevent. Checking the code only when
        // the client bothered to send one makes the dangerous endpoint easier to
        // pass than the sign-in that reached it.
        assertThrows(BadCredentialsException.class, () ->
                stepUp.verify(user(true), "correct horse", null, "change your payout details"));
        assertThrows(BadCredentialsException.class, () ->
                stepUp.verify(user(true), "correct horse", "   ", "change your payout details"));
    }

    @Test
    void anMfaAccountPassesWithBothFactors() {
        assertDoesNotThrow(() ->
                stepUp.verify(user(true), "correct horse", currentCode(),
                        "change your payout details"));
    }

    @Test
    void aValidCodeDoesNotSubstituteForThePassword() {
        assertThrows(BadCredentialsException.class, () ->
                stepUp.verify(user(true), "wrong", currentCode(), "change your payout details"));
    }

    @Test
    void theRefusalDoesNotSayWhichFactorWasWrong() {
        BadCredentialsException wrongPassword = assertThrows(BadCredentialsException.class, () ->
                stepUp.verify(user(true), "wrong", currentCode(), "change your payout details"));
        BadCredentialsException wrongCode = assertThrows(BadCredentialsException.class, () ->
                stepUp.verify(user(true), "correct horse", "000000", "change your payout details"));

        // Telling somebody the password was right and only the code was wrong
        // confirms the password.
        assertEquals(wrongPassword.getMessage(), wrongCode.getMessage());
    }

    @Test
    void anAccountWithNoLocalPasswordCannotStepUpAtAll() {
        User oauthOnly = user(false);
        oauthOnly.setPassword(null);

        assertThrows(BadCredentialsException.class, () ->
                stepUp.verify(oauthOnly, "anything", null, "change your payout details"));
    }

    @Test
    void aNullUserIsRefusedRatherThanThrowingSomethingElse() {
        assertThrows(BadCredentialsException.class, () ->
                stepUp.verify(null, "correct horse", null, "change your payout details"));
    }

    @Test
    void theClientCanAskHowManyBoxesToDraw() {
        assertTrue(stepUp.requiresSecondFactor(user(true)));
        assertFalse(stepUp.requiresSecondFactor(user(false)));
        assertFalse(stepUp.requiresSecondFactor(null));
    }
}
