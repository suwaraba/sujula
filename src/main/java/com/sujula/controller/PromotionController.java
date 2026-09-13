package com.sujula.controller;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sujula.dto.request.promotion.PromotionRequests;
import com.sujula.dto.response.promotion.PromotionResponses;
import com.sujula.model.constant.PromotionStatus;
import com.sujula.service.idempotency.IdempotencyService;
import com.sujula.service.promotion.PromotionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * A seller's own discounts.
 *
 * <p>Two kinds, and the difference is who knows about them. A promotion is a
 * price the shop is charging and anyone can see it; a coupon is a credential a
 * buyer presents. That is why a coupon can be capped per customer and a
 * promotion cannot — there is nobody to count.
 *
 * <p>Every amount is in the seller's own currency. A buyer paying in EUR sees it
 * converted at the rate their order was quoted at, so the seller is discounting
 * a number they chose rather than one the market moved.
 */
@RestController
@RequestMapping("/vendor")
@PreAuthorize("isAuthenticated()")
@Tag(name = "promotions", description = "A seller's own promotions and coupons")
public class PromotionController {

    private static final int MAX_PAGE_SIZE = 100;

    private static final String CREATE = "promotion.create";
    private static final String ACTIVATE = "promotion.activate";
    private static final String COUPON = "coupon.create";

    private final PromotionService promotions;
    private final AuthenticatedCaller caller;
    private final IdempotencyService idempotency;

    public PromotionController(PromotionService promotions, AuthenticatedCaller caller,
                               IdempotencyService idempotency) {
        this.promotions = promotions;
        this.caller = caller;
        this.idempotency = idempotency;
    }

    // -- Promotions ----------------------------------------------------------

    @GetMapping("/promotions")
    @Operation(summary = "My promotions",
               description = "Each says whether it is discounting anything right now, which is not "
                       + "the same as being active — a scheduled one is active and not yet "
                       + "running — and why not, when it is not.")
    public ResponseEntity<PromotionResponses.PromotionPage> list(
            Authentication authentication,
            @RequestParam(required = false) PromotionStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return ResponseEntity.ok(promotions.list(caller.userId(authentication), status, pageable));
    }

    @PostMapping("/promotions")
    @Operation(summary = "Draft a promotion",
               description = "PERCENT, FIXED, BUY_X_GET_Y, FREE_SHIPPING or BUNDLE. Created as a "
                       + "draft: activation is a separate endpoint because that is where the "
                       + "overlap check lives, and a status a client could set on the way in would "
                       + "be a way straight past it. There is no currency field — a FIXED amount "
                       + "is in the store's settlement currency.")
    public ResponseEntity<PromotionResponses.Promotion> create(
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PromotionRequests.CreatePromotion request) {

        Long userId = caller.userId(authentication);
        PromotionResponses.Promotion created = idempotency.execute(
                IdempotencyService.scopeFor(userId, CREATE), idempotencyKey, request,
                HttpStatus.CREATED.value(), PromotionResponses.Promotion.class,
                () -> promotions.create(userId, request));

        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/promotions/{promotionId}")
    @Operation(summary = "Edit a promotion",
               description = "Absent fields are left alone. Widening a running promotion's goods "
                       + "or window is re-checked for conflicts, because that is exactly how one "
                       + "comes to overlap another.")
    public ResponseEntity<PromotionResponses.Promotion> update(
            Authentication authentication, @PathVariable Long promotionId,
            @Valid @RequestBody PromotionRequests.UpdatePromotion request) {

        return ResponseEntity.ok(promotions.update(
                caller.userId(authentication), promotionId, request));
    }

    @DeleteMapping("/promotions/{promotionId}")
    @Operation(summary = "Remove a promotion",
               description = "Cancelled rather than deleted once it has discounted anything: an "
                       + "order placed under it has to keep explaining why it cost what it did.")
    public ResponseEntity<Void> delete(
            Authentication authentication, @PathVariable Long promotionId) {
        promotions.delete(caller.userId(authentication), promotionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/promotions/{promotionId}/activate")
    @Operation(summary = "Turn it on",
               description = "Refused if another live promotion already discounts the same goods "
                       + "over the same dates. Two discounts on one item do not compound into a "
                       + "price anybody can predict — they compound into whichever the pricing "
                       + "code reaches first. The refusal names the promotion in the way, because "
                       + "'conflicts with an existing promotion' leaves a seller hunting.")
    public ResponseEntity<PromotionResponses.Activated> activate(
            Authentication authentication, @PathVariable Long promotionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        Long userId = caller.userId(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(userId, ACTIVATE + ":" + promotionId), idempotencyKey,
                promotionId, HttpStatus.OK.value(), PromotionResponses.Activated.class,
                () -> promotions.activate(userId, promotionId)));
    }

    // -- Coupons -------------------------------------------------------------

    @GetMapping("/coupons")
    @Operation(summary = "My coupons",
               description = "With how many times each has been redeemed against its limit, which "
                       + "is what a seller watching a campaign actually reads.")
    public ResponseEntity<PromotionResponses.CouponPage> coupons(
            Authentication authentication,
            @RequestParam(defaultValue = "false") boolean activeOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return ResponseEntity.ok(promotions.coupons(
                caller.userId(authentication), activeOnly, pageable));
    }

    @PostMapping("/coupons")
    @Operation(summary = "Issue a coupon",
               description = "The code is upper-cased and unique platform-wide: a buyer holding a "
                       + "word that means one thing in one shop and something else in another is a "
                       + "support ticket nobody can resolve. Set a per-customer limit unless you "
                       + "want a launch offer spent forty times by one person with forty email "
                       + "addresses.")
    public ResponseEntity<PromotionResponses.Coupon> createCoupon(
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PromotionRequests.CreateCoupon request) {

        Long userId = caller.userId(authentication);
        PromotionResponses.Coupon created = idempotency.execute(
                IdempotencyService.scopeFor(userId, COUPON), idempotencyKey, request,
                HttpStatus.CREATED.value(), PromotionResponses.Coupon.class,
                () -> promotions.createCoupon(userId, request));

        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/coupons/{couponId}")
    @Operation(summary = "Edit a coupon",
               description = "Everything but the code, which is printed on flyers and typed from "
                       + "memory — changing it silently breaks every buyer who already has it. The "
                       + "usage limit cannot be set below what has already been redeemed.")
    public ResponseEntity<PromotionResponses.Coupon> updateCoupon(
            Authentication authentication, @PathVariable Long couponId,
            @Valid @RequestBody PromotionRequests.UpdateCoupon request) {

        return ResponseEntity.ok(promotions.updateCoupon(
                caller.userId(authentication), couponId, request));
    }

    @GetMapping("/coupons/{couponId}/redemptions")
    @Operation(summary = "Who used it",
               description = "Names and order ids, not email addresses. A seller needs to "
                       + "recognise a customer, not to be handed a mailing list with their "
                       + "campaign report.")
    public ResponseEntity<PromotionResponses.Redemptions> redemptions(
            Authentication authentication, @PathVariable Long couponId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return ResponseEntity.ok(promotions.redemptions(
                caller.userId(authentication), couponId, pageable));
    }
}
