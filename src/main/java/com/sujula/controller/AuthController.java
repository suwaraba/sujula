package com.sujula.controller;

import com.sujula.dto.request.auth.AuthRequests;
import com.sujula.dto.response.auth.AuthResponses;
import com.sujula.service.auth.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Getting in, staying in, and getting out.
 *
 * <p>Every method here is three lines or fewer, and that is the design. The
 * controller's whole job is to bind a validated DTO, name the caller from the
 * authenticated principal, and hand both to the service — no entity reaches it,
 * no rule is decided in it, and nothing it does would need to change if the
 * persistence model did.
 *
 * <p>Which endpoints are public is decided in {@code SecurityConfig}, not here:
 * registration, sign-in, refresh, email verification, password recovery and the
 * OAuth callback all have to work before there is an identity to check.
 */
@RestController
@RequestMapping("/auth")
@Tag(name = "auth", description = "Registration, sign-in, tokens, multi-factor and recovery")
public class AuthController {

    private final AuthService authService;
    private final AuthenticatedCaller caller;

    public AuthController(AuthService authService, AuthenticatedCaller caller) {
        this.authService = authService;
        this.caller = caller;
    }

    // ── Getting in ───────────────────────────────────────────────────────────

    @PostMapping("/register")
    @Operation(summary = "Create a customer account and sign in",
               description = "Always creates a CUSTOMER. Selling and staff roles are granted by approval.")
    public ResponseEntity<AuthResponses.Tokens> register(@Valid @RequestBody AuthRequests.Register request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    @Operation(summary = "Sign in",
               description = "Returns tokens, or mfaRequired=true when a second factor is still needed. "
                       + "The second is not an error: the password was correct.")
    public ResponseEntity<AuthResponses.LoginResult> login(@Valid @RequestBody AuthRequests.Login request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a refresh token for a new pair",
               description = "Single use. The token presented is rotated away; presenting a spent one "
                       + "is treated as theft and ends the session.")
    public ResponseEntity<AuthResponses.Tokens> refresh(@Valid @RequestBody AuthRequests.Refresh request) {
        return ResponseEntity.ok(authService.refresh(request));
    }

    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Sign out this device")
    public ResponseEntity<Void> logout(@Valid @RequestBody AuthRequests.Logout request) {
        authService.logout(request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout-all")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Sign out everywhere, including here")
    public ResponseEntity<AuthResponses.Ack> logoutAll(Authentication authentication) {
        int ended = authService.logoutAll(caller.userId(authentication));
        return ResponseEntity.ok(AuthResponses.Ack.of("Signed out of " + ended + " session(s)"));
    }

    // ── Proving an address ───────────────────────────────────────────────────

    @PostMapping("/verify-email")
    @Operation(summary = "Confirm an email address from its link")
    public ResponseEntity<Void> verifyEmail(@Valid @RequestBody AuthRequests.VerifyEmail request) {
        authService.verifyEmail(request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/verify-email/resend")
    @Operation(summary = "Send the verification link again",
               description = "Answers identically whether or not the address exists.")
    public ResponseEntity<Void> resendVerification(@Valid @RequestBody AuthRequests.ResendVerification request) {
        authService.resendVerificationEmail(request);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/verify-phone/request")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Send a code to a phone number",
               description = "Rate limited per account. The number is claimed here and written to the "
                       + "profile only once the code is confirmed.")
    public ResponseEntity<AuthResponses.PhoneChallenge> requestPhoneVerification(
            Authentication authentication,
            @Valid @RequestBody AuthRequests.PhoneVerificationRequest request) {
        return ResponseEntity.ok(
                authService.requestPhoneVerification(caller.userId(authentication), request));
    }

    @PostMapping("/verify-phone/confirm")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Confirm a phone code")
    public ResponseEntity<AuthResponses.Profile> confirmPhoneVerification(
            Authentication authentication,
            @Valid @RequestBody AuthRequests.PhoneVerificationConfirm request) {
        return ResponseEntity.ok(
                authService.confirmPhoneVerification(caller.userId(authentication), request));
    }

    // ── Passwords ────────────────────────────────────────────────────────────

    @PostMapping("/password/forgot")
    @Operation(summary = "Start password recovery",
               description = "Answers identically whether or not the address exists.")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody AuthRequests.ForgotPassword request) {
        authService.forgotPassword(request);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/password/reset")
    @Operation(summary = "Set a new password from a reset link",
               description = "Ends every session: a reset is what someone does when they believe the "
                       + "account is compromised.")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody AuthRequests.ResetPassword request) {
        authService.resetPassword(request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password/change")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Change a password while signed in",
               description = "Signs every other device out unless keepOtherSessions is set.")
    public ResponseEntity<Void> changePassword(Authentication authentication,
                                               @Valid @RequestBody AuthRequests.ChangePassword request) {
        authService.changePassword(caller.userId(authentication), caller.sessionId(), request);
        return ResponseEntity.noContent().build();
    }

    // ── Multi-factor ─────────────────────────────────────────────────────────

    @PostMapping("/mfa/setup")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Begin authenticator enrolment",
               description = "Returns a secret and an otpauth:// URI. Not yet enabled — activate proves "
                       + "the app holds it, so an abandoned setup cannot lock the account.")
    public ResponseEntity<AuthResponses.MfaSetup> beginMfaSetup(Authentication authentication) {
        return ResponseEntity.ok(authService.beginMfaSetup(caller.userId(authentication)));
    }

    @PostMapping("/mfa/activate")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Turn multi-factor on",
               description = "Returns recovery codes once. Only their hashes are kept.")
    public ResponseEntity<AuthResponses.MfaActivation> activateMfa(
            Authentication authentication, @Valid @RequestBody AuthRequests.MfaActivate request) {
        return ResponseEntity.ok(
                authService.activateMfa(caller.userId(authentication), caller.sessionId(), request));
    }

    @DeleteMapping("/mfa")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Turn multi-factor off", description = "Re-checks the password.")
    public ResponseEntity<Void> disableMfa(Authentication authentication,
                                           @Valid @RequestBody AuthRequests.MfaConfirm request) {
        authService.disableMfa(caller.userId(authentication), request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/mfa/recovery-codes")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Replace the recovery codes",
               description = "Every code from the previous batch stops working.")
    public ResponseEntity<AuthResponses.MfaActivation> regenerateRecoveryCodes(
            Authentication authentication, @Valid @RequestBody AuthRequests.MfaConfirm request) {
        return ResponseEntity.ok(
                authService.regenerateRecoveryCodes(caller.userId(authentication), request));
    }

    // ── External identity ────────────────────────────────────────────────────

    @PostMapping("/oauth/{provider}/callback")
    @Operation(summary = "Complete a Google or Apple sign-in",
               description = "The authorisation code is exchanged server-side; the client secret never "
                       + "leaves the server. Identity comes from the provider's subject claim, never "
                       + "from the email.")
    public ResponseEntity<AuthResponses.Tokens> oauthCallback(
            @PathVariable String provider, @Valid @RequestBody AuthRequests.OAuthCallback request) {
        return ResponseEntity.ok(authService.completeOAuthLogin(provider, request));
    }
}
