package com.sujula.dto.response.admin;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.sujula.model.constant.AuditAction;
import com.sujula.model.constant.CallbackOutcome;
import com.sujula.model.constant.DisputeOutcome;
import com.sujula.model.constant.DisputeReason;
import com.sujula.model.constant.DisputeStatus;
import com.sujula.model.constant.JobRunStatus;
import com.sujula.model.constant.NotificationEvent;
import com.sujula.model.constant.UserRole;

/**
 * What the back office is shown about disputes, comms and the platform itself.
 */
public final class AdminPlatformResponses {

    private AdminPlatformResponses() {}

    // ── Disputes ─────────────────────────────────────────────────────────────

    public record DisputeRow(
            Long disputeId,
            String reference,
            DisputeStatus status,
            DisputeReason reason,

            Long vendorOrderId,
            Long orderId,
            String orderNumber,
            Long vendorId,
            String storeName,

            String raisedByName,
            LocalDateTime raisedAt,

            /** In the seller's own currency, which is the side the ledger holds. */
            BigDecimal amountNative,
            String nativeCurrency,

            /** In the buyer's, at the rate the order was placed at. */
            BigDecimal amount,
            String currency,

            boolean moneyFrozen,
            LocalDateTime frozenAt,

            Long assignedToUserId,
            String assignedToEmail,
            LocalDateTime assignedAt,

            LocalDateTime dueBy,

            /**
             * Hours until the deadline; negative when it has passed.
             *
             * <p>Reported rather than left to a client's clock, because a queue
             * sorted by urgency needs everybody to agree what urgent means.
             */
            Long hoursRemaining,

            boolean overdue,

            /** A call is owed to somebody on this one. */
            boolean callbackOutstanding,

            int messageCount,
            int evidenceCount,

            DisputeOutcome outcome,
            LocalDateTime resolvedAt) {}

    public record DisputeDecided(
            Long disputeId,
            String reference,
            DisputeStatus status,
            DisputeOutcome outcome,

            BigDecimal awardedToBuyerNative,
            BigDecimal keptByVendorNative,
            String nativeCurrency,

            /** What was written to the ledger, named so nothing is implied. */
            List<String> ledgerEntries,

            boolean returnRequired,
            String message) {}

    public record NoteAdded(
            Long noteId,
            Long disputeId,
            boolean internal,
            LocalDateTime createdAt,
            String message) {}

    public record CallbackRow(
            Long callbackId,
            Long disputeId,
            String disputeReference,
            String phone,
            String contactName,
            String preferredLanguage,
            String reason,
            Long requestedByUserId,
            LocalDateTime requestedAt,
            LocalDateTime callBy,
            CallbackOutcome outcome,
            Long calledByUserId,
            LocalDateTime calledAt,
            String notes,
            int attempts,
            boolean outstanding,
            boolean overdue,
            String message) {}

    // ── Comms ────────────────────────────────────────────────────────────────

    public record AnnouncementSent(
            Long announcementId,
            String reference,
            String title,
            UserRole audienceRole,
            String countryCode,
            NotificationEvent event,

            /** Everybody the segment matched. */
            int segmentSize,

            /**
             * Everybody who actually got it.
             *
             * <p>Not the same number. Somebody who switched every channel off for
             * this event is in the segment and receives nothing, and reporting
             * the segment size as the reach would be reporting something untrue.
             */
            int recipients,

            LocalDateTime sentAt,
            String message) {}

    public record NotificationSent(
            Long notificationId,
            Long userId,
            NotificationEvent event,
            boolean delivered,
            String message) {}

    // ── Audit ────────────────────────────────────────────────────────────────

    public record AuditRow(
            Long id,
            LocalDateTime at,
            Long actorUserId,
            String actorEmail,
            String actorName,
            AuditAction action,
            String targetType,
            Long targetId,
            String targetLabel,
            String summary,
            String details,
            String ipAddress) {}

    // ── Platform ─────────────────────────────────────────────────────────────

    public record FlagRow(
            Long id,
            String key,
            String label,
            String description,
            boolean enabled,
            boolean clientVisible,
            Long lastChangedByUserId,
            LocalDateTime lastChangedAt,
            String lastChangeReason) {}

    public record JobRow(
            String name,
            String description,
            boolean enabled,
            long intervalMs,

            LocalDateTime lastStartedAt,
            LocalDateTime lastFinishedAt,
            JobRunStatus lastStatus,
            Long lastDurationMs,
            Integer lastItemsProcessed,
            String lastFailureReason,

            /** When it last finished cleanly — which can be long before the last run. */
            LocalDateTime lastSuccessAt,

            long failuresInLastDay,

            /**
             * Whether it has not run in materially longer than its own interval.
             *
             * <p>The interval is beside it because without one, "last ran an hour
             * ago" is uninterpretable: fine for a daily job, alarming for one
             * that runs every thirty seconds.
             */
            boolean overdue,

            List<String> warnings) {}

    public record JobTriggered(
            String name,
            JobRunStatus status,
            int itemsProcessed,
            Long durationMs,
            String failureReason,
            String message) {}

    // ── Dashboard ────────────────────────────────────────────────────────────

    public record Dashboard(
            LocalDateTime at,

            /** Per currency, because a single GMV figure across three is meaningless. */
            List<GmvLine> gmv,

            long ordersToday,
            long ordersThisMonth,

            long parcelsInFlight,
            long parcelsDeliveredThisMonth,
            long parcelsFailedThisMonth,

            /** Delivered against attempted, as a percentage, this month. */
            BigDecimal deliverySuccessRate,

            long disputesOpen,
            long disputesOverdue,
            long callbacksOutstanding,

            long moderationCasesOpen,
            long kycWaiting,
            long storesAwaitingApproval,

            /** Parcels that have not moved in longer than they should have. */
            long stuckShipments,

            long payoutBatchesAwaitingApproval,
            long failedPayouts,

            List<String> attention) {}

    public record GmvLine(String currency, BigDecimal thisMonth, BigDecimal today, long orders) {}
}
