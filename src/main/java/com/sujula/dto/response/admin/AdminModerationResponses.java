package com.sujula.dto.response.admin;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.sujula.model.constant.KycDocumentStatus;
import com.sujula.model.constant.ModerationCaseStatus;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.ProductStatus;
import com.sujula.model.constant.SanctionType;

/** What an administrator is shown across the four moderation queues. */
public final class AdminModerationResponses {

    private AdminModerationResponses() {}

    // ── Stores ───────────────────────────────────────────────────────────────

    public record StoreRow(
            Long id, String storeName, String slug, PartnerStatus status,
            String ownerEmail, String countryCode, String settlementCurrency,
            BigDecimal commissionRate,
            /** Whether this store's money is being held, and why. */
            boolean payoutsHeld, String payoutsHeldReason, LocalDateTime payoutsHeldAt,
            int liveProducts, int openCases,
            LocalDateTime createdAt) {}

    /**
     * What a store decision did, including what it did to everything else.
     *
     * <p>The cascade is reported rather than left to be discovered. An
     * administrator who suspends a store and is not told that forty listings
     * came down has not been told what they did.
     */
    public record StoreDecision(
            Long vendorId, PartnerStatus status,
            int productsSuspended, boolean payoutsHeld, int payoutsPutOnHold,
            String caseReference,
            String message) {}

    /**
     * A commission change, with what it does and does not touch.
     *
     * <p>{@code ordersAffected} is always zero and says so. It is here because
     * that is the question a seller asks, and an answer of "none, and here is
     * why" is better than silence.
     */
    public record CommissionChanged(
            Long vendorId, BigDecimal previousRate, BigDecimal newRate,
            LocalDateTime effectiveFrom, int ordersAffected,
            List<CommissionRow> history, String message) {}

    public record CommissionRow(BigDecimal rate, LocalDateTime effectiveFrom,
                                LocalDateTime effectiveUntil, String setBy, String note,
                                boolean inForce) {}

    // ── KYC ──────────────────────────────────────────────────────────────────

    public record KycRow(
            Long id, Long vendorId, String storeName, String type, KycDocumentStatus status,
            String fileUrl, String originalFilename, java.time.LocalDate expiresOn,
            LocalDateTime submittedAt, LocalDateTime reviewedAt, String reviewedBy,
            String rejectionReason,
            /** Whether approving this one completes the set the store needs. */
            boolean completesTheSet) {}

    public record KycDecision(Long documentId, KycDocumentStatus status,
                              PartnerStatus storeStatus, String message) {}

    // ── Products ─────────────────────────────────────────────────────────────

    public record ProductRow(
            Long id, String name, ProductStatus status,
            Long vendorId, String storeName,
            BigDecimal price, String currency, Integer stock,
            String primaryImageUrl,
            LocalDateTime submittedAt, String rejectionReason,
            int openCases) {}

    public record ProductDecision(Long productId, ProductStatus status, String caseReference,
                                  String message) {}

    // ── Reviews ──────────────────────────────────────────────────────────────

    public record ReviewRow(
            Long id, Long productId, String productName, String storeName,
            int rating, String title, String comment,
            String authorName, boolean verifiedPurchase,
            int reportCount, List<String> reportReasons,
            boolean hidden, LocalDateTime createdAt) {}

    public record ReviewDecision(Long reviewId, boolean visible, int reportsCleared,
                                 String message) {}

    // ── Cases ────────────────────────────────────────────────────────────────

    public record CaseRow(
            Long id, String reference, ModerationCaseStatus status, String reason,
            String subjectType, Long subjectId, String subjectLabel,
            String accountableEmail, String storeName,
            String source, String raisedBy,
            String assignedTo, LocalDateTime dueBy, boolean overdue,
            /** How many times this account has been here before. */
            long priorCases,
            LocalDateTime createdAt) {}

    public record CaseResolved(
            Long id, String reference, ModerationCaseStatus status, String outcome,
            SanctionType sanctionIssued, LocalDateTime sanctionExpiresAt,
            boolean accountLocked,
            String message) {}
}
