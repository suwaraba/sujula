package com.sujula.controller;

import java.time.LocalDate;

import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sujula.dto.request.driver.DriverRequests;
import com.sujula.dto.response.driver.DriverResponses;
import com.sujula.service.driver.DriverCustodyService;
import com.sujula.service.driver.DriverProfileService;
import com.sujula.service.idempotency.IdempotencyService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * The driver's app.
 *
 * <p>No driver id appears in any path. The driver is resolved from the session
 * and goes into every query, so there is no parameter to change to answer
 * somebody else's offers or open somebody else's parcel.
 *
 * <p>Everything that moves a parcel is idempotent, and that is not a nicety
 * here. A driver records a collection standing in a shop doorway with one bar of
 * signal; the request goes, the response does not come back, and the app retries.
 * Without a key that second attempt is a second collection of the same parcel.
 * The events that matter most carry the app's own id in the body as well, so a
 * batch uploaded twice after a day offline records each event once.
 *
 * <p>A recipient's address and phone number appear only while the driver is
 * carrying the parcel — not before they accept, not after they hand over. That
 * window is enforced in the service and every response here is served
 * {@code no-store}, because an address cached on a shared phone is an address
 * the next person to pick it up can read.
 */
@RestController
@RequestMapping("/driver")
@PreAuthorize("isAuthenticated()")
@Tag(name = "driver", description = "Carrying parcels: assignments, custody and earnings")
public class DriverController {

    private static final int MAX_PAGE_SIZE = 100;

    private static final String APPLY = "driver.apply";
    private static final String ACCEPT = "driver.assignment.accept";
    private static final String DECLINE = "driver.assignment.decline";
    private static final String ARRIVED = "driver.shipment.arrived";
    private static final String COLLECT = "driver.shipment.collect";
    private static final String DEPOSIT = "driver.shipment.deposit";
    private static final String DELIVER = "driver.shipment.deliver";
    private static final String FAILED = "driver.shipment.failed";
    private static final String CODE = "driver.shipment.recipient-code";
    private static final String TRANSFER = "driver.shipment.transfer";
    private static final String SYNC = "driver.custody.sync";

    private final DriverProfileService profiles;
    private final DriverCustodyService custody;
    private final AuthenticatedCaller caller;
    private final IdempotencyService idempotency;

    public DriverController(DriverProfileService profiles, DriverCustodyService custody,
                            AuthenticatedCaller caller, IdempotencyService idempotency) {
        this.profiles = profiles;
        this.custody = custody;
        this.caller = caller;
        this.idempotency = idempotency;
    }

    // ── The driver ───────────────────────────────────────────────────────────

    @PostMapping("/profile")
    @Operation(summary = "Apply to carry parcels",
               description = "Creates a driver profile awaiting review. The identity and licence "
                       + "details are required because of what the job is: a driver holds goods "
                       + "worth more than they earn in a month and turns up at buyers' families' "
                       + "homes.")
    public ResponseEntity<DriverResponses.Profile> apply(
            Authentication authentication,
            @Valid @RequestBody DriverRequests.Apply request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {

        Long userId = caller.userId(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(userId, APPLY), key, request,
                200, DriverResponses.Profile.class, () -> profiles.apply(userId, request)));
    }

    @GetMapping("/profile")
    @Operation(summary = "My profile and where my application has got to")
    public ResponseEntity<DriverResponses.Profile> myProfile(Authentication authentication) {
        return uncached(profiles.myProfile(caller.userId(authentication)));
    }

    @PatchMapping("/profile")
    @Operation(summary = "Change my vehicle or the area I cover",
               description = "Only what a driver may say about themselves. Nothing here touches "
                       + "status, score or the documents somebody already reviewed.")
    public ResponseEntity<DriverResponses.Profile> updateProfile(
            Authentication authentication,
            @Valid @RequestBody DriverRequests.UpdateProfile request) {
        return uncached(profiles.updateProfile(caller.userId(authentication), request));
    }

    @PutMapping("/availability")
    @Operation(summary = "Go online or off",
               description = "A driver's own decision, separate from whether the platform has "
                       + "approved them. Anything already accepted stays theirs to finish.")
    public ResponseEntity<DriverResponses.AvailabilitySet> availability(
            Authentication authentication,
            @Valid @RequestBody DriverRequests.SetAvailability request) {
        return uncached(profiles.setAvailability(caller.userId(authentication), request));
    }

    @PostMapping("/location")
    @Operation(summary = "Where I am",
               description = "Accepted only while online — a platform that tracked drivers who "
                       + "had finished for the day would be tracking people rather than parcels — "
                       + "and throttled, because a phone pinging every second is a flat battery "
                       + "by eleven.")
    public ResponseEntity<DriverResponses.LocationAccepted> ping(
            Authentication authentication,
            @Valid @RequestBody DriverRequests.Ping request) {
        return uncached(profiles.ping(caller.userId(authentication), request));
    }

    // ── Assignments ──────────────────────────────────────────────────────────

    @GetMapping("/assignments")
    @Operation(summary = "Offers and accepted work",
               description = "An offer carries a town, a distance and what it pays — never an "
                       + "address. The same leg goes to several drivers and one takes it; showing "
                       + "the destination would hand a recipient's home to everyone who declined.")
    public ResponseEntity<DriverResponses.Assignments> assignments(Authentication authentication) {
        return uncached(profiles.assignments(caller.userId(authentication)));
    }

    @PostMapping("/assignments/{legId}/accept")
    @Operation(summary = "Take a job", description = "Refused once the offer has lapsed.")
    public ResponseEntity<DriverResponses.AssignmentAnswered> accept(
            Authentication authentication, @PathVariable Long legId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {

        Long userId = caller.userId(authentication);
        return uncached(idempotency.execute(
                IdempotencyService.scopeFor(userId, ACCEPT + ":" + legId), key, legId,
                200, DriverResponses.AssignmentAnswered.class,
                () -> profiles.accept(userId, legId)));
    }

    @PostMapping("/assignments/{legId}/decline")
    @Operation(summary = "Turn a job down",
               description = "The reason is required: it is what tells dispatch whether to offer "
                       + "it nearby or further away. Declining affects how much you are offered.")
    public ResponseEntity<DriverResponses.AssignmentAnswered> decline(
            Authentication authentication, @PathVariable Long legId,
            @Valid @RequestBody DriverRequests.Decline request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {

        Long userId = caller.userId(authentication);
        return uncached(idempotency.execute(
                IdempotencyService.scopeFor(userId, DECLINE + ":" + legId), key, request,
                200, DriverResponses.AssignmentAnswered.class,
                () -> profiles.decline(userId, legId, request)));
    }

    // ── A parcel ─────────────────────────────────────────────────────────────

    @GetMapping("/shipments/{id}")
    @Operation(summary = "One parcel",
               description = "The delivery address and the recipient's number are present only "
                       + "while you are carrying it. They are absent rather than blank before and "
                       + "after, because the recipient may have no account and never agreed to "
                       + "anything — the only reason to hold her address is being on the way to it.")
    public ResponseEntity<DriverResponses.ShipmentDetail> shipment(
            Authentication authentication, @PathVariable Long id) {
        return uncached(custody.shipment(caller.userId(authentication), id));
    }

    @PostMapping("/shipments/{id}/arrived-at-origin")
    @Operation(summary = "I am at the shop",
               description = "No code: nothing has changed hands yet. The position is kept and "
                       + "checked against the shop, so a seller waiting knows a driver is there.")
    public ResponseEntity<DriverResponses.CustodyRecorded> arrived(
            Authentication authentication, @PathVariable Long id,
            @Valid @RequestBody DriverRequests.Arrived request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {

        Long userId = caller.userId(authentication);
        return uncached(idempotency.execute(
                IdempotencyService.scopeFor(userId, ARRIVED + ":" + id), key, request,
                200, DriverResponses.CustodyRecorded.class,
                () -> custody.arrivedAtOrigin(userId, id, request)));
    }

    @PostMapping("/shipments/{id}/collect")
    @Operation(summary = "Collect from the seller",
               description = "The seller reads out their release code and the driver presents it. "
                       + "That is the only thing a code proves and the whole reason for one: two "
                       + "people were in the same place at the same time.")
    public ResponseEntity<DriverResponses.CustodyRecorded> collect(
            Authentication authentication, @PathVariable Long id,
            @Valid @RequestBody DriverRequests.Handover request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {

        Long userId = caller.userId(authentication);
        return uncached(idempotency.execute(
                IdempotencyService.scopeFor(userId, COLLECT + ":" + id), key, request,
                200, DriverResponses.CustodyRecorded.class,
                () -> custody.collect(userId, id, request)));
    }

    @PostMapping("/shipments/{id}/deposit-at-pickup")
    @Operation(summary = "Leave it at a pickup point")
    public ResponseEntity<DriverResponses.CustodyRecorded> deposit(
            Authentication authentication, @PathVariable Long id,
            @Valid @RequestBody DriverRequests.Handover request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {

        Long userId = caller.userId(authentication);
        return uncached(idempotency.execute(
                IdempotencyService.scopeFor(userId, DEPOSIT + ":" + id), key, request,
                200, DriverResponses.CustodyRecorded.class,
                () -> custody.depositAtPickup(userId, id, request)));
    }

    @PostMapping("/shipments/{id}/deliver")
    @Operation(summary = "The recipient has it",
               description = "The end of the chain, and the only event that releases the seller's "
                       + "money — so it asks for the most: the recipient's code, a position and a "
                       + "photograph. This is the link somebody would forge if any one of them "
                       + "were enough on its own.")
    public ResponseEntity<DriverResponses.CustodyRecorded> deliver(
            Authentication authentication, @PathVariable Long id,
            @Valid @RequestBody DriverRequests.Handover request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {

        Long userId = caller.userId(authentication);
        return uncached(idempotency.execute(
                IdempotencyService.scopeFor(userId, DELIVER + ":" + id), key, request,
                200, DriverResponses.CustodyRecorded.class,
                () -> custody.deliver(userId, id, request)));
    }

    @PostMapping("/shipments/{id}/delivery-failed")
    @Operation(summary = "It did not work",
               description = "Custody does not move: the driver still has the parcel, which is "
                       + "why this is an event rather than the end of the chain. The reason code "
                       + "decides what happens next — nobody home is a retry, a refused parcel "
                       + "goes back.")
    public ResponseEntity<DriverResponses.AttemptFailed> deliveryFailed(
            Authentication authentication, @PathVariable Long id,
            @Valid @RequestBody DriverRequests.DeliveryFailed request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {

        Long userId = caller.userId(authentication);
        return uncached(idempotency.execute(
                IdempotencyService.scopeFor(userId, FAILED + ":" + id), key, request,
                200, DriverResponses.AttemptFailed.class,
                () -> custody.deliveryFailed(userId, id, request)));
    }

    @PostMapping("/shipments/{id}/request-recipient-code")
    @Operation(summary = "Have the collection code sent",
               description = "Emailed to the buyer, who passes it to the recipient — she may have "
                       + "no account, no app and no email of her own. The driver never sees it: a "
                       + "driver who could read it could mark a parcel delivered without meeting "
                       + "anybody. Rate limited so the buyer is not spammed.")
    public ResponseEntity<DriverResponses.RecipientCodeRequested> requestCode(
            Authentication authentication, @PathVariable Long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {

        Long userId = caller.userId(authentication);
        return uncached(idempotency.execute(
                IdempotencyService.scopeFor(userId, CODE + ":" + id), key, id,
                200, DriverResponses.RecipientCodeRequested.class,
                () -> custody.requestRecipientCode(userId, id)));
    }

    @PostMapping("/shipments/{id}/transfer")
    @Operation(summary = "Hand it to another driver",
               description = "Both sides present something. A transfer attested by one person is "
                       + "a link nobody can corroborate, and it is the link at which a parcel "
                       + "would go missing if either half could be forged.")
    public ResponseEntity<DriverResponses.CustodyRecorded> transfer(
            Authentication authentication, @PathVariable Long id,
            @Valid @RequestBody DriverRequests.Transfer request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {

        Long userId = caller.userId(authentication);
        return uncached(idempotency.execute(
                IdempotencyService.scopeFor(userId, TRANSFER + ":" + id), key, request,
                200, DriverResponses.CustodyRecorded.class,
                () -> custody.transfer(userId, id, request)));
    }

    @PostMapping("/custody-events/sync")
    @Operation(summary = "Upload what happened while I had no signal",
               description = "The case this platform actually runs in: a round worked through an "
                       + "area with no coverage, uploaded when the driver comes back within "
                       + "range. Applied oldest first and deduplicated by the app's own id, so "
                       + "sending a batch twice records it once. One bad entry does not throw "
                       + "away the rest of the day.")
    public ResponseEntity<DriverResponses.SyncResult> sync(
            Authentication authentication,
            @Valid @RequestBody DriverRequests.SyncBatch request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {

        Long userId = caller.userId(authentication);
        return uncached(idempotency.execute(
                IdempotencyService.scopeFor(userId, SYNC), key, request,
                200, DriverResponses.SyncResult.class, () -> custody.sync(userId, request)));
    }

    // ── Money and history ────────────────────────────────────────────────────

    @GetMapping("/earnings")
    @Operation(summary = "What I have earned",
               description = "Per delivery and per period, kept apart by currency: a driver who "
                       + "has worked legs paid in dalasi and in CFA has two earnings, and adding "
                       + "them would need a rate nobody agreed to.")
    public ResponseEntity<DriverResponses.Earnings> earnings(
            Authentication authentication,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return uncached(profiles.earnings(caller.userId(authentication), from, to));
    }

    @GetMapping("/history")
    @Operation(summary = "Jobs I have finished",
               description = "Towns rather than addresses. A round from three months ago is not a "
                       + "reason to still hold somebody's front door.")
    public ResponseEntity<DriverResponses.History> history(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return uncached(profiles.history(caller.userId(authentication),
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE))));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Nothing on this surface belongs in a cache.
     *
     * <p>These responses carry recipients' addresses and phone numbers, a
     * driver's position and their earnings. A phone in a shared vehicle, or a
     * proxy on a cheap network, would hold all of it for whoever comes next.
     */
    private static <T> ResponseEntity<T> uncached(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(body);
    }
}
