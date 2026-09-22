package com.sujula.controller;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sujula.dto.request.notification.NotificationRequests;
import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.notification.NotificationResponses;
import com.sujula.service.notification.NotificationInboxService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * The signed-in user's own inbox, settings and phones.
 *
 * <p>No user id appears in any path. Every endpoint takes its subject from the
 * session, so there is no parameter a caller could change to reach somebody
 * else's inbox — a stronger guarantee than a check, which can be missing from
 * the one endpoint that matters.
 *
 * <p>The preference matrix is what makes the rest of this real. Five events
 * cannot be switched off, each because silence would cost somebody something
 * they cannot get back, and the lock carries its reason so a client renders an
 * explanation rather than a switch that silently does nothing.
 */
@RestController
@RequestMapping("/notifications")
@PreAuthorize("isAuthenticated()")
@Tag(name = "notifications", description = "The inbox, what you are told about, and on what")
public class NotificationController {

    private final NotificationInboxService inbox;
    private final AuthenticatedCaller caller;

    public NotificationController(NotificationInboxService inbox, AuthenticatedCaller caller) {
        this.inbox = inbox;
        this.caller = caller;
    }

    // ── The inbox ────────────────────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "What you have been told, newest first",
               description = "The inbox is the record rather than a message: it is written "
                       + "whenever an event is not switched off, and always for the five that "
                       + "cannot be. Somebody who turned every other channel off can still find "
                       + "out what happened here.")
    public ResponseEntity<PagedResponse<NotificationResponses.Item>> inbox(
            Authentication authentication,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok()
                // Private and brief. A shared cache holding somebody's inbox
                // would serve it to the next person through the same proxy.
                .cacheControl(CacheControl.maxAge(15, java.util.concurrent.TimeUnit.SECONDS)
                        .cachePrivate())
                .body(inbox.inbox(caller.userId(authentication), unreadOnly, paged(page, size)));
    }

    @PostMapping("/{notificationId}/read")
    @Operation(summary = "Mark one as read",
               description = "Marking an already-read notification is not an error. The inbox "
                       + "retries, and a client that lost a reply must be able to send the same "
                       + "call again.")
    public ResponseEntity<NotificationResponses.Item> markRead(
            Authentication authentication, @PathVariable Long notificationId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(inbox.markRead(caller.userId(authentication), notificationId));
    }

    @PostMapping("/read-all")
    @Operation(summary = "Mark everything as read",
               description = "Says how many that was, because dismissing forty unread "
                       + "notifications unseen is worth being told about.")
    public ResponseEntity<NotificationResponses.AllRead> markAllRead(
            Authentication authentication) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(inbox.markAllRead(caller.userId(authentication)));
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    @GetMapping("/preferences")
    @Operation(summary = "Every event on every channel",
               description = "Resolved rather than stored: a user who has never opened this page "
                       + "has no rows at all and gets the defaults, so returning only the "
                       + "overrides would show almost nothing to almost everybody.")
    public ResponseEntity<NotificationResponses.Preferences> preferences(
            Authentication authentication) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(inbox.preferences(caller.userId(authentication)));
    }

    @PutMapping("/preferences")
    @Operation(summary = "Change some of the switches",
               description = "A list of changes rather than the whole matrix, so a client with a "
                       + "stale copy cannot silently revert a switch the user changed on another "
                       + "device. A locked switch in the list is ignored rather than failing the "
                       + "call; the response says how many actually moved.")
    public ResponseEntity<NotificationResponses.Preferences> updatePreferences(
            Authentication authentication,
            @Valid @RequestBody NotificationRequests.UpdatePreferences request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(inbox.updatePreferences(caller.userId(authentication), request));
    }

    // ── Devices ──────────────────────────────────────────────────────────────

    @PostMapping("/devices")
    @Operation(summary = "Register a phone for push",
               description = "Sending the same token again is a refresh rather than a second "
                       + "device — the app does this on every start. A token last registered "
                       + "under another account is revoked there first: operating systems reassign "
                       + "them, and the next owner of a phone must not be sent the previous "
                       + "owner's delivery codes. The token is never returned by any endpoint.")
    public ResponseEntity<NotificationResponses.Device> registerDevice(
            Authentication authentication,
            @Valid @RequestBody NotificationRequests.RegisterDevice request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(inbox.registerDevice(caller.userId(authentication), request));
    }

    @DeleteMapping("/devices/{deviceId}")
    @Operation(summary = "Stop sending to a phone",
               description = "Soft, so \"removed on the 4th\" survives — a stolen handset is the "
                       + "case that makes that worth keeping. Says plainly that this does not "
                       + "sign the device out.")
    public ResponseEntity<NotificationResponses.DeviceRemoved> removeDevice(
            Authentication authentication, @PathVariable Long deviceId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(inbox.removeDevice(caller.userId(authentication), deviceId));
    }

    private static Pageable paged(int page, int size) {
        return PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
    }
}
