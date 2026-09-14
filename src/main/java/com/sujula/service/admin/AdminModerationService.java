package com.sujula.service.admin;

import org.springframework.data.domain.Pageable;

import com.sujula.dto.request.admin.AdminModerationRequests;
import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.admin.AdminModerationResponses;
import com.sujula.model.constant.KycDocumentStatus;
import com.sujula.model.constant.ModerationCaseStatus;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.ProductStatus;
import com.sujula.model.user.User;

/**
 * Who may trade here, and what they may list.
 *
 * <p>Four queues that share one property: every decision writes a
 * {@code ModerationCase} or resolves one, so the platform can answer "what did
 * we suspend people for this quarter" with a query rather than by reading two
 * thousand notes.
 *
 * <p>Two cascades live here and nowhere else. Suspending a store takes its
 * listings down and holds its money — a suspension that stopped new orders but
 * kept paying out last month's would cost the platform money on exactly the
 * stores it has decided not to trust. And changing a commission writes an
 * effective-dated row rather than a number, because an order priced under one
 * rate is an order settled under it.
 */
public interface AdminModerationService {

    // ── Stores ───────────────────────────────────────────────────────────────

    PagedResponse<AdminModerationResponses.StoreRow> stores(
            User staff, String query, PartnerStatus status, String country,
            Boolean payoutsHeld, Pageable pageable);

    AdminModerationResponses.StoreDecision approveStore(
            User staff, Long vendorId, AdminModerationRequests.ApproveStore request);

    AdminModerationResponses.StoreDecision rejectStore(
            User staff, Long vendorId, AdminModerationRequests.RejectStore request);

    /**
     * Suspends a store, and everything that follows from it.
     *
     * <p>Listings come down and payouts are held. Both are consequences rather
     * than separate decisions, which is why they are done here: a suspension
     * applied in three places is one that gets applied in two.
     */
    AdminModerationResponses.StoreDecision suspendStore(
            User staff, Long vendorId, AdminModerationRequests.SuspendStore request);

    /**
     * Changes what a store is charged, from a date.
     *
     * <p>Never backwards. A rate backdated to last month would re-price orders
     * that have already been paid out, and the money to claw back would have to
     * come from somewhere.
     */
    AdminModerationResponses.CommissionChanged changeCommission(
            User staff, Long vendorId, AdminModerationRequests.ChangeCommission request);

    // ── KYC ──────────────────────────────────────────────────────────────────

    PagedResponse<AdminModerationResponses.KycRow> kycQueue(
            User staff, KycDocumentStatus status, Long vendorId, Pageable pageable);

    AdminModerationResponses.KycDecision approveKyc(
            User staff, Long documentId, AdminModerationRequests.ApproveKyc request);

    AdminModerationResponses.KycDecision rejectKyc(
            User staff, Long documentId, AdminModerationRequests.RejectKyc request);

    // ── Products ─────────────────────────────────────────────────────────────

    PagedResponse<AdminModerationResponses.ProductRow> productQueue(
            User staff, ProductStatus status, Long vendorId, Pageable pageable);

    AdminModerationResponses.ProductDecision approveProduct(
            User staff, Long productId, AdminModerationRequests.ApproveProduct request);

    AdminModerationResponses.ProductDecision rejectProduct(
            User staff, Long productId, AdminModerationRequests.RejectProduct request);

    /** Takes a listing down for breaking a rule, and raises the case that says so. */
    AdminModerationResponses.ProductDecision suspendProduct(
            User staff, Long productId, AdminModerationRequests.SuspendProduct request);

    /** Lists something on a seller's behalf — a phone order, or a shop with no internet. */
    AdminModerationResponses.ProductDecision createProduct(
            User staff, AdminModerationRequests.CreateProduct request);

    AdminModerationResponses.ProductDecision patchProduct(
            User staff, Long productId, AdminModerationRequests.PatchProduct request);

    // ── Reviews ──────────────────────────────────────────────────────────────

    /** Reviews somebody has reported, worst first. */
    PagedResponse<AdminModerationResponses.ReviewRow> reviewQueue(
            User staff, Boolean hiddenOnly, Pageable pageable);

    /** Puts a reported review back, which is the answer most of them get. */
    AdminModerationResponses.ReviewDecision publishReview(
            User staff, Long reviewId, AdminModerationRequests.ModerateReview request);

    /** Takes one down, which needs a rule it broke rather than a seller who disliked it. */
    AdminModerationResponses.ReviewDecision rejectReview(
            User staff, Long reviewId, AdminModerationRequests.ModerateReview request);

    // ── Cases ────────────────────────────────────────────────────────────────

    PagedResponse<AdminModerationResponses.CaseRow> cases(
            User staff, ModerationCaseStatus status,
            com.sujula.model.constant.ModerationReason reason, Long assigneeId, Pageable pageable);

    /** Decides a case, and issues the sanction it warrants — or none. */
    AdminModerationResponses.CaseResolved resolveCase(
            User staff, Long caseId, AdminModerationRequests.ResolveCase request);
}
