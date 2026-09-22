package com.sujula.dto.response.admin;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.sujula.model.constant.LedgerEntryType;
import com.sujula.model.constant.PaymentMethod;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.constant.PayoutBatchStatus;
import com.sujula.model.constant.PayoutStatus;
import com.sujula.model.constant.ReportExportStatus;
import com.sujula.model.constant.ReportType;

/**
 * What the back office is shown about the platform's money.
 *
 * <p>Nothing in this file adds two currencies together. Every total carries the
 * currency it is in, and a question spanning several comes back as several
 * answers — because a single figure that summed dalasi and CFA would be a number
 * somebody eventually reconciles against a bank statement, and it would never
 * agree (C2).
 */
public final class AdminMoneyResponses {

    private AdminMoneyResponses() {}

    // ── Payments ─────────────────────────────────────────────────────────────

    public record PaymentRow(
            Long paymentId,
            Long orderId,
            String orderNumber,
            PaymentStatus status,
            PaymentMethod method,

            /** What the buyer was charged, in the buyer's currency. */
            BigDecimal amount,
            String currency,
            BigDecimal amountRefunded,

            /** What is still refundable — the charge less what has gone back. */
            BigDecimal refundable,

            /** The provider's own id, which is what a PSP dashboard is searched by. */
            String transactionId,
            String reference,

            String buyerName,
            String buyerEmail,

            /**
             * Where the payer was, which is not where the goods went.
             *
             * <p>Both are here because on this platform they are routinely
             * different, and an agent looking at a payment from Madrid for a
             * parcel to Serrekunda needs to see that rather than conclude
             * something is wrong (C1).
             */
            String payerCountry,
            String destinationCountry,

            LocalDateTime paidAt,
            LocalDateTime createdAt,

            /** One per seller, because that is the unit a refund works on (C3). */
            List<SliceRow> slices,

            List<String> flags) {}

    public record SliceRow(
            Long vendorOrderId,
            Long vendorId,
            String storeName,
            String status,

            /** In the buyer's currency. */
            BigDecimal total,
            String displayCurrency,

            /** In the seller's own, at the rate frozen when the order was placed. */
            BigDecimal totalNative,
            String nativeCurrency,
            BigDecimal fxRate,
            LocalDateTime fxRateAt,

            BigDecimal alreadyRefundedNative,
            boolean escrowReleased,
            boolean disputeFrozen) {}

    public record RefundMade(
            Long refundRequestId,
            String reference,
            Long vendorOrderId,
            String storeName,

            BigDecimal amount,
            String currency,
            BigDecimal amountNative,
            String nativeCurrency,

            /**
             * The rate this refund was converted at, and when it was taken.
             *
             * <p>The order's own, not today's. A refund re-converted at a fresh
             * rate gives the buyer a different number from the one they paid, and
             * the difference comes out of the seller or the platform depending
             * which way the currency moved.
             */
            BigDecimal fxRate,
            LocalDateTime fxRateAt,

            boolean stepUpRequired,
            String message) {}

    // ── Ledger ───────────────────────────────────────────────────────────────

    public record LedgerRow(
            Long entryId,
            LocalDateTime occurredAt,
            Long vendorId,
            String storeName,
            LedgerEntryType type,
            BigDecimal amount,
            String currency,

            /** Null while the money is still in escrow. */
            LocalDateTime availableFrom,

            Long vendorOrderId,
            Long orderId,
            Long payoutId,
            String reference,
            String description) {}

    /** One per currency on the page, never one across them. */
    public record CurrencyTotal(String currency, BigDecimal total, long rows) {}

    public record LedgerPage(
            List<LedgerRow> rows,
            List<CurrencyTotal> totals,
            int page, int size, long totalElements, int totalPages, boolean last) {}

    // ── Balances and reconciliation ──────────────────────────────────────────

    public record VendorBalance(
            Long vendorId,
            String storeName,
            String settlementCurrency,
            String currency,
            BigDecimal available,
            BigDecimal held,
            BigDecimal total,

            /** Committed against an open transfer, so not payable twice. */
            BigDecimal inFlight,

            boolean payoutsHeld,
            String payoutsHeldReason,

            /**
             * True when the ledger holds a currency the store does not settle in.
             *
             * <p>Which should be impossible — entries are posted in the order's
             * native currency and that is the vendor's own — so it is surfaced
             * rather than hidden. A balance in a currency a seller cannot be paid
             * in is money nobody can send them.
             */
            boolean currencyMismatch) {}

    public record Reconciliation(
            LocalDate asOf,
            List<ReconciliationLine> lines,
            List<String> findings,
            String message) {}

    public record ReconciliationLine(
            String currency,

            /** What the ledger says is still held against undelivered goods. */
            BigDecimal escrowHeld,

            /** What the ledger says is payable and has not left. */
            BigDecimal availableToVendors,

            /** What the payment records say buyers actually paid, less refunds. */
            BigDecimal takenFromBuyers,

            /** What has actually been transferred out and completed. */
            BigDecimal paidOut,

            /** What is with a bank right now. */
            BigDecimal inFlight,

            /**
             * What the platform should be holding, less what it says it holds.
             *
             * <p>Zero is the only good answer. Anything else is a real
             * discrepancy or a real bug, and the point of the report is that
             * nobody has to compute it by hand at the end of a month.
             */
            BigDecimal difference,

            boolean balanced,

            /**
             * Why the two sides are not directly comparable, when they are not.
             *
             * <p>Buyers pay in their own currency and sellers settle in theirs, so
             * the payment side of a cross-border order is not denominated in the
             * same currency as the ledger side. Saying so is more useful than a
             * difference that looks alarming and is arithmetic.
             */
            String caveat) {}

    // ── Payouts ──────────────────────────────────────────────────────────────

    public record BatchRow(
            Long batchId,
            String reference,
            PayoutBatchStatus status,
            String currency,
            BigDecimal total,
            int itemCount,

            Long preparedByUserId,
            String preparedByEmail,
            LocalDateTime preparedAt,

            Long approvedByUserId,
            String approvedByEmail,
            LocalDateTime approvedAt,

            String note,
            String exclusions,

            /** Per-item state, so an approver sees what they are releasing. */
            List<BatchItem> items,

            List<String> warnings) {}

    public record BatchItem(
            Long payoutId,
            Long vendorId,
            String storeName,
            BigDecimal amount,
            String currency,
            PayoutStatus status,
            int attempts,
            String failureReason,
            String bankAccountSummary) {}

    public record BatchSaved(
            Long batchId,
            String reference,
            PayoutBatchStatus status,
            String currency,
            BigDecimal total,
            int itemCount,
            String message) {}

    public record PayoutRetried(
            Long payoutId,
            PayoutStatus status,
            int attempts,
            String message) {}

    // ── FX ───────────────────────────────────────────────────────────────────

    public record RateRow(
            Long id,
            String fromCurrency,
            String toCurrency,
            BigDecimal rate,
            LocalDate rateDate,
            LocalDateTime recordedAt,

            /** The spread in force on that date, so the pair reads together. */
            Integer spreadBasisPoints,
            BigDecimal rateWithSpread) {}

    public record RatesRefreshed(
            int pairsRefreshed,
            int pairsUnchanged,
            List<String> failed,
            LocalDateTime at,
            String message) {}

    public record SpreadRow(
            Long id,
            String fromCurrency,
            String toCurrency,
            int basisPoints,
            String asPercentage,
            LocalDateTime effectiveFrom,
            Long setByUserId,
            String reason,

            /** Whether this is the row that would apply to a conversion right now. */
            boolean inForceNow) {}

    public record SpreadSet(
            SpreadRow spread,
            SpreadRow superseded,
            String message) {}

    // ── Reports ──────────────────────────────────────────────────────────────

    public record RevenueReport(
            LocalDate fromDate,
            LocalDate toDate,
            List<RevenueLine> lines,
            String message) {}

    public record RevenueLine(
            String currency,

            /** Gross sold, before the platform's share. */
            BigDecimal grossSales,

            /** The platform's share, as a positive figure. */
            BigDecimal commission,

            /** Handed back on refunded goods, as a positive figure. */
            BigDecimal commissionReversed,

            BigDecimal refunds,

            /** Commission less what was handed back. */
            BigDecimal netCommission,

            /**
             * What the platform made on conversion in this window.
             *
             * <p>Derived from each order's own snapshotted rate and the spread
             * that was in force when it was placed — never from today's spread,
             * which would rewrite what March appears to have earned.
             */
            BigDecimal fxMargin,
            String fxMarginCurrency,

            long orderCount) {}

    public record ExportQueued(
            Long exportId,
            String reference,
            ReportType type,
            ReportExportStatus status,
            LocalDate fromDate,
            LocalDate toDate,

            /** How many more of these this person may ask for in the window. */
            int remainingInWindow,

            String message) {}

    public record ExportRow(
            Long exportId,
            String reference,
            ReportType type,
            ReportExportStatus status,
            Long requestedByUserId,
            String requestedByEmail,
            LocalDate fromDate,
            LocalDate toDate,
            String currency,
            Long vendorId,
            String format,
            Integer rowCount,
            String resultUrl,
            LocalDateTime resultExpiresAt,
            String failureReason,
            LocalDateTime createdAt,
            LocalDateTime finishedAt) {}
}
