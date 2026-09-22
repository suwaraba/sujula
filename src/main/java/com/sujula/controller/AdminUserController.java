package com.sujula.controller;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sujula.dto.request.admin.AdminUserRequests;
import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.admin.AdminUserResponses;
import com.sujula.model.constant.UserRole;
import com.sujula.service.admin.AdminUserService;
import com.sujula.service.idempotency.IdempotencyService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Accounts, as an administrator sees them.
 *
 * <p>Two roles reach this controller and they do different things. Support reads
 * — the search and the profile — and answers people; an administrator decides.
 * Every read calls {@code staff}, every write calls {@code decider}, and the
 * method name on each endpoint is therefore visible evidence of which it is.
 *
 * <p><strong>Nothing here sets a flag on an account.</strong> Locking and
 * unlocking go through the sanction registry, so every account that cannot sign
 * in has a row naming who decided that, why, and until when — and the person who
 * will ask for it is the one it was done to.
 */
@RestController
@RequestMapping("/admin/users")
@PreAuthorize("isAuthenticated()")
@Tag(name = "admin-users", description = "Accounts, sanctions, roles and impersonation")
public class AdminUserController {

    private static final String CREATE = "admin.users.create";
    private static final String ACTIVATE = "admin.users.activate";
    private static final String DEACTIVATE = "admin.users.deactivate";
    private static final String SUSPEND = "admin.users.suspend";
    private static final String ROLES = "admin.users.roles";
    private static final String LOGOUT = "admin.users.force-logout";
    private static final String RESET_MFA = "admin.users.reset-mfa";
    private static final String IMPERSONATE = "admin.users.impersonate";

    private final AdminUserService adminUsers;
    private final StaffCaller staff;
    private final IdempotencyService idempotency;

    public AdminUserController(AdminUserService adminUsers, StaffCaller staff,
                               IdempotencyService idempotency) {
        this.adminUsers = adminUsers;
        this.staff = staff;
        this.idempotency = idempotency;
    }

    // ── Reads: support as well as administrators ─────────────────────────────

    @GetMapping
    @Operation(summary = "Search accounts",
               description = "Every filter is optional and the text match is deliberately narrow — "
                       + "name, email and phone, not a sweep of every column, so an agent looking "
                       + "for \"Fatou\" does not get every order note that mentions her. "
                       + "lockedOut reads the sanction rows rather than the cached flag, so it "
                       + "cannot disagree with what happens when the person tries to sign in.")
    public ResponseEntity<PagedResponse<AdminUserResponses.UserRow>> search(
            Authentication authentication,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UserRole role,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) Boolean blocked,
            @RequestParam(required = false) Boolean lockedOut,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok()
                // Private and brief: these rows carry names, phone numbers and
                // email addresses, and a shared cache holding them would serve
                // one agent's search to the next person through the proxy.
                .cacheControl(CacheControl.noStore())
                .body(adminUsers.search(staff.staff(authentication), q, role, country, blocked,
                        lockedOut, paged(page, size)));
    }

    @GetMapping("/{userId}")
    @Operation(summary = "One account, with the shape of its history",
               description = "The activity summary is gathered here rather than left to six "
                       + "separate calls, because an agent who has to make six makes one and "
                       + "decides on it. Money is per currency and never summed — 400 EUR and "
                       + "12,000 GMD is not 12,400 of anything. No credential of any kind appears "
                       + "on this screen.")
    public ResponseEntity<AdminUserResponses.UserDetail> detail(
            Authentication authentication, @PathVariable Long userId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(adminUsers.detail(staff.staff(authentication), userId));
    }

    // ── Writes: administrators only ──────────────────────────────────────────

    @PostMapping
    @Operation(summary = "Create an actor by hand",
               description = "For what self-registration cannot serve: a driver signed up at a "
                       + "desk, a counter operator with no email of their own. No password is "
                       + "accepted or returned — one is minted and they are sent a link to set "
                       + "their own, because a password an administrator chose is one an "
                       + "administrator knows.")
    public ResponseEntity<AdminUserResponses.UserCreated> create(
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminUserRequests.CreateUser request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), CREATE), key, request,
                HttpStatus.CREATED.value(), AdminUserResponses.UserCreated.class,
                () -> adminUsers.create(acting, request)));
    }

    @PatchMapping("/{userId}")
    @Operation(summary = "Edit a profile on somebody's behalf",
               description = "Audited with the reason. Changing a phone number clears its "
                       + "verification: carrying that across would let an administrator hand "
                       + "somebody a verified number they do not hold.")
    public ResponseEntity<AdminUserResponses.UserDetail> patch(
            Authentication authentication, @PathVariable Long userId,
            @Valid @RequestBody AdminUserRequests.PatchUser request) {
        return ResponseEntity.ok(
                adminUsers.patch(staff.decider(authentication), userId, request));
    }

    @PostMapping("/{userId}/activate")
    @Operation(summary = "Lift whatever is holding an account out",
               description = "Idempotent by nature as well as by key: an account that was not "
                       + "locked answers plainly rather than failing, because two agents "
                       + "answering the same complaint should not see an error.")
    public ResponseEntity<AdminUserResponses.AccountChanged> activate(
            Authentication authentication, @PathVariable Long userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminUserRequests.Activate request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), ACTIVATE + ":" + userId), key, request,
                200, AdminUserResponses.AccountChanged.class,
                () -> adminUsers.activate(acting, userId, request)));
    }

    @PostMapping("/{userId}/deactivate")
    @Operation(summary = "Shut an account indefinitely",
               description = "Issues a sanction and ends every session — otherwise the lock would "
                       + "only take effect the next time they were asked to log in, which for a "
                       + "phone app is never. The reason is required and the person is told it.")
    public ResponseEntity<AdminUserResponses.AccountChanged> deactivate(
            Authentication authentication, @PathVariable Long userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminUserRequests.Deactivate request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), DEACTIVATE + ":" + userId), key,
                request, 200, AdminUserResponses.AccountChanged.class,
                () -> adminUsers.deactivate(acting, userId, request)));
    }

    @PostMapping("/{userId}/suspend")
    @Operation(summary = "Shut an account for a stated number of days",
               description = "A duration rather than a date: an administrator thinks in \"a "
                       + "week\", and a date sent from a client is one that can be off by a "
                       + "timezone nobody converted. It ends by itself — no job has to run for "
                       + "the account to come back.")
    public ResponseEntity<AdminUserResponses.AccountChanged> suspend(
            Authentication authentication, @PathVariable Long userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminUserRequests.Suspend request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), SUSPEND + ":" + userId), key, request,
                200, AdminUserResponses.AccountChanged.class,
                () -> adminUsers.suspend(acting, userId, request)));
    }

    @PostMapping("/{userId}/roles")
    @Operation(summary = "Change what kind of actor an account is",
               description = "Ends every session, because a role lives in the access token: a "
                       + "user demoted with a live token is still what they were until it "
                       + "expires, and that is the direction that matters. An account that runs a "
                       + "store is refused — suspend the store instead, which has a cascade this "
                       + "does not.")
    public ResponseEntity<AdminUserResponses.RoleChanged> changeRole(
            Authentication authentication, @PathVariable Long userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminUserRequests.ChangeRole request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), ROLES + ":" + userId), key, request,
                200, AdminUserResponses.RoleChanged.class,
                () -> adminUsers.changeRole(acting, userId, request)));
    }

    @PostMapping("/{userId}/force-logout")
    @Operation(summary = "End every session an account has open")
    public ResponseEntity<AdminUserResponses.SessionsEnded> forceLogout(
            Authentication authentication, @PathVariable Long userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminUserRequests.ForceLogout request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), LOGOUT + ":" + userId), key, request,
                200, AdminUserResponses.SessionsEnded.class,
                () -> adminUsers.forceLogout(acting, userId, request)));
    }

    @PostMapping("/{userId}/reset-mfa")
    @Operation(summary = "Clear somebody's second factor",
               description = "Step-up on the administrator's own password and code first. This is "
                       + "the endpoint an attacker who has reached an admin account wants most, "
                       + "because it turns one takeover into a takeover of anybody. Their "
                       + "sessions go too, or the account spends a month with no second factor "
                       + "and a live token.")
    public ResponseEntity<AdminUserResponses.MfaReset> resetMfa(
            Authentication authentication, @PathVariable Long userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminUserRequests.ResetMfa request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(idempotency.execute(
                        IdempotencyService.scopeFor(acting.getId(), RESET_MFA + ":" + userId), key,
                        request, 200, AdminUserResponses.MfaReset.class,
                        () -> adminUsers.resetMfa(acting, userId, request)));
    }

    @PostMapping("/{userId}/impersonate")
    @Operation(summary = "Open a short session as somebody else",
               description = "The most invasive thing this surface can do: it reads their "
                       + "messages, their addresses and their orders. Time-boxed and not "
                       + "extendable, flagged in the token so every client shows a banner, "
                       + "visible in the user's own device list, and audited with the reason — "
                       + "which is the whole difference between support work and snooping. "
                       + "Impersonating another member of staff is refused outright.")
    public ResponseEntity<AdminUserResponses.ImpersonationOpened> impersonate(
            Authentication authentication, @PathVariable Long userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminUserRequests.Impersonate request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(idempotency.execute(
                        IdempotencyService.scopeFor(acting.getId(), IMPERSONATE + ":" + userId),
                        key, request, 200, AdminUserResponses.ImpersonationOpened.class,
                        () -> adminUsers.impersonate(acting, userId, request)));
    }

    private static Pageable paged(int page, int size) {
        return PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
    }
}
