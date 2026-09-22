package com.sujula.controller;

import com.sujula.dto.request.UserRequest;
import com.sujula.dto.request.user.BlockUserRequest;
import com.sujula.dto.request.user.ChangePasswordRequest;
import com.sujula.dto.request.user.ForgotPasswordRequest;
import com.sujula.dto.request.user.LoginRequest;
import com.sujula.dto.request.user.ResendVerificationRequest;
import com.sujula.dto.request.user.ResetPasswordRequest;
import com.sujula.dto.request.user.UpdatePreferencesRequest;
import com.sujula.dto.request.user.VerifyEmailRequest;
import com.sujula.dto.request.user.VerifyPasswordRequest;
import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.user.UserResponse;
import com.sujula.model.constant.UserRole;
import com.sujula.service.UserService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


/**
 * Accounts, on the older surface. {@code /auth} and {@code /me} are the
 * replacements; this remains because clients still call it.
 *
 * <h2>Why every rule here is {@code @PreAuthorize}</h2>
 *
 * <p>{@code @PostAuthorize} evaluates <em>after</em> the annotated method has
 * returned, and the transaction lives on the service method — so it has already
 * committed by the time the check runs. A refusal at that point answers 403 and
 * leaves the write in the database, which is not a refusal at all. Every rule
 * below is therefore evaluated before the call.
 *
 * <p>Nothing is lost by the move: not one of these expressions reads
 * {@code returnObject}. They test the caller's role, or compare the caller
 * against {@code #id} — which is a method argument and is available before the
 * method runs.
 *
 * <p>{@code @PostAuthorize} remains correct for a <em>read</em> whose permission
 * depends on the row that came back. There is no such case on this class.
 *
 * <p>The annotations are not the only lock. {@code SecurityConfig} carries path
 * rules over the administrative routes here, because an annotation is a thing
 * that can be left off the one endpoint that mattered.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    // --- Public, unauthenticated flows -------------------------------------------------

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody UserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.save(request));
    }

    /**
     * Signs a returning user in. The session cookie the response carries is what
     * authenticates every later request, so there is nothing to store client-side.
     */
    @PostMapping("/login")
    public ResponseEntity<UserResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(userService.login(request.getEmail(), request.getPassword()));
    }

    @PostMapping("/password/forgot")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        userService.requestPasswordReset(request.getEmail());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/password/reset")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(request.getToken(), request.getNewPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/verify-email")
    public ResponseEntity<Void> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        userService.verifyEmail(request.getToken());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        userService.resendVerificationEmail(request.getEmail());
        return ResponseEntity.accepted().build();
    }

    // --- Authenticated self-service -----------------------------------------------------

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserResponse> getCurrentUser() {
        return ResponseEntity.ok(userService.getCurrentUser(null));
    }

    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> logout() {
        userService.logout(null);
        return ResponseEntity.noContent().build();
    }

    // --- Self or admin -------------------------------------------------------------------

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or @userSecurity.isSelf(authentication, #id)")
    public ResponseEntity<UserResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(userService.getCurrentUser(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or @userSecurity.isSelf(authentication, #id)")
    public ResponseEntity<UserResponse> update(@PathVariable Long id, @Valid @RequestBody UserRequest request) {
        return ResponseEntity.ok(userService.updateUser(id, request));
    }

    @PutMapping("/{id}/password")
    @PreAuthorize("hasRole('ADMIN') or @userSecurity.isSelf(authentication, #id)")
    public ResponseEntity<Void> changePassword(@PathVariable Long id, @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(id, request.getCurrentPassword(), request.getNewPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/password/verify")
    @PreAuthorize("hasRole('ADMIN') or @userSecurity.isSelf(authentication, #id)")
    public ResponseEntity<Boolean> verifyPassword(@PathVariable Long id, @Valid @RequestBody VerifyPasswordRequest request) {
        return ResponseEntity.ok(userService.verifyPassword(id, request.getPassword()));
    }

    @PatchMapping("/{id}/preferences")
    @PreAuthorize("hasRole('ADMIN') or @userSecurity.isSelf(authentication, #id)")
    public ResponseEntity<UserResponse> updatePreferences(@PathVariable Long id, @RequestBody UpdatePreferencesRequest request) {
        return ResponseEntity.ok(userService.updatePreferences(id, request.getPreferredCurrency(), request.getPreferredLanguage()));
    }

    // --- Admin only ------------------------------------------------------------------------

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PagedResponse<UserResponse>> findAll(
            @RequestParam UserRole role,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PagedResponse.of(userService.findAll(role, PageRequest.of(page, size))));
    }

    @GetMapping("/by-email")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> findByEmail(@RequestParam String email) {
        return ResponseEntity.ok(userService.findByEmail(email));
    }

    @PatchMapping("/{id}/block")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> block(@PathVariable Long id, @RequestBody BlockUserRequest request) {
        return ResponseEntity.ok(userService.blockUser(id, request.isBlocked(), request.isFraud()));
    }

    @PatchMapping("/{id}/unblock")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> unblock(@PathVariable Long id) {
        return ResponseEntity.ok(userService.unblockUser(id));
    }

    @PatchMapping("/{id}/unlock")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> unlock(@PathVariable Long id) {
        return ResponseEntity.ok(userService.unlockUser(id));
    }

    @PatchMapping("/{id}/fraud")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> markFraud(@PathVariable Long id, @RequestParam boolean fraud) {
        return ResponseEntity.ok(userService.markFraud(id, fraud));
    }

    @PatchMapping("/{id}/enable")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> enable(@PathVariable Long id) {
        return ResponseEntity.ok(userService.enableUser(id));
    }

    @PatchMapping("/{id}/disable")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> disable(@PathVariable Long id) {
        return ResponseEntity.ok(userService.disableUser(id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteById(@PathVariable Long id) {
        userService.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/permanent")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deletePermanently(@PathVariable Long id) {
        userService.deleteAccountPermanently(id);
        return ResponseEntity.noContent().build();
    }
}
