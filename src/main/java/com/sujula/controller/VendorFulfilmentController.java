package com.sujula.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sujula.dto.request.fulfilment.FulfilmentRequests;
import com.sujula.dto.response.fulfilment.FulfilmentResponses;
import com.sujula.service.fulfilment.VendorFulfilmentService;
import com.sujula.service.idempotency.IdempotencyService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * A seller working one order from acceptance to the shop door.
 *
 * <p>No vendor id appears in any path. The seller is resolved from the session
 * and goes into every query, so there is no parameter to change to reach
 * somebody else's parcel — another seller's order is reported as not found
 * rather than refused, because "forbidden" would confirm the id was real.
 *
 * <p>Every state-changing call takes an {@code Idempotency-Key}. That is not a
 * nicety on this surface: a seller marks an order ready standing in a shop with
 * one bar of signal, and without it a lost response means a second release code
 * issued and the first one silently dead.
 *
 * <p>Nothing here can say a parcel was collected or delivered. The ladder a
 * seller can climb ends at READY_FOR_PICKUP; SHIPPED is what presenting the
 * release code produces, and DELIVERED is what the recipient proves.
 */
@RestController
@RequestMapping("/vendor/orders")
@PreAuthorize("isAuthenticated()")
@Tag(name = "fulfilment", description = "Accepting, packing and handing over a seller's orders")
public class VendorFulfilmentController {

    private static final String ACCEPT = "fulfilment.accept";
    private static final String REJECT = "fulfilment.reject";
    private static final String READY = "fulfilment.ready";
    private static final String REGENERATE = "fulfilment.code.regenerate";
    private static final String ASSIGN_IMEI = "fulfilment.imei.assign";

    private final VendorFulfilmentService fulfilment;
    private final AuthenticatedCaller caller;
    private final IdempotencyService idempotency;

    public VendorFulfilmentController(VendorFulfilmentService fulfilment, AuthenticatedCaller caller,
                                      IdempotencyService idempotency) {
        this.fulfilment = fulfilment;
        this.caller = caller;
        this.idempotency = idempotency;
    }

    @PostMapping("/{vendorOrderId}/accept")
    @Operation(summary = "Accept an order",
               description = "Moves a new order to PREPARING. Accepting one that is already "
                       + "accepted is the same answer rather than an error — a double-tapped "
                       + "button on a bad connection is the ordinary case here.")
    public ResponseEntity<FulfilmentResponses.Accepted> accept(
            Authentication authentication,
            @PathVariable Long vendorOrderId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        Long userId = caller.userId(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(userId, ACCEPT + ":" + vendorOrderId), idempotencyKey,
                vendorOrderId, 200, FulfilmentResponses.Accepted.class,
                () -> fulfilment.accept(userId, vendorOrderId)));
    }

    @PostMapping("/{vendorOrderId}/reject")
    @Operation(summary = "Turn an order down",
               description = "Cancels this seller's slice only — another vendor's lines on the "
                       + "same payment are untouched — puts the goods back on the shelf, and "
                       + "requests the buyer's refund. Requests it: money leaving the platform is "
                       + "decided by an administrator, never by this call. The reason is required "
                       + "because the buyer reads it, and they have already paid.")
    public ResponseEntity<FulfilmentResponses.Rejected> reject(
            Authentication authentication,
            @PathVariable Long vendorOrderId,
            @Valid @RequestBody FulfilmentRequests.Reject request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        Long userId = caller.userId(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(userId, REJECT + ":" + vendorOrderId), idempotencyKey,
                request, 200, FulfilmentResponses.Rejected.class,
                () -> fulfilment.reject(userId, vendorOrderId, request)));
    }

    @PostMapping("/{vendorOrderId}/ready")
    @Operation(summary = "Packed and waiting for a driver",
               description = "Moves the order to READY_FOR_PICKUP and issues the collection code. "
                       + "Refused while any handset-tracked line still has a phone to scan: that "
                       + "is the last moment at which anyone can say which handset is in the box.")
    public ResponseEntity<FulfilmentResponses.Ready> ready(
            Authentication authentication,
            @PathVariable Long vendorOrderId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        Long userId = caller.userId(authentication);
        FulfilmentResponses.Ready body = idempotency.execute(
                IdempotencyService.scopeFor(userId, READY + ":" + vendorOrderId), idempotencyKey,
                vendorOrderId, 200, FulfilmentResponses.Ready.class,
                () -> fulfilment.ready(userId, vendorOrderId));
        return secret(body);
    }

    @GetMapping("/{vendorOrderId}/handoff-code")
    @Operation(summary = "The collection code",
               description = "Shown to the seller and to nobody else. Never logged, and served "
                       + "no-store — a code sitting in a proxy or a browser's back-forward cache "
                       + "is a code that releases somebody else's goods.")
    public ResponseEntity<FulfilmentResponses.ReleaseCode> handoffCode(
            Authentication authentication, @PathVariable Long vendorOrderId) {
        return secret(fulfilment.releaseCode(caller.userId(authentication), vendorOrderId));
    }

    @PostMapping("/{vendorOrderId}/handoff-code/regenerate")
    @Operation(summary = "Issue a new collection code",
               description = "Kills the current code and issues another. Rate limited, because "
                       + "the reason to reissue — somebody saw it — is also the reason somebody "
                       + "would want a stream of them.")
    public ResponseEntity<FulfilmentResponses.ReleaseCode> regenerate(
            Authentication authentication,
            @PathVariable Long vendorOrderId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        Long userId = caller.userId(authentication);
        FulfilmentResponses.ReleaseCode body = idempotency.execute(
                IdempotencyService.scopeFor(userId, REGENERATE + ":" + vendorOrderId), idempotencyKey,
                vendorOrderId, 200, FulfilmentResponses.ReleaseCode.class,
                () -> fulfilment.regenerateReleaseCode(userId, vendorOrderId));
        return secret(body);
    }

    @GetMapping(value = "/{vendorOrderId}/label", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "The parcel label",
               description = "An A6 PDF carrying the recipient's name, the destination town and a "
                       + "signed QR. No street address, no prices, no contents and no collection "
                       + "code: a parcel on a bench is visible to everyone who walks past it, and "
                       + "the driver resolves the address by scanning.")
    public ResponseEntity<byte[]> label(Authentication authentication,
                                        @PathVariable Long vendorOrderId) {

        VendorFulfilmentService.ParcelLabel label =
                fulfilment.label(caller.userId(authentication), vendorOrderId);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(label.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + label.filename() + "\"")
                // A label names a recipient and a town. It is printed and thrown
                // away, and it has no business in a shared cache.
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(label.content());
    }

    @PostMapping("/{vendorOrderId}/lines/{lineId}/assign-imei")
    @Operation(summary = "Scan a handset onto a line",
               description = "Binds one physical phone to one line, checking that it is this "
                       + "seller's, that it is on the shelf, and that it is the model the buyer "
                       + "actually ordered. A line of two phones takes two calls.")
    public ResponseEntity<FulfilmentResponses.ImeiAssigned> assignImei(
            Authentication authentication,
            @PathVariable Long vendorOrderId,
            @PathVariable Long lineId,
            @Valid @RequestBody FulfilmentRequests.AssignImei request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        Long userId = caller.userId(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(userId, ASSIGN_IMEI + ":" + vendorOrderId + ":" + lineId),
                idempotencyKey, request, 200, FulfilmentResponses.ImeiAssigned.class,
                () -> fulfilment.assignImei(userId, vendorOrderId, lineId, request)));
    }

    /**
     * Serves a response carrying the release code.
     *
     * <p>{@code no-store} rather than {@code no-cache}: the second still permits
     * a copy on disk, and a six-digit code that opens a parcel has no business
     * surviving the request it was asked for in.
     */
    private static <T> ResponseEntity<T> secret(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(body);
    }
}
