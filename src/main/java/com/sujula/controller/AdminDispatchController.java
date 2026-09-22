package com.sujula.controller;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sujula.dto.request.admin.AdminDispatchRequests;
import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.admin.AdminDispatchResponses;
import com.sujula.model.constant.OrderStatus;
import com.sujula.model.constant.ShipmentStatus;
import com.sujula.service.admin.AdminDispatchService;
import com.sujula.service.idempotency.IdempotencyService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Orders and parcels, for the people who have to unstick them.
 *
 * <p>Support reads all of it — an agent on the telephone to somebody whose
 * parcel has not arrived needs the order, the ledger and the custody chain, and
 * needs them without being able to change any of it. Every write is an
 * administrator's.
 *
 * <p>Two of those writes are deliberately harder than the rest, because they are
 * the two ways a status can be reached without the thing behind it having
 * happened: forcing a vendor order's status, and overriding a handoff. The
 * second also demands step-up.
 */
@RestController
@RequestMapping("/admin")
@PreAuthorize("isAuthenticated()")
@Tag(name = "admin-dispatch", description = "Orders, the dispatch board and the custody chain")
public class AdminDispatchController {

    private final AdminDispatchService dispatch;
    private final StaffCaller staff;
    private final IdempotencyService idempotency;

    public AdminDispatchController(AdminDispatchService dispatch, StaffCaller staff,
                                   IdempotencyService idempotency) {
        this.dispatch = dispatch;
        this.staff = staff;
        this.idempotency = idempotency;
    }

    // ── Orders ───────────────────────────────────────────────────────────────

    @GetMapping("/orders")
    @Operation(summary = "Search orders",
               description = "destinationCountry filters on where the goods go, not where the "
                       + "buyer is — on this platform those are routinely different, so they are "
                       + "different questions. stuckForHours asks how long an order has sat "
                       + "without moving, which is what a stuck-order sweep actually wants.")
    public ResponseEntity<PagedResponse<AdminDispatchResponses.OrderRow>> orders(
            Authentication authentication,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) String destinationCountry,
            @RequestParam(required = false) Long vendorId,
            @RequestParam(required = false) Integer stuckForHours,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return uncached(dispatch.orders(staff.staff(authentication), q, status, destinationCountry,
                vendorId, stuckForHours, paged(page, size)));
    }

    @GetMapping("/orders/{orderId}")
    @Operation(summary = "One order: its slices, its ledger and its parcels",
               description = "All three in one response, because the interesting facts are the "
                       + "disagreements between them — a cancelled order with a moving parcel, a "
                       + "delivered parcel with no collection event — and nobody reading three "
                       + "screens spots a disagreement. They are listed under warnings.")
    public ResponseEntity<AdminDispatchResponses.OrderDetail> order(
            Authentication authentication, @PathVariable Long orderId) {
        return uncached(dispatch.order(staff.staff(authentication), orderId));
    }

    @PostMapping("/orders/{orderId}/cancel")
    @Operation(summary = "Cancel over the top of whatever it was doing",
               description = "One seller's slice where one is named, all of them otherwise — "
                       + "never some. Parcels stop too: a cancelled order with a parcel still "
                       + "moving is refunded goods being delivered, and the driver finds out at "
                       + "the door.")
    public ResponseEntity<AdminDispatchResponses.OrderCancelled> forceCancel(
            Authentication authentication, @PathVariable Long orderId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminDispatchRequests.ForceCancelOrder request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), "admin.orders.cancel:" + orderId),
                key, request, 200, AdminDispatchResponses.OrderCancelled.class,
                () -> dispatch.forceCancel(acting, orderId, request)));
    }

    @PostMapping("/orders/{orderId}/vendor-orders/{vendorOrderId}/force-status")
    @Operation(summary = "Break-glass: set a status by hand",
               description = "Every other status here is a consequence of something that "
                       + "happened. This is the escape hatch for when the thing that happened "
                       + "cannot be recorded — a driver's phone in the river, a counter that "
                       + "burned down. The note must be substantial because it is the only "
                       + "evidence the status was ever justified, and it has its own audit action "
                       + "so a month with twenty of these is a question somebody can ask.")
    public ResponseEntity<AdminDispatchResponses.StatusForced> forceStatus(
            Authentication authentication, @PathVariable Long orderId,
            @PathVariable Long vendorOrderId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminDispatchRequests.ForceStatus request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(),
                        "admin.orders.force-status:" + vendorOrderId),
                key, request, 200, AdminDispatchResponses.StatusForced.class,
                () -> dispatch.forceStatus(acting, orderId, vendorOrderId, request)));
    }

    @PostMapping("/orders")
    @Operation(summary = "Place an order for somebody who telephoned",
               description = "Routed through checkout with an impersonated session rather than "
                       + "built here, so it is priced, stock-reserved and rate-stamped exactly as "
                       + "the buyer's own would be — and so there is a record of who placed it. "
                       + "This endpoint says so rather than quietly doing something weaker.")
    public ResponseEntity<AdminDispatchResponses.OrderPlaced> placeOnBehalf(
            Authentication authentication,
            @Valid @RequestBody AdminDispatchRequests.PlaceOrderOnBehalf request) {
        return ResponseEntity.ok(dispatch.placeOnBehalf(staff.decider(authentication), request));
    }

    // ── The board ────────────────────────────────────────────────────────────

    @GetMapping("/shipments")
    @Operation(summary = "The dispatch board",
               description = "Sorted by how long each parcel has sat without moving, because a "
                       + "board sorted by age shows what came in first and a board sorted by "
                       + "stuckness shows what is going wrong.")
    public ResponseEntity<PagedResponse<AdminDispatchResponses.ShipmentRow>> shipments(
            Authentication authentication,
            @RequestParam(required = false) ShipmentStatus status,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) Long driverId,
            @RequestParam(required = false) Integer waitingOverHours,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return uncached(dispatch.shipments(staff.staff(authentication), status, country, driverId,
                waitingOverHours, paged(page, size)));
    }

    @GetMapping("/shipments/unassigned")
    @Operation(summary = "Parcels nobody is carrying, with who could",
               description = "Candidates are ranked on being on shift, distance from the shop and "
                       + "how often they accept — and every one carries the reason it is where it "
                       + "is. A dispatcher handed an unexplained list picks the first, and the "
                       + "first would otherwise be whoever the database returned.")
    public ResponseEntity<List<AdminDispatchResponses.UnassignedShipment>> unassigned(
            Authentication authentication,
            @RequestParam(defaultValue = "20") int limit) {
        return uncached(dispatch.unassigned(staff.staff(authentication), limit));
    }

    @PostMapping("/shipments/{shipmentId}/assign")
    @Operation(summary = "Offer a parcel to a driver",
               description = "An offer, not an assignment: the driver accepts or it lapses back "
                       + "to the queue. A platform that could put a job on somebody's screen and "
                       + "call it theirs would be one where a driver who was asleep is "
                       + "accountable for a parcel. Takes a row lock — two dispatchers racing is "
                       + "the ordinary case on a busy morning, and the loser is told.")
    public ResponseEntity<AdminDispatchResponses.AssignmentMade> assign(
            Authentication authentication, @PathVariable Long shipmentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminDispatchRequests.AssignShipment request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), "admin.shipments.assign:" + shipmentId),
                key, request, 200, AdminDispatchResponses.AssignmentMade.class,
                () -> dispatch.assign(acting, shipmentId, request)));
    }

    @PostMapping("/shipments/{shipmentId}/unassign")
    @Operation(summary = "Take a parcel back off a driver",
               description = "Their acceptance score is untouched — a job taken off somebody is "
                       + "not a job they declined. Refused while they are actually carrying it: "
                       + "that would leave the custody chain saying they still have it.")
    public ResponseEntity<AdminDispatchResponses.AssignmentRemoved> unassign(
            Authentication authentication, @PathVariable Long shipmentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminDispatchRequests.UnassignShipment request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(),
                        "admin.shipments.unassign:" + shipmentId),
                key, request, 200, AdminDispatchResponses.AssignmentRemoved.class,
                () -> dispatch.unassign(acting, shipmentId, request)));
    }

    @PostMapping("/shipments/{shipmentId}/reassign")
    @Operation(summary = "Move a parcel to a different driver",
               description = "Only before anybody has picked it up, or after an attempt failed. A "
                       + "parcel in somebody's hands moves by a transfer with both drivers "
                       + "attesting — reassigning it here would say it changed hands when nobody "
                       + "handed it to anybody.")
    public ResponseEntity<AdminDispatchResponses.AssignmentMade> reassign(
            Authentication authentication, @PathVariable Long shipmentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminDispatchRequests.ReassignShipment request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(),
                        "admin.shipments.reassign:" + shipmentId),
                key, request, 200, AdminDispatchResponses.AssignmentMade.class,
                () -> dispatch.reassign(acting, shipmentId, request)));
    }

    @PostMapping("/shipments/{shipmentId}/override-handoff")
    @Operation(summary = "Record a handover that could not be proven the ordinary way",
               description = "The one way a parcel reaches DELIVERED without anybody presenting a "
                       + "code — so step-up on your own credentials, a substantial note, who "
                       + "attested it, and its own audit action. Not a hole in C4: the hole made "
                       + "expensive and loud, because a driver whose phone went into the river "
                       + "still has to be able to hand the parcel over, and the alternative to an "
                       + "audited override is somebody editing the database.")
    public ResponseEntity<AdminDispatchResponses.HandoffOverridden> overrideHandoff(
            Authentication authentication, @PathVariable Long shipmentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminDispatchRequests.OverrideHandoff request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(idempotency.execute(
                        IdempotencyService.scopeFor(acting.getId(),
                                "admin.shipments.override:" + shipmentId),
                        key, request, 200, AdminDispatchResponses.HandoffOverridden.class,
                        () -> dispatch.overrideHandoff(acting, shipmentId, request)));
    }

    @PostMapping("/shipments/{shipmentId}/cancel")
    @Operation(summary = "Stop a parcel and every open leg with it")
    public ResponseEntity<AdminDispatchResponses.ShipmentCancelled> cancelShipment(
            Authentication authentication, @PathVariable Long shipmentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminDispatchRequests.CancelShipment request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), "admin.shipments.cancel:" + shipmentId),
                key, request, 200, AdminDispatchResponses.ShipmentCancelled.class,
                () -> dispatch.cancelShipment(acting, shipmentId, request)));
    }

    @GetMapping("/shipments/{shipmentId}/custody-chain")
    @Operation(summary = "The whole chain, with its evidence",
               description = "What a dispute is actually decided on: who handed what to whom, "
                       + "where, how far from where it should have been, and with what "
                       + "photograph. Says whether a code was presented, never the code — an "
                       + "administrator has no use for the digits, and a code on a screen is a "
                       + "code somebody can read out. Links recorded by an override are marked.")
    public ResponseEntity<AdminDispatchResponses.CustodyChainView> custodyChain(
            Authentication authentication, @PathVariable Long shipmentId) {
        return uncached(dispatch.custodyChain(staff.staff(authentication), shipmentId));
    }

    private static <T> ResponseEntity<T> uncached(T body) {
        // Recipients' addresses, buyers' names, drivers' positions. A shared
        // cache holding any of it would serve one agent's screen to the next
        // person through the proxy.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static Pageable paged(int page, int size) {
        return PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
    }
}
