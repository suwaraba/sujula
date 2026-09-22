package com.sujula.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
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

import com.sujula.dto.request.admin.AdminPlatformRequests;
import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.admin.AdminPlatformResponses;
import com.sujula.model.constant.AuditAction;
import com.sujula.model.constant.DisputeReason;
import com.sujula.model.constant.DisputeStatus;
import com.sujula.model.constant.JobRunStatus;
import com.sujula.service.admin.AdminPlatformService;
import com.sujula.service.idempotency.IdempotencyService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Disputes, comms and the platform's own switches.
 *
 * <p>Support works the dispute queue and cannot decide one. That split is the
 * point of this controller: reading a dispute, taking it, writing an internal
 * note and arranging a call are all support's, because they are what an agent
 * does on the telephone. Deciding moves a seller's money, and that is an
 * administrator's with their own password on top.
 */
@RestController
@RequestMapping("/admin")
@PreAuthorize("isAuthenticated()")
@Tag(name = "admin-platform", description = "Disputes, announcements, audit, flags and jobs")
public class AdminPlatformController {

    private final AdminPlatformService platform;
    private final StaffCaller staff;
    private final IdempotencyService idempotency;

    public AdminPlatformController(AdminPlatformService platform, StaffCaller staff,
                                   IdempotencyService idempotency) {
        this.platform = platform;
        this.staff = staff;
        this.idempotency = idempotency;
    }

    // ── Disputes ─────────────────────────────────────────────────────────────

    @GetMapping("/disputes")
    @Operation(summary = "The dispute queue, sorted by deadline rather than age",
               description = "A dispute raised this morning with a four-hour promise is more "
                       + "urgent than one from Tuesday with a week, and both parties have money "
                       + "tied up behind the answer — so the sort is the promise. Each row says "
                       + "how many hours are left and whether a call is owed to somebody.")
    public ResponseEntity<PagedResponse<AdminPlatformResponses.DisputeRow>> disputes(
            Authentication authentication,
            @RequestParam(required = false) DisputeStatus status,
            @RequestParam(defaultValue = "true") boolean openOnly,
            @RequestParam(required = false) Long assignedTo,
            @RequestParam(defaultValue = "false") boolean unassignedOnly,
            @RequestParam(required = false) Long vendorId,
            @RequestParam(required = false) DisputeReason reason,
            @RequestParam(defaultValue = "false") boolean overdueOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        staff.staff(authentication);
        return uncached(platform.queue(status, openOnly, assignedTo, unassignedOnly, vendorId,
                reason, overdueOnly, paged(page, size)));
    }

    @GetMapping("/disputes/{disputeId}")
    @Operation(summary = "One dispute, with both currencies and both clocks")
    public ResponseEntity<AdminPlatformResponses.DisputeRow> dispute(
            Authentication authentication, @PathVariable Long disputeId) {
        staff.staff(authentication);
        return uncached(platform.readDispute(disputeId));
    }

    @PostMapping("/disputes/{disputeId}/assign")
    @Operation(summary = "Take a dispute, or give it to somebody",
               description = "An empty body takes it yourself, which is what picking one off the "
                       + "queue actually means. Taking one moves it out of OPEN, so the queue can "
                       + "tell 'nobody has looked' from 'somebody is on it' — a distinction that "
                       + "otherwise has two agents working the same dispute.")
    public ResponseEntity<AdminPlatformResponses.DisputeRow> assign(
            Authentication authentication, @PathVariable Long disputeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminPlatformRequests.AssignDispute request) {
        var acting = staff.staff(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), "admin.disputes.assign:" + disputeId),
                key, request, 200, AdminPlatformResponses.DisputeRow.class,
                () -> platform.assign(acting, disputeId, request)));
    }

    @PostMapping("/disputes/{disputeId}/notes")
    @Operation(summary = "A note for the next agent, never for the parties",
               description = "Internal always, and not a flag the caller can set. This is where "
                       + "'buyer has filed three of these' goes, and a switch that could make it "
                       + "visible is one somebody eventually leaves in the wrong position. Write "
                       + "to the parties through the dispute's own message thread.")
    public ResponseEntity<AdminPlatformResponses.NoteAdded> addNote(
            Authentication authentication, @PathVariable Long disputeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminPlatformRequests.AddNote request) {
        var acting = staff.staff(authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), "admin.disputes.notes:" + disputeId),
                key, request, HttpStatus.CREATED.value(),
                AdminPlatformResponses.NoteAdded.class,
                () -> platform.addNote(acting, disputeId, request)));
    }

    @PostMapping("/disputes/{disputeId}/resolve")
    @Operation(summary = "Decide it, and move the money that follows",
               description = "An administrator's, with step-up. The amount is in the SELLER's "
                       + "currency — that is what the ledger holds, and asking for the buyer's "
                       + "would mean converting at today's rate rather than the one the order was "
                       + "placed at. Only SPLIT takes a figure: FOR_BUYER and FOR_VENDOR already "
                       + "say what happens to the whole amount, and a number beside them could "
                       + "disagree. The hold comes off as its own ledger row and any refund as "
                       + "another, never netted.")
    public ResponseEntity<AdminPlatformResponses.DisputeDecided> resolve(
            Authentication authentication, @PathVariable Long disputeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminPlatformRequests.ResolveDispute request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), "admin.disputes.resolve:" + disputeId),
                key, request, 200, AdminPlatformResponses.DisputeDecided.class,
                () -> platform.resolve(acting, disputeId, request)));
    }

    @PostMapping("/disputes/{disputeId}/request-callback")
    @Operation(summary = "Arrange for somebody to be telephoned",
               description = "Takes a number, not a user id: the person who most needs the call is "
                       + "often the recipient, who has no account and whose number is on the "
                       + "parcel. Omit it and the parcel's is used. The preferred language is "
                       + "worth filling in — a caller who opens in English to somebody who speaks "
                       + "Wolof has already lost the call, and 'she did not want to talk' is what "
                       + "gets written down instead.")
    public ResponseEntity<AdminPlatformResponses.CallbackRow> requestCallback(
            Authentication authentication, @PathVariable Long disputeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminPlatformRequests.RequestCallback request) {
        var acting = staff.staff(authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), "admin.disputes.callback:" + disputeId),
                key, request, HttpStatus.CREATED.value(),
                AdminPlatformResponses.CallbackRow.class,
                () -> platform.requestCallback(acting, disputeId, request)));
    }

    @PostMapping("/callbacks/{callbackId}/outcome")
    @Operation(summary = "Record what came of a call",
               description = "'We said we would call', 'we called and she did not answer' and 'we "
                       + "called and she said the seal was cut' are three different states, and "
                       + "only the middle one leaves the call still owed. A reschedule must say "
                       + "when — one with no time on it is a call nobody will make.")
    public ResponseEntity<AdminPlatformResponses.CallbackRow> recordCallback(
            Authentication authentication, @PathVariable Long callbackId,
            @Valid @RequestBody AdminPlatformRequests.RecordCallback request) {
        return ResponseEntity.ok(
                platform.recordCallback(staff.staff(authentication), callbackId, request));
    }

    @GetMapping("/callbacks")
    @Operation(summary = "Calls somebody still owes, soonest deadline first",
               description = "A call promised and not made is worse than one never promised: the "
                       + "person is waiting by a phone.")
    public ResponseEntity<PagedResponse<AdminPlatformResponses.CallbackRow>> callbacks(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        staff.staff(authentication);
        return uncached(platform.outstandingCallbacks(paged(page, size)));
    }

    // ── Comms ────────────────────────────────────────────────────────────────

    @PostMapping("/announcements")
    @Operation(summary = "Tell a role, or a country, or everybody",
               description = "Country here is the RECIPIENTS' own — where a seller trades, where a "
                       + "driver carries — which is the one place on this platform that is the "
                       + "right question, because an announcement is about the person rather than "
                       + "about a parcel. It goes out through the ordinary dispatch, so each "
                       + "person's own settings decide the channels: the reach is reported beside "
                       + "the segment size, and they are not the same number.")
    public ResponseEntity<AdminPlatformResponses.AnnouncementSent> announce(
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminPlatformRequests.Announce request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), "admin.announcements"),
                key, request, HttpStatus.CREATED.value(),
                AdminPlatformResponses.AnnouncementSent.class,
                () -> platform.announce(acting, request)));
    }

    @PostMapping("/notifications/send")
    @Operation(summary = "Message one person",
               description = "Honours their preferences rather than overriding them — the "
                       + "response says plainly when nothing was delivered because they have the "
                       + "event switched off. If the message is one they cannot afford to miss, "
                       + "that is what the mandatory events are for.")
    public ResponseEntity<AdminPlatformResponses.NotificationSent> notify(
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminPlatformRequests.SendNotification request) {
        var acting = staff.staff(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), "admin.notifications.send"),
                key, request, 200, AdminPlatformResponses.NotificationSent.class,
                () -> platform.notify(acting, request)));
    }

    // ── Platform ─────────────────────────────────────────────────────────────

    @GetMapping("/audit-log")
    @Operation(summary = "Who did what, and when",
               description = "Every filter optional and every one narrowing, because the question "
                       + "is never the same twice: what did this agent do on Tuesday, who has "
                       + "touched this seller, who has been issuing refunds.")
    public ResponseEntity<PagedResponse<AdminPlatformResponses.AuditRow>> auditLog(
            Authentication authentication,
            @RequestParam(required = false) Long actorUserId,
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) Long targetId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        staff.decider(authentication);
        return uncached(platform.auditLog(actorUserId, action, targetType, targetId, q, from, to,
                paged(page, size)));
    }

    @GetMapping("/feature-flags")
    @Operation(summary = "The switches, and who last moved each one",
               description = "The reason is on the flag rather than only in the audit log, because "
                       + "the person who finds one off in six months reads the flag. A flag with "
                       + "no reason beside it is one nobody dares turn back on.")
    public ResponseEntity<List<AdminPlatformResponses.FlagRow>> flags(
            Authentication authentication) {
        staff.staff(authentication);
        return uncached(platform.flags());
    }

    @PatchMapping("/feature-flags/{flagKey}")
    @Operation(summary = "Move a switch, with a reason",
               description = "Flags are declared in code and seeded; this moves one rather than "
                       + "inventing one, because a flag nothing reads is a switch that does "
                       + "nothing.")
    public ResponseEntity<AdminPlatformResponses.FlagRow> setFlag(
            Authentication authentication, @PathVariable String flagKey,
            @Valid @RequestBody AdminPlatformRequests.SetFeatureFlag request) {
        return ResponseEntity.ok(
                platform.setFlag(staff.decider(authentication), flagKey, request));
    }

    @GetMapping("/jobs")
    @Operation(summary = "What runs in the background, and whether it is still running",
               description = "Each job carries its own interval, because without one 'last ran an "
                       + "hour ago' is uninterpretable — fine for a daily job, alarming for one "
                       + "that runs every thirty seconds. The last clean finish is reported "
                       + "separately from the last run: a job that has run six times since it last "
                       + "succeeded looks healthy under one number and looks like a problem under "
                       + "two.")
    public ResponseEntity<List<AdminPlatformResponses.JobRow>> jobs(Authentication authentication) {
        staff.staff(authentication);
        return uncached(platform.jobs());
    }

    @GetMapping("/jobs/history")
    @Operation(summary = "Every pass, including the ones that found nothing",
               description = "A drainer finding an empty queue is the normal case and still writes "
                       + "a row — so a GAP in these rows means the scheduler stopped, which is a "
                       + "different and worse problem than a job failing.")
    public ResponseEntity<PagedResponse<AdminPlatformResponses.JobRow>> jobHistory(
            Authentication authentication,
            @RequestParam(required = false) String jobName,
            @RequestParam(required = false) JobRunStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        staff.staff(authentication);
        return uncached(platform.jobHistory(jobName, status, paged(page, size)));
    }

    @PostMapping("/jobs/{jobName}/run")
    @Operation(summary = "Run a job now",
               description = "Recorded as a run a person started rather than the clock, which is "
                       + "context worth keeping: a manual pass at three in the afternoon during an "
                       + "incident reads differently from the scheduled two o'clock one. Refused "
                       + "for a job switched off in this deployment — that is usually because "
                       + "another instance is doing it.")
    public ResponseEntity<AdminPlatformResponses.JobTriggered> triggerJob(
            Authentication authentication, @PathVariable String jobName,
            @Valid @RequestBody AdminPlatformRequests.TriggerJob request) {
        return ResponseEntity.ok(
                platform.triggerJob(staff.decider(authentication), jobName, request));
    }

    @GetMapping("/dashboard")
    @Operation(summary = "The platform at a glance",
               description = "GMV is one line per settlement currency and there is no total — a "
                       + "figure that added dalasi to CFA is one somebody would quote in a "
                       + "meeting. The 'attention' list is the part that is about people rather "
                       + "than numbers: disputes past their promise, calls owed, parcels that have "
                       + "not moved in three days.")
    public ResponseEntity<AdminPlatformResponses.Dashboard> dashboard(
            Authentication authentication) {
        staff.staff(authentication);
        return uncached(platform.dashboard());
    }

    // ── Shared ───────────────────────────────────────────────────────────────

    private static <T> ResponseEntity<T> uncached(T body) {
        // Recipients' telephone numbers, agents' names, sellers' earnings. A
        // shared cache holding any of it would serve one agent's screen to the
        // next person through the proxy.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static Pageable paged(int page, int size) {
        return PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200));
    }
}
