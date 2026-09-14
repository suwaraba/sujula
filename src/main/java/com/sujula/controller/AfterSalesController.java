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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sujula.dto.request.aftersales.AfterSalesRequests;
import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.aftersales.AfterSalesResponses;
import com.sujula.model.constant.DisputeStatus;
import com.sujula.model.constant.ReturnStatus;
import com.sujula.service.aftersales.DisputeService;
import com.sujula.service.aftersales.MessagingService;
import com.sujula.service.aftersales.ReturnService;
import com.sujula.service.aftersales.ReviewModerationService;
import com.sujula.service.idempotency.IdempotencyService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * What happens after the money has moved.
 *
 * <p>Four surfaces in one controller, deliberately visible as four. They share a
 * property that is easier to keep in one place than to remember in four: a buyer
 * and a seller both write here, both read the same rows, and every one of those
 * rows is scoped by putting the caller into the query rather than by comparing
 * afterwards.
 *
 * <p>No user id appears in any path. An id of a return, a dispute, a review or a
 * thread does, and the caller goes into the query beside it — so somebody else's
 * row is not found rather than refused, which withholds the fact that it exists.
 *
 * <p>Everything under {@code /returns} and {@code /disputes} is per vendor order
 * (C3). There is no endpoint that returns or disputes "an order", because an
 * order is several sellers who each decide separately and are each paid
 * separately.
 */
@RestController
@PreAuthorize("isAuthenticated()")
@Tag(name = "after-sales", description = "Returns, disputes, reviews and messages")
public class AfterSalesController {

    private static final String OPEN_RETURN = "returns.open";
    private static final String OPEN_DISPUTE = "disputes.open";
    private static final String OPEN_THREAD = "messages.thread.open";

    private final ReturnService returns;
    private final DisputeService disputes;
    private final ReviewModerationService reviews;
    private final MessagingService messages;
    private final AuthenticatedCaller caller;
    private final IdempotencyService idempotency;

    public AfterSalesController(ReturnService returns, DisputeService disputes,
                                ReviewModerationService reviews, MessagingService messages,
                                AuthenticatedCaller caller, IdempotencyService idempotency) {
        this.returns = returns;
        this.disputes = disputes;
        this.reviews = reviews;
        this.messages = messages;
        this.caller = caller;
        this.idempotency = idempotency;
    }

    // ── Returns ──────────────────────────────────────────────────────────────

    @PostMapping("/returns")
    @Operation(summary = "Ask to send something back",
               description = "Against one seller's part of an order and named items, because a "
                       + "buyer who ordered three cases and wants to return one is the ordinary "
                       + "case. The reason decides who pays the carriage, and is frozen at this "
                       + "moment.")
    public ResponseEntity<AfterSalesResponses.ReturnDetail> openReturn(
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AfterSalesRequests.OpenReturn request) {
        Long userId = caller.userId(authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(idempotency.execute(
                IdempotencyService.scopeFor(userId, OPEN_RETURN), idempotencyKey, request,
                HttpStatus.CREATED.value(), AfterSalesResponses.ReturnDetail.class,
                () -> returns.open(userId, request)));
    }

    @GetMapping("/returns")
    @Operation(summary = "Your returns, from whichever side you are on",
               description = "One endpoint rather than a buyer's and a seller's: the row is the "
                       + "same row, and which of the two ownership tests finds it is decided from "
                       + "the caller rather than from anything they can change.")
    public ResponseEntity<PagedResponse<AfterSalesResponses.ReturnSummary>> listReturns(
            Authentication authentication,
            @RequestParam(required = false) ReturnStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(returns.list(caller.userId(authentication), status,
                paged(page, size)));
    }

    @GetMapping("/returns/{returnId}")
    @Operation(summary = "One return")
    public ResponseEntity<AfterSalesResponses.ReturnDetail> returnDetail(
            Authentication authentication, @PathVariable Long returnId) {
        return ResponseEntity.ok(returns.detail(caller.userId(authentication), returnId));
    }

    @PostMapping("/returns/{returnId}/approve")
    @Operation(summary = "Seller: yes, send it back")
    public ResponseEntity<AfterSalesResponses.ReturnDetail> approveReturn(
            Authentication authentication, @PathVariable Long returnId,
            @Valid @RequestBody(required = false) AfterSalesRequests.ApproveReturn request) {
        return ResponseEntity.ok(
                returns.approve(caller.userId(authentication), returnId, request));
    }

    @PostMapping("/returns/{returnId}/reject")
    @Operation(summary = "Seller: no, and why",
               description = "The reason is required. A refusal with nothing behind it is what "
                       + "turns a return into a dispute.")
    public ResponseEntity<AfterSalesResponses.ReturnDetail> rejectReturn(
            Authentication authentication, @PathVariable Long returnId,
            @Valid @RequestBody AfterSalesRequests.RejectReturn request) {
        return ResponseEntity.ok(returns.reject(caller.userId(authentication), returnId, request));
    }

    @PostMapping("/returns/{returnId}/offer-partial-refund")
    @Operation(summary = "Seller: keep it, and have some money back",
               description = "The sensible answer on this route, where carriage from Serrekunda to "
                       + "Banjul can exceed what is being returned. The amount is in the buyer's "
                       + "currency; what comes off the seller is computed at the order's own "
                       + "frozen rate rather than sent, because the rate is not the seller's to "
                       + "choose.")
    public ResponseEntity<AfterSalesResponses.ReturnDetail> offerPartialRefund(
            Authentication authentication, @PathVariable Long returnId,
            @Valid @RequestBody AfterSalesRequests.OfferPartialRefund request) {
        return ResponseEntity.ok(
                returns.offerPartialRefund(caller.userId(authentication), returnId, request));
    }

    @PostMapping("/returns/{returnId}/accept-offer")
    @Operation(summary = "Buyer: take the offer",
               description = "Fixes what is owed at this moment. A later offer from the seller "
                       + "cannot change it.")
    public ResponseEntity<AfterSalesResponses.ReturnDetail> acceptOffer(
            Authentication authentication, @PathVariable Long returnId) {
        return ResponseEntity.ok(returns.acceptOffer(caller.userId(authentication), returnId));
    }

    @PostMapping("/returns/{returnId}/received")
    @Operation(summary = "Seller: the goods are back",
               description = "Asks whether what came back is what went out, because a seller who "
                       + "opens the box and finds a different handset has to be able to say so at "
                       + "that moment rather than argue about it later with nothing on record.")
    public ResponseEntity<AfterSalesResponses.ReturnDetail> markReceived(
            Authentication authentication, @PathVariable Long returnId,
            @Valid @RequestBody(required = false) AfterSalesRequests.ReturnReceived request) {
        return ResponseEntity.ok(
                returns.markReceived(caller.userId(authentication), returnId, request));
    }

    @PostMapping("/returns/{returnId}/escalate")
    @Operation(summary = "Buyer: ask us to decide",
               description = "Opens a dispute, which freezes the seller's money on this part of "
                       + "the order and nothing else. Other sellers on the same payment are paid "
                       + "on time.")
    public ResponseEntity<AfterSalesResponses.ReturnDetail> escalate(
            Authentication authentication, @PathVariable Long returnId,
            @Valid @RequestBody AfterSalesRequests.EscalateReturn request) {
        return ResponseEntity.ok(
                returns.escalate(caller.userId(authentication), returnId, request));
    }

    // ── Disputes ─────────────────────────────────────────────────────────────

    @PostMapping("/disputes")
    @Operation(summary = "Raise a dispute",
               description = "Freezes the seller's money on this sub-order: escrow stops "
                       + "releasing, and where it has already released, a hold takes the money "
                       + "back off their available balance with a reason they can read.")
    public ResponseEntity<AfterSalesResponses.DisputeDetail> openDispute(
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AfterSalesRequests.OpenDispute request) {
        Long userId = caller.userId(authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(idempotency.execute(
                IdempotencyService.scopeFor(userId, OPEN_DISPUTE), idempotencyKey, request,
                HttpStatus.CREATED.value(), AfterSalesResponses.DisputeDetail.class,
                () -> disputes.open(userId, request)));
    }

    @GetMapping("/disputes")
    @Operation(summary = "Your disputes, from either side")
    public ResponseEntity<PagedResponse<AfterSalesResponses.DisputeSummary>> listDisputes(
            Authentication authentication,
            @RequestParam(required = false) DisputeStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(disputes.list(caller.userId(authentication), status,
                paged(page, size)));
    }

    @GetMapping("/disputes/{disputeId}")
    @Operation(summary = "One dispute, with its case file",
               description = "Moderators' internal notes are left out of what either side reads, "
                       + "filtered in the query rather than in a mapper somebody later forgets to "
                       + "apply.")
    public ResponseEntity<AfterSalesResponses.DisputeDetail> disputeDetail(
            Authentication authentication, @PathVariable Long disputeId) {
        return ResponseEntity.ok(disputes.detail(caller.userId(authentication), disputeId));
    }

    @PostMapping("/disputes/{disputeId}/messages")
    @Operation(summary = "Say something on a dispute")
    public ResponseEntity<AfterSalesResponses.DisputeDetail> disputeMessage(
            Authentication authentication, @PathVariable Long disputeId,
            @Valid @RequestBody AfterSalesRequests.DisputeMessage request) {
        return ResponseEntity.ok(
                disputes.addMessage(caller.userId(authentication), disputeId, request));
    }

    @PostMapping("/disputes/{disputeId}/evidence")
    @Operation(summary = "Put a file in",
               description = "The caption is required. Without one a moderator has a photograph "
                       + "of a box. The custody chain is already evidence and nobody uploads it.")
    public ResponseEntity<AfterSalesResponses.DisputeDetail> disputeEvidence(
            Authentication authentication, @PathVariable Long disputeId,
            @Valid @RequestBody AfterSalesRequests.DisputeEvidence request) {
        return ResponseEntity.ok(
                disputes.addEvidence(caller.userId(authentication), disputeId, request));
    }

    @PostMapping("/disputes/{disputeId}/withdraw")
    @Operation(summary = "Take it back, which lifts the freeze",
               description = "Only the person who raised it. A seller who could close a dispute "
                       + "against themselves would be a seller who is never disputed.")
    public ResponseEntity<AfterSalesResponses.DisputeDetail> withdrawDispute(
            Authentication authentication, @PathVariable Long disputeId,
            @Valid @RequestBody(required = false) AfterSalesRequests.WithdrawDispute request) {
        return ResponseEntity.ok(
                disputes.withdraw(caller.userId(authentication), disputeId, request));
    }

    // ── Reviews ──────────────────────────────────────────────────────────────

    @PatchMapping("/reviews/{reviewId}")
    @Operation(summary = "Change your own review, inside the window",
               description = "A window rather than forever: a review that can be rewritten a year "
                       + "later is one a seller can negotiate over, and the rating the next buyer "
                       + "reads stops being about the product.")
    public ResponseEntity<AfterSalesResponses.ReviewView> editReview(
            Authentication authentication, @PathVariable Long reviewId,
            @Valid @RequestBody AfterSalesRequests.EditReview request) {
        return ResponseEntity.ok(reviews.edit(caller.userId(authentication), reviewId, request));
    }

    @DeleteMapping("/reviews/{reviewId}")
    @Operation(summary = "Take your own review down, inside the same window")
    public ResponseEntity<AfterSalesResponses.ReviewDeleted> deleteReview(
            Authentication authentication, @PathVariable Long reviewId) {
        return ResponseEntity.ok(reviews.delete(caller.userId(authentication), reviewId));
    }

    @PostMapping("/reviews/{reviewId}/reply")
    @Operation(summary = "Seller: answer a review, once",
               description = "One answer per review and no edits. A seller who could reply "
                       + "repeatedly would argue under a bad review until the argument was longer "
                       + "than the review.")
    public ResponseEntity<AfterSalesResponses.ReviewView> replyToReview(
            Authentication authentication, @PathVariable Long reviewId,
            @Valid @RequestBody AfterSalesRequests.ReplyToReview request) {
        return ResponseEntity.ok(reviews.reply(caller.userId(authentication), reviewId, request));
    }

    @PostMapping("/reviews/{reviewId}/report")
    @Operation(summary = "Flag a review for somebody to read",
               description = "Takes nothing down. Reviews come down when they break a rule, not "
                       + "when somebody disagrees with them — a marketplace where a one-star "
                       + "review disappears on request has no reviews worth reading.")
    public ResponseEntity<AfterSalesResponses.ReviewReported> reportReview(
            Authentication authentication, @PathVariable Long reviewId,
            @Valid @RequestBody AfterSalesRequests.ReportReview request) {
        return ResponseEntity.ok(reviews.report(caller.userId(authentication), reviewId, request));
    }

    // ── Messages ─────────────────────────────────────────────────────────────

    @GetMapping("/messages/threads")
    @Operation(summary = "Your conversations")
    public ResponseEntity<PagedResponse<AfterSalesResponses.ThreadSummary>> threads(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(messages.threads(caller.userId(authentication),
                paged(page, size)));
    }

    @PostMapping("/messages/threads")
    @Operation(summary = "Start a conversation about an order or a product",
               description = "One or the other, never neither. That is what stops this being a "
                       + "messaging service: there is no request shape that opens a channel to a "
                       + "stranger.")
    public ResponseEntity<AfterSalesResponses.ThreadDetail> openThread(
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AfterSalesRequests.OpenThread request) {
        Long userId = caller.userId(authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(idempotency.execute(
                IdempotencyService.scopeFor(userId, OPEN_THREAD), idempotencyKey, request,
                HttpStatus.CREATED.value(), AfterSalesResponses.ThreadDetail.class,
                () -> messages.openThread(userId, request)));
    }

    @GetMapping("/messages/threads/{threadId}")
    @Operation(summary = "One conversation",
               description = "Opening it marks the other side's messages read, because a separate "
                       + "call for that is one clients forget to make and a badge that never "
                       + "clears.")
    public ResponseEntity<AfterSalesResponses.ThreadDetail> thread(
            Authentication authentication, @PathVariable Long threadId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(messages.thread(caller.userId(authentication), threadId,
                paged(page, size)));
    }

    @PostMapping("/messages/threads/{threadId}/messages")
    @Operation(summary = "Say something",
               description = "Telephone numbers, addresses and links are taken out — not to "
                       + "control the conversation, but because a sale arranged privately has no "
                       + "escrow, no delivery record and no refund. The message still arrives, "
                       + "with a line saying what was removed.")
    public ResponseEntity<AfterSalesResponses.MessageSent> sendMessage(
            Authentication authentication, @PathVariable Long threadId,
            @Valid @RequestBody AfterSalesRequests.SendMessage request) {
        return ResponseEntity.ok(
                messages.send(caller.userId(authentication), threadId, request));
    }

    private static Pageable paged(int page, int size) {
        return PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
    }
}
