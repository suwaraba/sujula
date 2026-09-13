package com.sujula.controller;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

import com.sujula.dto.request.inventory.InventoryRequests;
import com.sujula.dto.response.inventory.InventoryResponses;
import com.sujula.model.constant.ImeiStatus;
import com.sujula.service.idempotency.IdempotencyService;
import com.sujula.service.inventory.InventoryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * A seller's stock, and the handsets in it.
 *
 * <p>Nothing here sets a number without saying why. Every change becomes a
 * movement in the ledger, and the ledger includes the orders that took the stock
 * as well as the corrections the seller made — an audit that showed only one of
 * those would be wrong in exactly the case somebody opens it for.
 */
@RestController
@RequestMapping("/vendor")
@PreAuthorize("isAuthenticated()")
@Tag(name = "inventory", description = "A seller's stock, its audit trail, and their handsets")
public class InventoryController {

    private static final int MAX_PAGE_SIZE = 200;

    private static final String ADJUST = "inventory.adjust";
    private static final String BULK = "inventory.bulk";
    private static final String REGISTER = "inventory.imei.register";

    private final InventoryService inventory;
    private final AuthenticatedCaller caller;
    private final IdempotencyService idempotency;

    public InventoryController(InventoryService inventory, AuthenticatedCaller caller,
                               IdempotencyService idempotency) {
        this.inventory = inventory;
        this.caller = caller;
        this.idempotency = idempotency;
    }

    @GetMapping("/inventory")
    @Operation(summary = "What I have",
               description = "Lowest stock first, because that is the order a seller wants to act "
                       + "in. Every row carries its version, so correcting a count never needs a "
                       + "second request to fetch one. The low-stock and out-of-stock counts are "
                       + "across the whole catalogue rather than the page.")
    public ResponseEntity<InventoryResponses.Page> list(
            Authentication authentication,
            @Parameter(description = "Only items at or below their low-stock threshold")
            @RequestParam(defaultValue = "false") boolean lowStock,
            @RequestParam(defaultValue = "false") boolean outOfStock,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return ResponseEntity.ok(inventory.list(
                caller.userId(authentication), lowStock, outOfStock, search, pageable));
    }

    @PatchMapping("/inventory/{variantId}")
    @Operation(summary = "Change one item's stock",
               description = "Send either setTo, for a figure you counted, or delta, for a change "
                       + "you know about. They are not equivalent: a delta is safe whoever else is "
                       + "writing, but an absolute figure is only meaningful against the one it "
                       + "was read from, so setTo has to carry that row's version and a stale one "
                       + "is refused rather than silently overwriting somebody else's count. An "
                       + "item tracked handset by handset refuses both — its stock is however many "
                       + "handsets are in stock.")
    public ResponseEntity<InventoryResponses.Adjusted> adjust(
            Authentication authentication, @PathVariable Long variantId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody InventoryRequests.AdjustStock request) {

        Long userId = caller.userId(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(userId, ADJUST + ":" + variantId), idempotencyKey,
                request, HttpStatus.OK.value(), InventoryResponses.Adjusted.class,
                () -> inventory.adjust(userId, variantId, request)));
    }

    @PostMapping("/inventory/bulk")
    @Operation(summary = "Change several at once",
               description = "Deltas only. A bulk absolute set would overwrite whatever happened "
                       + "while the spreadsheet was open, and there is no version per row a seller "
                       + "would ever have. A bad line fails alone: correcting forty counts should "
                       + "not lose thirty-nine of them to one typo.")
    public ResponseEntity<InventoryResponses.BulkAdjusted> bulkAdjust(
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody InventoryRequests.BulkAdjust request) {

        Long userId = caller.userId(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(userId, BULK), idempotencyKey, request,
                HttpStatus.OK.value(), InventoryResponses.BulkAdjusted.class,
                () -> inventory.bulkAdjust(userId, request)));
    }

    @GetMapping("/inventory/{variantId}/movements")
    @Operation(summary = "What happened to this item's stock",
               description = "Every change and why, sales included. The quantity is signed, so the "
                       + "rows sum to the figure on the shelf and a reconciliation is something "
                       + "anybody can check rather than take on trust.")
    public ResponseEntity<InventoryResponses.Movements> movements(
            Authentication authentication, @PathVariable Long variantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return ResponseEntity.ok(inventory.movements(
                caller.userId(authentication), variantId, pageable));
    }

    // -- Handsets ------------------------------------------------------------

    @GetMapping("/imei-units")
    @Operation(summary = "My handsets",
               description = "Phones are the one thing here a count cannot describe: most are "
                       + "second-hand, and two units of the same model are not interchangeable.")
    public ResponseEntity<InventoryResponses.HandsetPage> handsets(
            Authentication authentication,
            @RequestParam(required = false) Long variantId,
            @RequestParam(required = false) ImeiStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return ResponseEntity.ok(inventory.handsets(
                caller.userId(authentication), variantId, status, pageable));
    }

    @PostMapping("/imei-units")
    @Operation(summary = "Register handsets to a variant",
               description = "A batch, because a seller unpacking a box registers twenty at a "
                       + "time. Each IMEI is checked against its own Luhn digit — which catches "
                       + "almost every hand-entry mistake for nothing — and against the whole "
                       + "platform, because the same handset on two shelves is a phone somebody "
                       + "has sold twice. One mistyped code rejects that line and registers the "
                       + "other nineteen. The variant's stock is then set to however many handsets "
                       + "are in stock.")
    public ResponseEntity<InventoryResponses.Registered> register(
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody InventoryRequests.RegisterImeiUnits request) {

        Long userId = caller.userId(authentication);
        InventoryResponses.Registered registered = idempotency.execute(
                IdempotencyService.scopeFor(userId, REGISTER), idempotencyKey, request,
                HttpStatus.CREATED.value(), InventoryResponses.Registered.class,
                () -> inventory.registerHandsets(userId, request));

        return ResponseEntity.status(HttpStatus.CREATED).body(registered);
    }

    @PatchMapping("/imei-units/{unitId}")
    @Operation(summary = "Re-grade a handset, or move it",
               description = "Grading matters more here than the field suggests: a buyer in Madrid "
                       + "choosing a phone for their brother cannot pick it up, and the gap "
                       + "between 'opened once' and 'scratched screen' is most of the dispute "
                       + "surface. A blocked handset cannot be changed from here — a seller who "
                       + "could clear that flag could launder a stolen phone, and recording an "
                       + "IMEI at all is mostly about making that harder.")
    public ResponseEntity<InventoryResponses.Handset> updateHandset(
            Authentication authentication, @PathVariable Long unitId,
            @Valid @RequestBody InventoryRequests.UpdateImeiUnit request) {

        return ResponseEntity.ok(inventory.updateHandset(
                caller.userId(authentication), unitId, request));
    }
}
