package com.sujula.controller;

import com.sujula.dto.request.buyerorder.BuyerOrderRequests;
import com.sujula.dto.response.buyerorder.BuyerOrderResponses;
import com.sujula.model.constant.OrderStatus;
import com.sujula.service.buyerorder.BuyerOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A buyer's own orders.
 *
 * <p>Every method here takes the caller's id from the authenticated principal
 * and hands it to the service, which resolves the order by id <em>and</em> buyer
 * in a single query. There is no endpoint on this controller that can be made to
 * read somebody else's order by changing a path variable, because the buyer is
 * never read from the path.
 *
 * <p>The shape of the surface follows from the marketplace: one payment became
 * several sub-orders, so cancellation, receipt and refunds are addressed per
 * seller ({@code /vendor-orders/{vendorOrderId}}) rather than per order.
 */
@RestController
@RequestMapping("/orders")
@PreAuthorize("isAuthenticated()")
@Tag(name = "orders", description = "A buyer's own orders: reads, tracking, cancellation and receipt")
public class BuyerOrderController {

    private static final int MAX_PAGE_SIZE = 50;

    private final BuyerOrderService orders;
    private final AuthenticatedCaller caller;

    public BuyerOrderController(BuyerOrderService orders, AuthenticatedCaller caller) {
        this.orders = orders;
        this.caller = caller;
    }

    @GetMapping
    @Operation(summary = "My orders",
               description = "Newest first. Each row carries the number of sellers in the order, "
                       + "because one payment becomes several sub-orders that ship and cancel on "
                       + "their own timetables — a single status for the whole order is only "
                       + "meaningful while they all agree.")
    public ResponseEntity<BuyerOrderResponses.Page> list(
            Authentication authentication,
            @Parameter(description = "Optional filter on the order's overall status")
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return ResponseEntity.ok(orders.list(caller.userId(authentication), status, pageable));
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "One order",
               description = "Grouped by seller, with each group's own totals, status and "
                       + "cancellability. Someone else's order is not found rather than forbidden: "
                       + "a 403 would confirm the order exists.")
    public ResponseEntity<BuyerOrderResponses.Detail> detail(
            Authentication authentication, @PathVariable Long orderId) {
        return ResponseEntity.ok(orders.detail(caller.userId(authentication), orderId));
    }

    @GetMapping("/{orderId}/tracking")
    @Operation(summary = "Where everything is",
               description = "One timeline per seller, built from the custody trail — the events "
                       + "recorded as the parcel changed hands. Two sellers shipping on two days "
                       + "produce two journeys, not one interleaved list.")
    public ResponseEntity<BuyerOrderResponses.Tracking> tracking(
            Authentication authentication, @PathVariable Long orderId) {
        return ResponseEntity.ok(orders.tracking(caller.userId(authentication), orderId));
    }

    @GetMapping("/{orderId}/invoice")
    @Operation(summary = "A link to the invoice PDF",
               description = "Returns a short-lived signed URL rather than the bytes, so the "
                       + "document can be opened, forwarded or saved without this endpoint being "
                       + "the thing that streams it. The invoice states both parties — who paid "
                       + "and who received — and each seller's figures with the rate they were "
                       + "converted at.")
    public ResponseEntity<BuyerOrderResponses.DocumentLink> invoice(
            Authentication authentication, @PathVariable Long orderId) {
        return ResponseEntity.ok(orders.invoiceLink(caller.userId(authentication), orderId));
    }

    @PostMapping("/{orderId}/cancel")
    @Operation(summary = "Cancel the whole order",
               description = "Allowed only while every seller's goods are still pre-dispatch. Once "
                       + "one seller has shipped, that part is a return rather than a cancellation, "
                       + "and the buyer is told which seller is blocking. Where money was taken, a "
                       + "refund is requested for review — never moved automatically.")
    public ResponseEntity<BuyerOrderResponses.Cancelled> cancel(
            Authentication authentication, @PathVariable Long orderId,
            @Valid @RequestBody(required = false) BuyerOrderRequests.Cancel request) {

        return ResponseEntity.ok(orders.cancel(caller.userId(authentication), orderId,
                request == null ? new BuyerOrderRequests.Cancel(null) : request));
    }

    @PostMapping("/{orderId}/vendor-orders/{vendorOrderId}/cancel")
    @Operation(summary = "Cancel one seller's items",
               description = "Leaves every other seller's items alone — their status, their "
                       + "timeline and their payout are untouched. A refund is requested against "
                       + "this sub-order alone, for an administrator to approve; it is a refund of "
                       + "these goods, not a proportion of the order.")
    public ResponseEntity<BuyerOrderResponses.Cancelled> cancelVendorOrder(
            Authentication authentication, @PathVariable Long orderId,
            @PathVariable Long vendorOrderId,
            @Valid @RequestBody(required = false) BuyerOrderRequests.Cancel request) {

        return ResponseEntity.ok(orders.cancelVendorOrder(caller.userId(authentication), orderId,
                vendorOrderId, request == null ? new BuyerOrderRequests.Cancel(null) : request));
    }

    @PostMapping("/{orderId}/vendor-orders/{vendorOrderId}/confirm-receipt")
    @Operation(summary = "Confirm these items arrived",
               description = "Optional, and it releases this seller's funds early. The "
                       + "confirmation is written into the custody trail as evidence in the "
                       + "buyer's name before the status moves, so the chain shows who said the "
                       + "parcel had arrived.")
    public ResponseEntity<BuyerOrderResponses.ReceiptConfirmed> confirmReceipt(
            Authentication authentication, @PathVariable Long orderId,
            @PathVariable Long vendorOrderId,
            @Valid @RequestBody(required = false) BuyerOrderRequests.ConfirmReceipt request) {

        return ResponseEntity.ok(orders.confirmReceipt(caller.userId(authentication), orderId,
                vendorOrderId,
                request == null ? new BuyerOrderRequests.ConfirmReceipt(null) : request));
    }

    @PostMapping("/{orderId}/lines/{lineId}/reviews")
    @Operation(summary = "Review something you received",
               description = "Verified purchase, established from the order rather than taken from "
                       + "the request: the line must be on this buyer's order and the goods must "
                       + "have arrived.")
    public ResponseEntity<BuyerOrderResponses.ReviewPosted> review(
            Authentication authentication, @PathVariable Long orderId, @PathVariable Long lineId,
            @Valid @RequestBody BuyerOrderRequests.PostReview request) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(orders.review(caller.userId(authentication), orderId, lineId, request));
    }
}
