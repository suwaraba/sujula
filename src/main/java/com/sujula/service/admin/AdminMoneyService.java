package com.sujula.service.admin;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;

import com.sujula.dto.request.admin.AdminMoneyRequests;
import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.admin.AdminMoneyResponses;
import com.sujula.model.constant.LedgerEntryType;
import com.sujula.model.constant.PayoutBatchStatus;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.constant.ReportExportStatus;
import com.sujula.model.constant.ReportType;
import com.sujula.model.user.User;

/**
 * The platform's money, for the people who answer for it.
 *
 * <p>Every write here is an administrator's and several of them demand step-up
 * as well, because this is the surface where an admin session left open on a
 * desk is worth something to somebody. Support reads.
 *
 * <p>Two rules are structural rather than conventional. A refund is one seller's
 * sub-order coming back, never a proportion of a payment (C3). And nothing here
 * re-converts: a figure already charged carries the rate it was charged at, and
 * every response repeats that rate rather than computing a fresh one (C2).
 */
public interface AdminMoneyService {

    // ── Payments and refunds ─────────────────────────────────────────────────

    PagedResponse<AdminMoneyResponses.PaymentRow> listPayments(
            String q, PaymentStatus status, String currency, String transactionId,
            LocalDate from, LocalDate to, Pageable pageable);

    AdminMoneyResponses.PaymentRow readPayment(Long paymentId);

    AdminMoneyResponses.RefundMade refund(
            User staff, Long paymentId, AdminMoneyRequests.Refund request);

    // ── Ledger ───────────────────────────────────────────────────────────────

    AdminMoneyResponses.LedgerPage exploreLedger(
            Long vendorId, String currency, LedgerEntryType type, Long orderId,
            String reference, LocalDate from, LocalDate to, Pageable pageable);

    AdminMoneyResponses.Reconciliation reconcile(LocalDate asOf);

    PagedResponse<AdminMoneyResponses.VendorBalance> listBalances(
            Long vendorId, String currency, boolean payableOnly, Pageable pageable);

    // ── Payouts ──────────────────────────────────────────────────────────────

    PagedResponse<AdminMoneyResponses.BatchRow> listBatches(
            PayoutBatchStatus status, String currency, Pageable pageable);

    AdminMoneyResponses.BatchRow readBatch(Long batchId);

    AdminMoneyResponses.BatchSaved prepareBatch(
            User staff, AdminMoneyRequests.PrepareBatch request);

    AdminMoneyResponses.BatchSaved approveBatch(
            User staff, Long batchId, AdminMoneyRequests.ApproveBatch request);

    AdminMoneyResponses.BatchSaved cancelBatch(
            User staff, Long batchId, AdminMoneyRequests.CancelBatch request);

    AdminMoneyResponses.PayoutRetried retryPayout(
            User staff, Long payoutId, AdminMoneyRequests.RetryPayoutItem request);

    // ── FX ───────────────────────────────────────────────────────────────────

    PagedResponse<AdminMoneyResponses.RateRow> rateHistory(
            String currency, LocalDate from, LocalDate to, Pageable pageable);

    AdminMoneyResponses.RatesRefreshed refreshRates(
            User staff, AdminMoneyRequests.RefreshRates request);

    List<AdminMoneyResponses.SpreadRow> spreads();

    AdminMoneyResponses.SpreadSet setSpread(User staff, AdminMoneyRequests.SetSpread request);

    // ── Reports ──────────────────────────────────────────────────────────────

    AdminMoneyResponses.RevenueReport revenue(LocalDate from, LocalDate to, Long vendorId);

    AdminMoneyResponses.ExportQueued requestExport(
            User staff, ReportType type, AdminMoneyRequests.RequestExport request);

    PagedResponse<AdminMoneyResponses.ExportRow> listExports(
            Long requestedByUserId, ReportExportStatus status, Pageable pageable);
}
