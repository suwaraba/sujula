package com.sujula.controller;

import com.sujula.dto.request.UserRequest;
import com.sujula.dto.request.user.BlockUserRequest;
import com.sujula.dto.request.user.ChangePasswordRequest;
import com.sujula.dto.request.user.ForgotPasswordRequest;
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
import org.springframework.security.access.prepost.PostAuthorize;
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
    @PostAuthorize("hasRole('ADMIN') or @userSecurity.isSelf(authentication, #id)")
    public ResponseEntity<UserResponse> update(@PathVariable Long id, @Valid @RequestBody UserRequest request) {
        return ResponseEntity.ok(userService.updateUser(id, request));
    }

    @PutMapping("/{id}/password")
    @PostAuthorize("hasRole('ADMIN') or @userSecurity.isSelf(authentication, #id)")
    public ResponseEntity<Void> changePassword(@PathVariable Long id, @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(id, request.getCurrentPassword(), request.getNewPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/password/verify")
    @PostAuthorize("hasRole('ADMIN') or @userSecurity.isSelf(authentication, #id)")
    public ResponseEntity<Boolean> verifyPassword(@PathVariable Long id, @Valid @RequestBody VerifyPasswordRequest request) {
        return ResponseEntity.ok(userService.verifyPassword(id, request.getPassword()));
    }

    @PatchMapping("/{id}/preferences")
    @PostAuthorize("hasRole('ADMIN') or @userSecurity.isSelf(authentication, #id)")
    public ResponseEntity<UserResponse> updatePreferences(@PathVariable Long id, @RequestBody UpdatePreferencesRequest request) {
        return ResponseEntity.ok(userService.updatePreferences(id, request.getPreferredCurrency(), request.getPreferredLanguage()));
    }

    // --- Admin only ------------------------------------------------------------------------

    @GetMapping
    @PostAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PagedResponse<UserResponse>> findAll(
            @RequestParam UserRole role,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PagedResponse.of(userService.findAll(role, PageRequest.of(page, size))));
    }

    @GetMapping("/by-email")
    @PostAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> findByEmail(@RequestParam String email) {
        return ResponseEntity.ok(userService.findByEmail(email));
    }

    @PatchMapping("/{id}/block")
    @PostAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> block(@PathVariable Long id, @RequestBody BlockUserRequest request) {
        return ResponseEntity.ok(userService.blockUser(id, request.isBlocked(), request.isFraud()));
    }

    @PatchMapping("/{id}/unblock")
    @PostAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> unblock(@PathVariable Long id) {
        return ResponseEntity.ok(userService.unblockUser(id));
    }

    @PatchMapping("/{id}/fraud")
    @PostAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> markFraud(@PathVariable Long id, @RequestParam boolean fraud) {
        return ResponseEntity.ok(userService.markFraud(id, fraud));
    }

    @PatchMapping("/{id}/enable")
    @PostAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> enable(@PathVariable Long id) {
        return ResponseEntity.ok(userService.enableUser(id));
    }

    @PatchMapping("/{id}/disable")
    @PostAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> disable(@PathVariable Long id) {
        return ResponseEntity.ok(userService.disableUser(id));
    }

    @DeleteMapping("/{id}")
    @PostAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteById(@PathVariable Long id) {
        userService.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/permanent")
    @PostAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deletePermanently(@PathVariable Long id) {
        userService.deleteAccountPermanently(id);
        return ResponseEntity.noContent().build();
    }
}
