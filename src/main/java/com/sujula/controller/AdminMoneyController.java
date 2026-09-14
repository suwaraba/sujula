package com.sujula.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
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

import com.sujula.dto.request.admin.AdminMoneyRequests;
import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.admin.AdminMoneyResponses;
import com.sujula.model.constant.LedgerEntryType;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.constant.PayoutBatchStatus;
import com.sujula.model.constant.ReportExportStatus;
import com.sujula.model.constant.ReportType;
import com.sujula.service.admin.AdminMoneyService;
import com.sujula.service.idempotency.IdempotencyService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * The platform's money.
 *
 * <p>Support reads it — an agent on the telephone to somebody whose refund has
 * not arrived needs the payment, the sub-order and the ledger rows behind them.
 * Every write is an administrator's, and the four that move money demand the
 * administrator's own password on top of their session.
 *
 * <p>The refund endpoint takes a payment id and immediately asks which seller's
 * part of it, because that is the unit a refund works on. A payment split
 * between three vendors has no meaningful 30% (C3).
 */
@RestController
@RequestMapping("/admin")
@PreAuthorize("isAuthenticated()")
@Tag(name = "admin-money", description = "Payments, refunds, the ledger, payouts, FX and reports")
public class AdminMoneyController {

    private final AdminMoneyService money;
    private final StaffCaller staff;
    private final IdempotencyService idempotency;

    public AdminMoneyController(AdminMoneyService money, StaffCaller staff,
                                IdempotencyService idempotency) {
        this.money = money;
        this.staff = staff;
        this.idempotency = idempotency;
    }

    // ── Payments ─────────────────────────────────────────────────────────────

    @GetMapping("/payments")
    @Operation(summary = "Search payments, including by the provider's own reference",
               description = "transactionId matches the PSP's id exactly — an agent holding a "
                       + "reference from a bank's screen and no order number has nothing else to "
                       + "go on, and a prefix match there would return other people's payments. "
                       + "Each row carries where the payer was and where the goods went, because "
                       + "here those are routinely different and an agent unfamiliar with that "
                       + "reads it as fraud.")
    public ResponseEntity<PagedResponse<AdminMoneyResponses.PaymentRow>> payments(
            Authentication authentication,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String transactionId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        staff.staff(authentication);
        return uncached(money.listPayments(q, status, currency, transactionId, from, to,
                paged(page, size)));
    }

    @GetMapping("/payments/{paymentId}")
    @Operation(summary = "One payment and its sub-orders",
               description = "Each sub-order carries both figures — what the buyer was charged and "
                       + "what the seller settles in — with the rate they were converted at and "
                       + "the moment it was taken. That pair is what makes a refund explicable a "
                       + "year later.")
    public ResponseEntity<AdminMoneyResponses.PaymentRow> payment(
            Authentication authentication, @PathVariable Long paymentId) {
        staff.staff(authentication);
        return uncached(money.readPayment(paymentId));
    }

    @PostMapping("/payments/{paymentId}/refund")
    @Operation(summary = "Refund one seller's part of a payment",
               description = "Names a sub-order, never a percentage: a payment split between three "
                       + "sellers has no meaningful 30%. Leave both amounts null for the whole "
                       + "sub-order; send both for a partial one, and never only one — working the "
                       + "second out here would use today's exchange rate rather than the one the "
                       + "order was placed at, and the buyer would get back a different number "
                       + "from the one they paid. Above 5,000 in the buyer's currency it demands "
                       + "your password again.")
    public ResponseEntity<AdminMoneyResponses.RefundMade> refund(
            Authentication authentication, @PathVariable Long paymentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminMoneyRequests.Refund request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), "admin.payments.refund:" + paymentId),
                key, request, 200, AdminMoneyResponses.RefundMade.class,
                () -> money.refund(acting, paymentId, request)));
    }

    // ── Ledger ───────────────────────────────────────────────────────────────

    @GetMapping("/ledger")
    @Operation(summary = "The journal, across every seller",
               description = "Totals come back one per currency on the page, never one across "
                       + "them. A journal spanning dalasi and CFA has two totals, and a reader who "
                       + "wanted one is asking for a number that does not exist.")
    public ResponseEntity<AdminMoneyResponses.LedgerPage> ledger(
            Authentication authentication,
            @RequestParam(required = false) Long vendorId,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) LedgerEntryType type,
            @RequestParam(required = false) Long orderId,
            @RequestParam(required = false) String reference,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        staff.staff(authentication);
        return uncached(money.exploreLedger(vendorId, currency, type, orderId, reference, from, to,
                paged(page, size)));
    }

    @GetMapping("/ledger/reconciliation")
    @Operation(summary = "Escrow against payments against what has been paid out",
               description = "One line per currency, with a caveat on each saying whether the two "
                       + "sides are directly comparable — buyers pay in their own currency and "
                       + "sellers settle in theirs, so for cross-border orders they are not. A "
                       + "difference computed across that boundary would be alarming and pure "
                       + "arithmetic.")
    public ResponseEntity<AdminMoneyResponses.Reconciliation> reconciliation(
            Authentication authentication,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        staff.staff(authentication);
        return uncached(money.reconcile(asOf));
    }

    @GetMapping("/balances")
    @Operation(summary = "Every seller's balances, in every currency they hold",
               description = "One row per seller per currency — a seller trading in two has two "
                       + "rows and there is no third that adds them. Each carries what is already "
                       + "committed against an open transfer, so nothing is queued for payout "
                       + "twice.")
    public ResponseEntity<PagedResponse<AdminMoneyResponses.VendorBalance>> balances(
            Authentication authentication,
            @RequestParam(required = false) Long vendorId,
            @RequestParam(required = false) String currency,
            @RequestParam(defaultValue = "false") boolean payableOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        staff.staff(authentication);
        return uncached(money.listBalances(vendorId, currency, payableOnly, paged(page, size)));
    }

    // ── Payouts ──────────────────────────────────────────────────────────────

    @GetMapping("/payouts/batches")
    @Operation(summary = "Payout runs, and what is in them")
    public ResponseEntity<PagedResponse<AdminMoneyResponses.BatchRow>> batches(
            Authentication authentication,
            @RequestParam(required = false) PayoutBatchStatus status,
            @RequestParam(required = false) String currency,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        staff.staff(authentication);
        return uncached(money.listBatches(status, currency, paged(page, size)));
    }

    @GetMapping("/payouts/batches/{batchId}")
    @Operation(summary = "One run, item by item",
               description = "Warns when the recorded total and the items no longer agree. That is "
                       + "the reason the total is stored rather than summed: a batch whose figures "
                       + "have moved since it was assembled is not the batch the approver was "
                       + "shown.")
    public ResponseEntity<AdminMoneyResponses.BatchRow> batch(
            Authentication authentication, @PathVariable Long batchId) {
        staff.staff(authentication);
        return uncached(money.readBatch(batchId));
    }

    @PostMapping("/payouts/batches")
    @Operation(summary = "Assemble a run for one currency",
               description = "Per currency always: sixteen sellers in dalasi and four in CFA are "
                       + "two runs, because a total that added them is a number somebody will "
                       + "nonetheless reconcile against a bank statement. Held stores are excluded "
                       + "and named — their money is being kept, not refused, and an approver who "
                       + "notices a seller missing will otherwise assume a bug. Demands your "
                       + "password.")
    public ResponseEntity<AdminMoneyResponses.BatchSaved> prepareBatch(
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminMoneyRequests.PrepareBatch request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), "admin.payouts.batches.create"),
                key, request, HttpStatus.CREATED.value(),
                AdminMoneyResponses.BatchSaved.class,
                () -> money.prepareBatch(acting, request)));
    }

    @PostMapping("/payouts/batches/{batchId}/approve")
    @Operation(summary = "Release a run — never your own",
               description = "The person who assembled a batch cannot release it. Four eyes on "
                       + "money leaving is not a convention here; it is the only control between a "
                       + "compromised admin session and every vendor balance on the platform. "
                       + "Refused too if the items no longer add to the total that was approved.")
    public ResponseEntity<AdminMoneyResponses.BatchSaved> approveBatch(
            Authentication authentication, @PathVariable Long batchId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminMoneyRequests.ApproveBatch request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(),
                        "admin.payouts.batches.approve:" + batchId),
                key, request, 200, AdminMoneyResponses.BatchSaved.class,
                () -> money.approveBatch(acting, batchId, request)));
    }

    @PostMapping("/payouts/batches/{batchId}/cancel")
    @Operation(summary = "Abandon a run before it is released",
               description = "Only before. Once transfers are with a bank, cancelling the batch "
                       + "here would not recall them — and no ledger entry was written at "
                       + "assembly, so cancelling changes no seller's balance.")
    public ResponseEntity<AdminMoneyResponses.BatchSaved> cancelBatch(
            Authentication authentication, @PathVariable Long batchId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminMoneyRequests.CancelBatch request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(),
                        "admin.payouts.batches.cancel:" + batchId),
                key, request, 200, AdminMoneyResponses.BatchSaved.class,
                () -> money.cancelBatch(acting, batchId, request)));
    }

    @PostMapping("/payouts/items/{payoutId}/retry")
    @Operation(summary = "Try a failed transfer again",
               description = "A new attempt on the same payout, never a second payout — the seller "
                       + "is owed one amount and a second row would look like two. The failed "
                       + "attempt stays in the ledger as a reversal so the seller sees what "
                       + "happened rather than an unexplained gap. After three, the answer is a "
                       + "different destination account.")
    public ResponseEntity<AdminMoneyResponses.PayoutRetried> retryPayout(
            Authentication authentication, @PathVariable Long payoutId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminMoneyRequests.RetryPayoutItem request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), "admin.payouts.retry:" + payoutId),
                key, request, 200, AdminMoneyResponses.PayoutRetried.class,
                () -> money.retryPayout(acting, payoutId, request)));
    }

    // ── FX ───────────────────────────────────────────────────────────────────

    @GetMapping("/fx/rates")
    @Operation(summary = "Rate history, with the spread that applied on each day",
               description = "The pair reads together on purpose. Reconstructing a figure from an "
                       + "old order needs that day's rate AND that day's spread; pairing an old "
                       + "rate with today's spread reconstructs it wrong.")
    public ResponseEntity<PagedResponse<AdminMoneyResponses.RateRow>> rates(
            Authentication authentication,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        staff.staff(authentication);
        return uncached(money.rateHistory(currency, from, to, paged(page, size)));
    }

    @PostMapping("/fx/refresh")
    @Operation(summary = "Pull rates now",
               description = "Updates today's row and never a previous day's, because an order "
                       + "converted yesterday cites yesterday's row. Orders already placed keep "
                       + "the rate they were converted at — this changes what happens next, not "
                       + "what happened.")
    public ResponseEntity<AdminMoneyResponses.RatesRefreshed> refreshRates(
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminMoneyRequests.RefreshRates request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), "admin.fx.refresh"),
                key, request, 200, AdminMoneyResponses.RatesRefreshed.class,
                () -> money.refreshRates(acting, request)));
    }

    @GetMapping("/fx/spread")
    @Operation(summary = "Every spread ever set, and which one is live",
               description = "Rows are appended rather than edited, so the spread that applied to "
                       + "any past order is still answerable. 'In force now' is resolved the same "
                       + "way a conversion resolves it, including most-specific-wins, rather than "
                       + "guessed from the dates.")
    public ResponseEntity<List<AdminMoneyResponses.SpreadRow>> spreads(
            Authentication authentication) {
        staff.staff(authentication);
        return uncached(money.spreads());
    }

    @PatchMapping("/fx/spread")
    @Operation(summary = "Set the platform's spread from a moment",
               description = "In basis points — 150 is 1.5% — because a spread typed as 0.015 and "
                       + "one typed as 1.5 look identical to a form and differ by a hundredfold in "
                       + "what a buyer pays. Never backdated: conversions already made carry the "
                       + "spread that applied at the time.")
    public ResponseEntity<AdminMoneyResponses.SpreadSet> setSpread(
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminMoneyRequests.SetSpread request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), "admin.fx.spread"),
                key, request, 200, AdminMoneyResponses.SpreadSet.class,
                () -> money.setSpread(acting, request)));
    }

    // ── Reports ──────────────────────────────────────────────────────────────

    @GetMapping("/reports/revenue")
    @Operation(summary = "Commission, refunds and FX margin, per settlement currency",
               description = "No grand total. FX margin is reported beside the commission rather "
                       + "than added to it, because it is earned in the buyer's currency and the "
                       + "commission in the seller's — and it is computed from each order's own "
                       + "snapshotted rate, so changing the spread today does not rewrite what "
                       + "March earned.")
    public ResponseEntity<AdminMoneyResponses.RevenueReport> revenue(
            Authentication authentication,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long vendorId) {
        staff.staff(authentication);
        return uncached(money.revenue(from, to, vendorId));
    }

    @GetMapping("/reports/exports")
    @Operation(summary = "Who has exported what",
               description = "The rows outlive the files. A lapsed export keeps its row with the "
                       + "requester's name on it and stops offering the link — 'who exported every "
                       + "seller's earnings in March' has to stay answerable after the file is "
                       + "gone.")
    public ResponseEntity<PagedResponse<AdminMoneyResponses.ExportRow>> exports(
            Authentication authentication,
            @RequestParam(required = false) Long requestedBy,
            @RequestParam(required = false) ReportExportStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        staff.staff(authentication);
        return uncached(money.listExports(requestedBy, status, paged(page, size)));
    }

    @PostMapping("/reports/{type}/export")
    @Operation(summary = "Ask for a finance file",
               description = "Queued rather than returned, and rate limited per person rather than "
                       + "per platform: this file is every seller's earnings, and ten requests in "
                       + "an hour from one account is the shape of an account somebody else is "
                       + "using. The link expires, because one that does not is a file the "
                       + "platform has lost track of.")
    public ResponseEntity<AdminMoneyResponses.ExportQueued> requestExport(
            Authentication authentication, @PathVariable ReportType type,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AdminMoneyRequests.RequestExport request) {
        var acting = staff.decider(authentication);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(idempotency.execute(
                IdempotencyService.scopeFor(acting.getId(), "admin.reports.export:" + type),
                key, request, HttpStatus.ACCEPTED.value(),
                AdminMoneyResponses.ExportQueued.class,
                () -> money.requestExport(acting, type, request)));
    }

    // ── Shared ───────────────────────────────────────────────────────────────

    private static <T> ResponseEntity<T> uncached(T body) {
        // Every seller's earnings and every buyer's spend. A shared cache
        // holding any of it would serve one agent's screen to the next person
        // through the proxy.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static Pageable paged(int page, int size) {
        return PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200));
    }
}
