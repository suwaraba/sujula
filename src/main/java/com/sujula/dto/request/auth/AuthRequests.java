package com.sujula.dto.request.auth;

import jakarta.validation.constraints.*;

/**
 * Everything the {@code /auth} endpoints accept.
 *
 * <p>Grouped in one file because they are small, they are read together, and a
 * dozen four-line files makes the shape of the API harder to see, not easier.
 * Records because a request is a value: nothing should be able to mutate one
 * after validation has passed on it.
 */
public final class AuthRequests {

    private AuthRequests() {}

    /** Registration only ever creates a CUSTOMER. Roles are reached by approval, never by asking. */
    public record Register(
            @NotBlank @Email @Size(max = 150) String email,
            @NotBlank @Size(min = 10, max = 128,
                    message = "Password must be at least 10 characters")
            String password,
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @Size(max = 30) @Pattern(regexp = "^\\+?[0-9 ()-]{6,30}$|^$",
                    message = "Phone must be digits, optionally with a leading +")
            String phone,
            @Size(min = 3, max = 3) String preferredCurrency,
            @Size(max = 10) String preferredLanguage,
            @Size(max = 120) String deviceLabel) {}

    /**
     * Sign in.
     *
     * @param totpCode required only once multi-factor is on. Absent, the response
     *                 says so and issues nothing, rather than failing as if the
     *                 password were wrong — the client needs to know which of the
     *                 two it is in order to prompt.
     */
    public record Login(
            @NotBlank @Email String email,
            @NotBlank String password,
            @Pattern(regexp = "^[0-9 ]{6,8}$|^$", message = "Authenticator code is six digits")
            String totpCode,
            @Size(max = 64) String recoveryCode,
            @Size(max = 120) String deviceLabel) {}

    public record Refresh(@NotBlank String refreshToken) {}

    /** Signing out needs the refresh token, so the right device is the one that ends. */
    public record Logout(@NotBlank String refreshToken) {}

    public record VerifyEmail(@NotBlank String token) {}

    public record ResendVerification(@NotBlank @Email String email) {}

    /** The number to prove. Confirming writes it to the profile, so it is claimed here. */
    public record PhoneVerificationRequest(
            @NotBlank @Pattern(regexp = "^\\+?[0-9 ()-]{6,30}$",
                    message = "Phone must be digits, optionally with a leading +")
            @Size(max = 30) String phone) {}

    public record PhoneVerificationConfirm(
            @NotBlank @Pattern(regexp = "^[0-9]{4,8}$", message = "The code is digits only")
            String code) {}

    public record ForgotPassword(@NotBlank @Email String email) {}

    public record ResetPassword(
            @NotBlank String token,
            @NotBlank @Size(min = 10, max = 128,
                    message = "Password must be at least 10 characters")
            String newPassword) {}

    /**
     * Change a password while signed in.
     *
     * @param keepOtherSessions changing a password is how someone reacts to
     *                          believing their account is compromised, so every
     *                          other device is signed out unless they say not to
     */
    public record ChangePassword(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 10, max = 128,
                    message = "Password must be at least 10 characters")
            String newPassword,

            /**
             * Boxed rather than primitive, and not cosmetic: a missing primitive
             * makes this binder reject the whole body as "not valid JSON", so a
             * client that simply omitted this optional flag could not change
             * their password at all.
             */
            Boolean keepOtherSessions) {

        /** Absent means the safe answer: sign every other device out. */
        public boolean keepOthers() {
            return Boolean.TRUE.equals(keepOtherSessions);
        }
    }

    /** Proves the authenticator was set up before it is trusted to guard the account. */
    public record MfaActivate(
            @NotBlank @Pattern(regexp = "^[0-9 ]{6,8}$", message = "Authenticator code is six digits")
            String code) {}

    /**
     * Turning multi-factor off, or minting new recovery codes.
     *
     * <p>Both re-check the password. Otherwise a borrowed unlocked phone is
     * enough to strip the second factor from the account, which defeats having
     * had one.
     */
    public record MfaConfirm(@NotBlank String password) {}

    /**
     * The authorisation code from the provider, exchanged server-side.
     *
     * @param codeVerifier PKCE. Required from public clients, where the code can
     *                     be intercepted on the way back and the verifier is the
     *                     only thing proving the exchange comes from whoever
     *                     started the flow.
     */
    public record OAuthCallback(
            @NotBlank String code,
            @Size(max = 500) String redirectUri,
            @Size(max = 200) String codeVerifier,
            @Size(max = 120) String deviceLabel) {}
}
