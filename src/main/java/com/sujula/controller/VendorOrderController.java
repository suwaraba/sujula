package com.sujula.controller;

import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.vendor.VendorOrderDetailResponse;
import com.sujula.dto.response.vendor.VendorOrderStatsResponse;
import com.sujula.dto.response.vendor.VendorOrderSummaryResponse;
import com.sujula.exceptions.BadRequestException;
import com.sujula.model.constant.VendorOrderStatus;
import com.sujula.model.user.User;
import com.sujula.service.VendorOrderService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The seller's order desk.
 *
 * <p>There is no vendor id anywhere in these paths. Every endpoint resolves the
 * vendor from the session, so a seller can only ever reach their own slice of an
 * order — there is no parameter to change to see somebody else's.
 *
 * <p>Nothing here returns the buyer, their address, their currency, or what they
 * paid. A vendor gets identifiers and their own money: the order number to quote
 * to support, the product ids to pick, and the goods total, commission and
 * payout in their own settlement currency, exactly as frozen when the order was
 * placed.
 */
@RestController
@RequestMapping("/api/vendor/orders")
@PreAuthorize("hasRole('VENDOR')")
public class VendorOrderController {

    private static final int MAX_PAGE_SIZE = 100;

    private final VendorOrderService vendorOrderService;

    public VendorOrderController(VendorOrderService vendorOrderService) {
        this.vendorOrderService = vendorOrderService;
    }

    /** The fulfilment queue, oldest first. Optionally narrowed to one status. */
    @GetMapping
    public ResponseEntity<PagedResponse<VendorOrderSummaryResponse>> myOrders(
            Authentication authentication,
            @RequestParam(required = false) VendorOrderStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PagedResponse.of(vendorOrderService.findMyOrders(
                currentUserId(authentication), status, pageOf(page, size))));
    }

    /** Dashboard counts and earnings, in the vendor's own currency. */
    @GetMapping("/stats")
    public ResponseEntity<VendorOrderStatsResponse> stats(Authentication authentication) {
        return ResponseEntity.ok(vendorOrderService.stats(currentUserId(authentication)));
    }

    /** What to pack, and what it pays. Another vendor's order is 404, not 403. */
    @GetMapping("/{vendorOrderId}")
    public ResponseEntity<VendorOrderDetailResponse> myOrder(Authentication authentication,
                                                             @PathVariable Long vendorOrderId) {
        return ResponseEntity.ok(vendorOrderService.findMyOrder(
                currentUserId(authentication), vendorOrderId));
    }

    /**
     * Accept, pack, hand over — or cancel while nothing has moved.
     *
     * <p>A vendor cannot mark an order delivered or refunded: the first is the
     * courier's to confirm and the second the platform's, and either would let a
     * seller close an order the buyer is still waiting on.
     */
    @PatchMapping("/{vendorOrderId}/status")
    public ResponseEntity<VendorOrderDetailResponse> updateStatus(Authentication authentication,
                                                                  @PathVariable Long vendorOrderId,
                                                                  @RequestParam VendorOrderStatus status) {
        return ResponseEntity.ok(vendorOrderService.updateStatus(
                currentUserId(authentication), vendorOrderId, status));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static Pageable pageOf(int page, int size) {
        if (page < 0) {
            throw new BadRequestException("page cannot be negative");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        // The queue query carries its own ordering — oldest first, which is the
        // order a seller should work in — so no sort is passed here.
        return PageRequest.of(page, size);
    }

    private Long currentUserId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof User user && user.getId() != null) {
            return user.getId();
        }
        throw new AccessDeniedException("Authentication is required");
    }
}
