package com.sujula.controller;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

import com.sujula.dto.request.admin.AdminModerationRequests;
import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.admin.AdminModerationResponses;
import com.sujula.model.constant.KycDocumentStatus;
import com.sujula.model.constant.ModerationCaseStatus;
import com.sujula.model.constant.ModerationReason;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.ProductStatus;
import com.sujula.service.admin.AdminModerationService;
import com.sujula.service.idempotency.IdempotencyService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Who may trade here, and what they may list.
 *
 * <p>Four queues under one controller because they share a property: support
 * works all of them and decides none. The reads take {@code staff}; every
 * decision takes {@code decider}, and one of them — approving or rejecting a
 * product or a review — is the exception the spec allows support to make,
 * because a catalogue queue nobody can clear is a catalogue queue that grows.
 */
@RestController
@RequestMapping("/admin")
@PreAuthorize("isAuthenticated()")
@Tag(name = "admin-moderation", description = "Stores, KYC, listings, reviews and policy cases")
public class AdminModerationController {

    private final AdminModerationService moderation;
    private final StaffCaller staff;
    private final IdempotencyService idempotency;

    public AdminModerationController(AdminModerationService moderation, StaffCaller staff,
                                     IdempotencyService idempotency) {
        this.moderation = moderation;
        this.staff = staff;
        this.idempotency = idempotency;
    }

    // ── Stores ───────────────────────────────────────────────────────────────

    @GetMapping("/stores")
    @Operation(summary = "The stores, with what is holding each of them up",
               description = "payoutsHeld is here because it is the question asked after a "
                       + "suspension sweep — whose money are we sitting on — and the one thing on "
                       + "this screen a seller will telephone about.")
    public ResponseEntity<PagedResponse<AdminModerationResponses.StoreRow>> stores(
            Authentication authentication,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) PartnerStatus status,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) Boolean payoutsHeld,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return uncached(moderation.stores(staff.staff(authentication), q, status, country,
                payoutsHeld, paged(page, size)));
    }

    @PostMapping("/stores/{vendorId}/approve")
    @Operation(summary = "Let a store trade",
               description = "Refused while documents are still waiting in the KYC queue: "
                       + "approving a seller nobody has identified is approving whoever is behind "
                       + "the account, and the money then goes to them.")
    public ResponseEntity<AdminModerationResponses.StoreDecision> approveStore(
            Authentication authentication, @PathVariable Long vendorId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody(required = false) AdminModerationRequests.ApproveStore request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), "admin.stores.approve:" + vendorId),
                key, request, 200, AdminModerationResponses.StoreDecision.class,
                () -> moderation.approveStore(acting, vendorId, request)));
    }

    @PostMapping("/stores/{vendorId}/reject")
    @Operation(summary = "Refuse an application, with a reason they can act on")
    public ResponseEntity<AdminModerationResponses.StoreDecision> rejectStore(
            Authentication authentication, @PathVariable Long vendorId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminModerationRequests.RejectStore request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), "admin.stores.reject:" + vendorId),
                key, request, 200, AdminModerationResponses.StoreDecision.class,
                () -> moderation.rejectStore(acting, vendorId, request)));
    }

    @PostMapping("/stores/{vendorId}/suspend")
    @Operation(summary = "Stop a store, and everything that follows",
               description = "Listings come down and payouts are held, in one operation — a "
                       + "suspension applied in three places is one that gets applied in two. The "
                       + "money is held rather than cancelled: the seller earned it, and telling "
                       + "them the wrong one of those is how a suspension becomes a complaint "
                       + "about theft.")
    public ResponseEntity<AdminModerationResponses.StoreDecision> suspendStore(
            Authentication authentication, @PathVariable Long vendorId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminModerationRequests.SuspendStore request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), "admin.stores.suspend:" + vendorId),
                key, request, 200, AdminModerationResponses.StoreDecision.class,
                () -> moderation.suspendStore(acting, vendorId, request)));
    }

    @PatchMapping("/stores/{vendorId}/commission")
    @Operation(summary = "Change what a store is charged, from a date",
               description = "Writes an effective-dated row rather than a number. Backdating is "
                       + "refused: orders placed before now were priced at the old rate and "
                       + "settled at it, and changing that would mean taking money back from "
                       + "payouts that have already gone.")
    public ResponseEntity<AdminModerationResponses.CommissionChanged> changeCommission(
            Authentication authentication, @PathVariable Long vendorId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminModerationRequests.ChangeCommission request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), "admin.stores.commission:" + vendorId),
                key, request, 200, AdminModerationResponses.CommissionChanged.class,
                () -> moderation.changeCommission(acting, vendorId, request)));
    }

    // ── KYC ──────────────────────────────────────────────────────────────────

    @GetMapping("/kyc/queue")
    @Operation(summary = "Documents waiting to be read",
               description = "Oldest first, and superseded documents left out — a seller who "
                       + "uploaded a clearer photograph should not have the worse one refused "
                       + "after they had already replaced it.")
    public ResponseEntity<PagedResponse<AdminModerationResponses.KycRow>> kycQueue(
            Authentication authentication,
            @RequestParam(required = false) KycDocumentStatus status,
            @RequestParam(required = false) Long vendorId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return uncached(moderation.kycQueue(staff.staff(authentication),
                status == null ? KycDocumentStatus.SUBMITTED : status, vendorId,
                paged(page, size)));
    }

    @PostMapping("/kyc/{documentId}/approve")
    @Operation(summary = "Accept a document",
               description = "A store waiting on its papers moves on by itself once the last one "
                       + "is accepted — otherwise a seller whose documents are all in order waits "
                       + "for somebody to notice.")
    public ResponseEntity<AdminModerationResponses.KycDecision> approveKyc(
            Authentication authentication, @PathVariable Long documentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody(required = false) AdminModerationRequests.ApproveKyc request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), "admin.kyc.approve:" + documentId),
                key, request, 200, AdminModerationResponses.KycDecision.class,
                () -> moderation.approveKyc(acting, documentId, request)));
    }

    @PostMapping("/kyc/{documentId}/reject")
    @Operation(summary = "Refuse a document, saying what is wrong with it")
    public ResponseEntity<AdminModerationResponses.KycDecision> rejectKyc(
            Authentication authentication, @PathVariable Long documentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminModerationRequests.RejectKyc request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), "admin.kyc.reject:" + documentId),
                key, request, 200, AdminModerationResponses.KycDecision.class,
                () -> moderation.rejectKyc(acting, documentId, request)));
    }

    // ── Products ─────────────────────────────────────────────────────────────

    @GetMapping("/moderation/products")
    @Operation(summary = "Listings waiting for review",
               description = "Oldest submission first: a queue worked from the top leaves the "
                       + "bottom untouched forever, and the seller at the bottom is the one who "
                       + "has waited longest to start selling.")
    public ResponseEntity<PagedResponse<AdminModerationResponses.ProductRow>> productQueue(
            Authentication authentication,
            @RequestParam(required = false) ProductStatus status,
            @RequestParam(required = false) Long vendorId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return uncached(moderation.productQueue(staff.staff(authentication), status, vendorId,
                paged(page, size)));
    }

    @PostMapping("/products/{productId}/approve")
    @Operation(summary = "Publish a listing",
               description = "Support may decide this one. A catalogue queue nobody can clear is "
                       + "a catalogue queue that grows, and publishing a phone is not an "
                       + "irreversible act — unlike suspending the seller behind it.")
    public ResponseEntity<AdminModerationResponses.ProductDecision> approveProduct(
            Authentication authentication, @PathVariable Long productId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody(required = false) AdminModerationRequests.ApproveProduct request) {
        var acting = staff.staff(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), "admin.products.approve:" + productId),
                key, request, 200, AdminModerationResponses.ProductDecision.class,
                () -> moderation.approveProduct(acting, productId, request)));
    }

    @PostMapping("/products/{productId}/reject")
    @Operation(summary = "Refuse a listing, with a reason code and words",
               description = "The code is what the platform reports on; the words are what the "
                       + "seller can act on. Both are required.")
    public ResponseEntity<AdminModerationResponses.ProductDecision> rejectProduct(
            Authentication authentication, @PathVariable Long productId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminModerationRequests.RejectProduct request) {
        var acting = staff.staff(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), "admin.products.reject:" + productId),
                key, request, 200, AdminModerationResponses.ProductDecision.class,
                () -> moderation.rejectProduct(acting, productId, request)));
    }

    @PostMapping("/products/{productId}/suspend")
    @Operation(summary = "Take a listing down for breaking a rule",
               description = "Administrators only, and it raises a case whether or not anybody "
                       + "asked — without one there is no record to answer \"why has this seller "
                       + "had four listings taken down\".")
    public ResponseEntity<AdminModerationResponses.ProductDecision> suspendProduct(
            Authentication authentication, @PathVariable Long productId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminModerationRequests.SuspendProduct request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), "admin.products.suspend:" + productId),
                key, request, 200, AdminModerationResponses.ProductDecision.class,
                () -> moderation.suspendProduct(acting, productId, request)));
    }

    @PostMapping("/products")
    @Operation(summary = "List something on a seller's behalf",
               description = "For a shop with no internet, or a phone order. Published straight "
                       + "away rather than queued: an administrator creating a listing has "
                       + "already made the decision the queue exists to make.")
    public ResponseEntity<AdminModerationResponses.ProductDecision> createProduct(
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminModerationRequests.CreateProduct request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), "admin.products.create"), key, request,
                HttpStatus.CREATED.value(), AdminModerationResponses.ProductDecision.class,
                () -> moderation.createProduct(acting, request)));
    }

    @PatchMapping("/products/{productId}")
    @Operation(summary = "Edit somebody else's listing, with a reason")
    public ResponseEntity<AdminModerationResponses.ProductDecision> patchProduct(
            Authentication authentication, @PathVariable Long productId,
            @Valid @RequestBody AdminModerationRequests.PatchProduct request) {
        return ResponseEntity.ok(
                moderation.patchProduct(staff.decider(authentication), productId, request));
    }

    // ── Reviews ──────────────────────────────────────────────────────────────

    @GetMapping("/moderation/reviews")
    @Operation(summary = "Reviews somebody has reported",
               description = "Most-reported first rather than oldest: a review five people have "
                       + "flagged is more likely to break a rule than one a seller flagged an "
                       + "hour ago, and a queue worked by age puts the seller's complaint first.")
    public ResponseEntity<PagedResponse<AdminModerationResponses.ReviewRow>> reviewQueue(
            Authentication authentication,
            @RequestParam(required = false) Boolean hiddenOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return uncached(moderation.reviewQueue(staff.staff(authentication), hiddenOnly,
                paged(page, size)));
    }

    @PostMapping("/reviews/{reviewId}/publish")
    @Operation(summary = "Leave a reported review up",
               description = "The answer most reports get. Support may decide it, because leaving "
                       + "a review where it already is takes nothing away from anybody.")
    public ResponseEntity<AdminModerationResponses.ReviewDecision> publishReview(
            Authentication authentication, @PathVariable Long reviewId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminModerationRequests.ModerateReview request) {
        var acting = staff.staff(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), "admin.reviews.publish:" + reviewId),
                key, request, 200, AdminModerationResponses.ReviewDecision.class,
                () -> moderation.publishReview(acting, reviewId, request)));
    }

    @PostMapping("/reviews/{reviewId}/reject")
    @Operation(summary = "Take a review down",
               description = "Needs a rule it broke. A seller who dislikes a review is not a "
                       + "rule, and a marketplace that removed them on request would have none "
                       + "worth reading — which costs the honest sellers most.")
    public ResponseEntity<AdminModerationResponses.ReviewDecision> rejectReview(
            Authentication authentication, @PathVariable Long reviewId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminModerationRequests.ModerateReview request) {
        var acting = staff.staff(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), "admin.reviews.reject:" + reviewId),
                key, request, 200, AdminModerationResponses.ReviewDecision.class,
                () -> moderation.rejectReview(acting, reviewId, request)));
    }

    // ── Cases ────────────────────────────────────────────────────────────────

    @GetMapping("/moderation/cases")
    @Operation(summary = "Every policy case, soonest deadline first",
               description = "Sorted by deadline rather than age, because those are different "
                       + "questions and only one is about a promise. Each row carries how many "
                       + "times the account has been here before — a fourth report is a different "
                       + "thing from a first.")
    public ResponseEntity<PagedResponse<AdminModerationResponses.CaseRow>> cases(
            Authentication authentication,
            @RequestParam(required = false) ModerationCaseStatus status,
            @RequestParam(required = false) ModerationReason reason,
            @RequestParam(required = false) Long assigneeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return uncached(moderation.cases(staff.staff(authentication), status, reason, assigneeId,
                paged(page, size)));
    }

    @PostMapping("/moderation/cases/{caseId}/resolve")
    @Operation(summary = "Decide a case, and issue what it warrants",
               description = "A sanction is optional and absent is the commonest outcome — most "
                       + "reports are answered by looking and finding nothing. Making that the "
                       + "default is what stops a queue producing punishments because it exists.")
    public ResponseEntity<AdminModerationResponses.CaseResolved> resolveCase(
            Authentication authentication, @PathVariable Long caseId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminModerationRequests.ResolveCase request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), "admin.cases.resolve:" + caseId),
                key, request, 200, AdminModerationResponses.CaseResolved.class,
                () -> moderation.resolveCase(acting, caseId, request)));
    }

    private static <T> ResponseEntity<T> uncached(T body) {
        // These rows carry sellers' documents, buyers' names and what one person
        // said about another. A shared cache holding them would serve one
        // agent's queue to the next person through the proxy.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static Pageable paged(int page, int size) {
        return PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
    }
}
