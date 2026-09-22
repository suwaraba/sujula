package com.sujula.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sujula.dto.request.pickup.PickupRequests;
import com.sujula.dto.response.pickup.PickupResponses;
import com.sujula.service.idempotency.IdempotencyService;
import com.sujula.service.pickup.PickupPointService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Counters that hold parcels for people.
 *
 * <p>Two surfaces in one controller, deliberately kept visible as two. The
 * {@code /pickup-points} routes are open — a shopper chooses where to collect
 * before they sign in, and frequently before they have an account — and carry an
 * address, hours and a capacity band. Everything under {@code /pickup} is the
 * operator's and resolves their point from the session.
 *
 * <p>No operator id appears in any path. A point id does, and the operator goes
 * into the query beside it, so another operator's counter is not found rather
 * than refused — these rows lead to recipients' names and phone numbers.
 */
@RestController
@Tag(name = "pickup", description = "Collection counters: finding them, running them, and the shelf")
public class PickupPointController {

    private static final String APPLY = "pickup.apply";
    private static final String ACCEPT = "pickup.parcel.accept";
    private static final String REJECT = "pickup.parcel.reject";
    private static final String RELEASE = "pickup.parcel.release";
    private static final String RETURN = "pickup.parcel.return";
    private static final String RESEND = "pickup.parcel.resend-code";

    private final PickupPointService pickup;
    private final AuthenticatedCaller caller;
    private final IdempotencyService idempotency;

    public PickupPointController(PickupPointService pickup, AuthenticatedCaller caller,
                                 IdempotencyService idempotency) {
        this.pickup = pickup;
        this.caller = caller;
        this.idempotency = idempotency;
    }

    // ── Public ───────────────────────────────────────────────────────────────

    @GetMapping("/pickup-points")
    @Operation(summary = "Counters near a place",
               description = "Open, because a shopper picks where to collect before signing in. "
                       + "Give lat and lng with an optional radius, or a town. Only approved, "
                       + "open counters are listed: sending somebody to a shuttered shop is worse "
                       + "than showing them nothing.")
    public ResponseEntity<PickupResponses.PublicPoints> search(
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(required = false) Double radius,
            @RequestParam(required = false) String city) {

        return ResponseEntity.ok()
                // Safe to cache briefly: a counter's address and hours change
                // rarely, and the capacity band is a band rather than a live
                // count for exactly this reason.
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePublic())
                .body(pickup.search(new PickupRequests.NearbySearch(lat, lng, radius, city)));
    }

    @GetMapping("/pickup-points/{id}")
    @Operation(summary = "One counter",
               description = "Hours and how full it is, as a band rather than a count — that a "
                       + "shop is holding a hundred and ninety parcels is a fact about somebody's "
                       + "business. Carries no operator name and no contact email.")
    public ResponseEntity<PickupResponses.PublicPoint> point(@PathVariable Long id) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePublic())
                .body(pickup.publicPoint(id));
    }

    // ── Becoming an operator ─────────────────────────────────────────────────

    @PostMapping("/pickup/applications")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Apply to run a counter",
               description = "A position is required as well as an address: most addresses here "
                       + "do not resolve to a point, and the position is what a driver navigates "
                       + "to. The counter is switched off until somebody has checked it is real.")
    public ResponseEntity<PickupResponses.ApplicationSubmitted> apply(
            Authentication authentication,
            @Valid @RequestBody PickupRequests.Apply request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {

        Long userId = caller.userId(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(userId, APPLY), key, request,
                200, PickupResponses.ApplicationSubmitted.class,
                () -> pickup.apply(userId, request)));
    }

    // ── Running counters ─────────────────────────────────────────────────────

    @GetMapping("/pickup/points")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "The counters I run",
               description = "A list, because somebody who runs one well is frequently asked to "
                       + "run a second.")
    public ResponseEntity<PickupResponses.OperatorPoints> myPoints(Authentication authentication) {
        return uncached(pickup.myPoints(caller.userId(authentication)));
    }

    @PatchMapping("/pickup/points/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Change hours, capacity or close for a while",
               description = "Closing keeps the parcels you already hold — the people waiting on "
                       + "them did not choose the closure — and stops new ones being sent. "
                       + "Capacity cannot be set below what is already on the shelf.")
    public ResponseEntity<PickupResponses.OperatorPoint> updatePoint(
            Authentication authentication, @PathVariable Long id,
            @Valid @RequestBody PickupRequests.UpdatePoint request) {
        return uncached(pickup.updatePoint(caller.userId(authentication), id, request));
    }

    @GetMapping("/pickup/points/{id}/parcels")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "What is coming, what is here, and what has to go back",
               description = "Three lists rather than one with a flag: they are three different "
                       + "jobs and an operator works them at different times of day. Incoming "
                       + "parcels carry no recipient name — they are not here yet.")
    public ResponseEntity<PickupResponses.Parcels> parcels(
            Authentication authentication, @PathVariable Long id) {
        return uncached(pickup.parcels(caller.userId(authentication), id));
    }

    // ── The counter ──────────────────────────────────────────────────────────

    @PostMapping("/pickup/points/{id}/parcels/{shipmentId}/accept")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Take a parcel in from a driver",
               description = "The operator verifies the driver's code — the receiving party "
                       + "checking the giving party, which is the only thing a code can prove — "
                       + "and the parcel gets a shelf. Refused when the counter is closed, "
                       + "suspended or full, and the refusal says which.")
    public ResponseEntity<PickupResponses.ParcelAccepted> accept(
            Authentication authentication, @PathVariable Long id, @PathVariable Long shipmentId,
            @Valid @RequestBody PickupRequests.AcceptParcel request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {

        Long userId = caller.userId(authentication);
        return uncached(idempotency.execute(
                IdempotencyService.scopeFor(userId, ACCEPT + ":" + shipmentId), key, request,
                200, PickupResponses.ParcelAccepted.class,
                () -> pickup.accept(userId, id, shipmentId, request)));
    }

    @PostMapping("/pickup/points/{id}/parcels/{shipmentId}/reject")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Turn a parcel away",
               description = "Custody does not move: the driver still has it in their hands, "
                       + "which is why this is recorded as a failed attempt rather than the end "
                       + "of the chain. Somebody stays accountable for the parcel.")
    public ResponseEntity<PickupResponses.ParcelRejected> reject(
            Authentication authentication, @PathVariable Long id, @PathVariable Long shipmentId,
            @Valid @RequestBody PickupRequests.RejectParcel request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {

        Long userId = caller.userId(authentication);
        return uncached(idempotency.execute(
                IdempotencyService.scopeFor(userId, REJECT + ":" + shipmentId), key, request,
                200, PickupResponses.ParcelRejected.class,
                () -> pickup.reject(userId, id, shipmentId, request)));
    }

    @PostMapping("/pickup/points/{id}/parcels/{shipmentId}/release")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Hand a parcel to the person collecting",
               description = "The end of the chain, exactly as a doorstep delivery is, and the "
                       + "thing that releases the seller's money. Needs the recipient's code and "
                       + "the name of whoever is collecting: a code alone would let anybody who "
                       + "overheard it collect, a name alone anybody who read the label.")
    public ResponseEntity<PickupResponses.ParcelReleased> release(
            Authentication authentication, @PathVariable Long id, @PathVariable Long shipmentId,
            @Valid @RequestBody PickupRequests.ReleaseParcel request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {

        Long userId = caller.userId(authentication);
        return uncached(idempotency.execute(
                IdempotencyService.scopeFor(userId, RELEASE + ":" + shipmentId), key, request,
                200, PickupResponses.ParcelReleased.class,
                () -> pickup.release(userId, id, shipmentId, request)));
    }

    @PostMapping("/pickup/points/{id}/parcels/{shipmentId}/return")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Send an uncollected parcel back",
               description = "Only once the storage deadline has passed. Before then it is "
                       + "refused: somebody may be travelling to collect, and sending it back "
                       + "early takes a decision that is not the counter's to take.")
    public ResponseEntity<PickupResponses.ParcelReturning> returnParcel(
            Authentication authentication, @PathVariable Long id, @PathVariable Long shipmentId,
            @Valid @RequestBody(required = false) PickupRequests.ReturnParcel request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {

        Long userId = caller.userId(authentication);
        PickupRequests.ReturnParcel body = request != null ? request
                : new PickupRequests.ReturnParcel(null, null);
        return uncached(idempotency.execute(
                IdempotencyService.scopeFor(userId, RETURN + ":" + shipmentId), key, body,
                200, PickupResponses.ParcelReturning.class,
                () -> pickup.returnToVendor(userId, id, shipmentId, body)));
    }

    @PostMapping("/pickup/points/{id}/parcels/{shipmentId}/resend-code")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Have the collection code sent again",
               description = "Emailed to the buyer, who passes it to whoever is collecting — the "
                       + "recipient may have no account, no app and no email of her own. The "
                       + "operator never sees it: one who could read it could hand the parcel to "
                       + "whoever is standing there. Any earlier code stops working.")
    public ResponseEntity<PickupResponses.CodeResent> resendCode(
            Authentication authentication, @PathVariable Long id, @PathVariable Long shipmentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {

        Long userId = caller.userId(authentication);
        return uncached(idempotency.execute(
                IdempotencyService.scopeFor(userId, RESEND + ":" + shipmentId), key, shipmentId,
                200, PickupResponses.CodeResent.class,
                () -> pickup.resendCode(userId, id, shipmentId)));
    }

    @GetMapping("/pickup/points/{id}/earnings")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "What this counter has earned",
               description = "Per parcel and per currency. A point that has handled parcels priced "
                       + "in dalasi and in CFA has two earnings, and adding them would need a rate "
                       + "nobody agreed to.")
    public ResponseEntity<PickupResponses.Earnings> earnings(
            Authentication authentication, @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return uncached(pickup.earnings(caller.userId(authentication), id, from, to));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * The operator's own views carry recipients' names and their earnings.
     *
     * <p>A counter's tablet sits on a shop counter all day and is frequently
     * shared. Nothing on this half belongs in a cache; the public half above is
     * cacheable precisely because it carries none of it.
     */
    private static <T> ResponseEntity<T> uncached(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(body);
    }
}
