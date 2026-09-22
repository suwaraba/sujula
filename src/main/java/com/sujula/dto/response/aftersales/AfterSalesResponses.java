package com.sujula.dto.response.aftersales;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.sujula.model.constant.DisputeOutcome;
import com.sujula.model.constant.DisputeReason;
import com.sujula.model.constant.DisputeStatus;
import com.sujula.model.constant.ReturnReason;
import com.sujula.model.constant.ReturnStatus;
import com.sujula.model.constant.ThreadSubject;

/**
 * What each side is told after the sale.
 *
 * <p>Two currencies everywhere a figure appears, with the rate beside them. A
 * buyer in Madrid is being refunded euros and the seller in Banjul is losing
 * dalasis, and both of them are entitled to see their own number and the rate
 * between them — which is the one from the day of the order, not today's.
 */
public final class AfterSalesResponses {

    private AfterSalesResponses() {}

    // ── Shared ───────────────────────────────────────────────────────────────

    /**
     * A sum of money, said twice with the rate that joins them.
     *
     * <p>Never one figure. A refund shown only in euros leaves the seller unable
     * to check it against their payout, and one shown only in dalasis leaves the
     * buyer unable to check it against their card.
     */
    public record Money(BigDecimal amount, String currency,
                        BigDecimal amountNative, String nativeCurrency,
                        BigDecimal rate, LocalDateTime rateAt) {}

    // ── Returns ──────────────────────────────────────────────────────────────

    public record ReturnSummary(
            Long id, String reference, ReturnStatus status, ReturnReason reason,
            String orderNumber, String storeName,
            int itemCount, Money claimed, Money settlement,
            boolean waitingOnYou, String whatHappensNext,
            LocalDateTime createdAt, LocalDateTime updatedAt) {}

    public record ReturnDetail(
            Long id, String reference, ReturnStatus status, ReturnReason reason,
            String orderNumber, String storeName,
            String description, List<String> photoUrls,
            List<ReturnLineView> lines,
            Money claimed,
            Offer offer,
            boolean sellerPaysCarriage, String carriageNote,
            String decisionNote, LocalDateTime decidedAt,
            LocalDateTime receivedAt, String receivedNote,
            Long disputeId, String disputeReference,
            String refundReference,
            boolean waitingOnYou, String whatHappensNext,
            LocalDateTime createdAt, LocalDateTime updatedAt) {}

    public record ReturnLineView(Long orderItemId, String productName, int quantity,
                                 Money unitPrice, List<String> imeis) {}

    /**
     * Money offered instead of the goods coming back.
     *
     * <p>Carries whether it is still open, because an offer that has been
     * superseded or a return that has moved on must not render as a live button.
     */
    public record Offer(Money amount, String note, LocalDateTime offeredAt,
                        LocalDateTime acceptedAt, boolean open) {}

    // ── Disputes ─────────────────────────────────────────────────────────────

    public record DisputeSummary(
            Long id, String reference, DisputeStatus status, DisputeReason reason,
            String orderNumber, String storeName,
            Money claimed, DisputeOutcome outcome,
            boolean moneyFrozen, int messageCount, int evidenceCount,
            LocalDateTime createdAt, LocalDateTime updatedAt) {}

    public record DisputeDetail(
            Long id, String reference, DisputeStatus status, DisputeReason reason,
            String orderNumber, String storeName,
            String description,
            Money claimed, DisputeOutcome outcome, Money awardedToBuyer,
            String resolutionNote, LocalDateTime resolvedAt,
            Long returnRequestId, String returnReference,
            String refundReference,
            Freeze freeze,
            List<DisputeMessageView> messages,
            List<DisputeEvidenceView> evidence,
            String whatHappensNext,
            LocalDateTime createdAt, LocalDateTime updatedAt) {}

    /**
     * What the freeze actually did, told to whichever side is reading.
     *
     * <p>The seller sees it because their balance changed and they will ask why;
     * the buyer sees it because "the money is held" is the reassurance that stops
     * them trying to arrange something privately instead.
     */
    public record Freeze(boolean active, LocalDateTime since, LocalDateTime liftedAt,
                         String ledgerReference, String explanation) {}

    public record DisputeMessageView(Long id, String authorSide, String authorName,
                                     String body, LocalDateTime at) {}

    public record DisputeEvidenceView(Long id, String uploadedBySide, String url,
                                      String contentType, String caption, LocalDateTime at) {}

    // ── Reviews ──────────────────────────────────────────────────────────────

    public record ReviewView(
            Long id, Long productId, String productName,
            int rating, String title, String comment,
            boolean verifiedPurchase,
            String vendorReply, LocalDateTime vendorRepliedAt,
            LocalDateTime editedAt, int editCount,
            /** How long is left to change it, in hours. Zero once the window has closed. */
            long hoursLeftToEdit,
            LocalDateTime createdAt) {}

    public record ReviewDeleted(Long reviewId, String message) {}

    /**
     * What somebody is told after reporting a review.
     *
     * <p>Says plainly that nothing has come down. A reporter who is left to
     * assume the review was removed reports again, and a seller who believes
     * reporting works reports every review below four stars.
     */
    public record ReviewReported(Long reviewId, int reportCount, String message) {}

    // ── Messages ─────────────────────────────────────────────────────────────

    public record ThreadSummary(
            Long id, ThreadSubject subject, String title,
            String orderNumber, Long productId, String productName,
            String withName,
            String lastMessagePreview, LocalDateTime lastMessageAt,
            int unread, boolean closed) {}

    public record ThreadDetail(
            Long id, ThreadSubject subject, String title,
            String orderNumber, Long productId, String productName,
            String withName, boolean closed,
            List<MessageView> messages,
            int total, int page, int size) {}

    public record MessageView(Long id, String senderSide, String senderName,
                              String body, boolean filtered, String filteredKinds,
                              LocalDateTime at, LocalDateTime readAt) {}

    /**
     * The acknowledgement of a sent message.
     *
     * <p>Carries {@code notice} when the filter changed something. The sender is
     * told what happened to their own words rather than discovering it by
     * reading them back — a silent edit is a lie, and one that teaches people to
     * write in code.
     */
    public record MessageSent(Long threadId, MessageView message, String notice) {}
}
