package com.sujula.controller;

import com.sujula.dto.request.auth.MeRequests;
import com.sujula.dto.response.auth.AuthResponses;
import com.sujula.service.auth.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The signed-in account.
 *
 * <p>There is no account id anywhere in these paths, and that is the design.
 * Every operation resolves its subject from the authenticated principal, so a
 * caller cannot address another account — not by changing a parameter, because
 * there is no parameter to change. The single id that does appear,
 * {@code /me/sessions/{id}}, is resolved with the owner inside the query.
 */
@RestController
@RequestMapping("/me")
@PreAuthorize("isAuthenticated()")
@Tag(name = "me", description = "The signed-in account: profile, devices, permissions, data rights")
public class MeController {

    private final AccountService accountService;
    private final AuthenticatedCaller caller;

    public MeController(AccountService accountService, AuthenticatedCaller caller) {
        this.accountService = accountService;
        this.caller = caller;
    }

    @GetMapping
    @Operation(summary = "Profile, roles, permissions and resolved currency",
               description = "Resolved currency is the stated preference where there is one, otherwise "
                       + "inferred from the country the account was last seen from.")
    public ResponseEntity<AuthResponses.Me> me(Authentication authentication) {
        return ResponseEntity.ok(accountService.getMe(caller.userId(authentication)));
    }

    @PatchMapping
    @Operation(summary = "Update the profile",
               description = "Partial: only the fields sent are changed. Email, phone and role are not "
                       + "here — the first two are proved through verification, the third is granted.")
    public ResponseEntity<AuthResponses.Profile> updateMe(
            Authentication authentication, @Valid @RequestBody MeRequests.UpdateProfile request) {
        return ResponseEntity.ok(accountService.updateMe(caller.userId(authentication), request));
    }

    @DeleteMapping
    @Operation(summary = "Ask for the account to be erased",
               description = "Idempotent: asking again while one is open returns the open request. "
                       + "Erasure is pseudonymisation — financial records are kept and the personal "
                       + "data in them is overwritten.")
    public ResponseEntity<AuthResponses.DataRequest> requestErasure(Authentication authentication) {
        return ResponseEntity.accepted()
                .body(accountService.requestErasure(caller.userId(authentication)));
    }

    @PostMapping("/export")
    @Operation(summary = "Ask for a copy of everything held",
               description = "Runs as a job. Poll GET /me/export for the download link.")
    public ResponseEntity<AuthResponses.DataRequest> requestExport(Authentication authentication) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(accountService.requestExport(caller.userId(authentication)));
    }

    @GetMapping("/export")
    @Operation(summary = "The state of the latest export",
               description = "Carries a time-limited download URL once the job has finished.")
    public ResponseEntity<AuthResponses.DataRequest> latestExport(Authentication authentication) {
        return ResponseEntity.ok(accountService.latestExport(caller.userId(authentication)));
    }

    @GetMapping("/sessions")
    @Operation(summary = "Every device signed in to this account",
               description = "The session making the request is marked current.")
    public ResponseEntity<List<AuthResponses.Session>> sessions(Authentication authentication) {
        return ResponseEntity.ok(
                accountService.listSessions(caller.userId(authentication), caller.sessionId()));
    }

    @DeleteMapping("/sessions/{sessionId}")
    @Operation(summary = "Sign a device out remotely",
               description = "Takes effect on that device's next request, not when its access token "
                       + "expires. Another account's session is reported as not found.")
    public ResponseEntity<Void> revokeSession(Authentication authentication,
                                              @PathVariable Long sessionId) {
        accountService.revokeSession(caller.userId(authentication), sessionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/permissions")
    @Operation(summary = "What this account may do",
               description = "Resolved from role and standing — a vendor pending approval cannot list "
                       + "products, and this says so. Advisory: every endpoint still enforces its own "
                       + "rules.")
    public ResponseEntity<AuthResponses.Permissions> permissions(Authentication authentication) {
        return ResponseEntity.ok(accountService.permissions(caller.userId(authentication)));
    }
}
