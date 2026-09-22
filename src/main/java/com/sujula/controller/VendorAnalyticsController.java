package com.sujula.controller;

import java.time.LocalDate;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sujula.dto.request.money.MoneyRequests;
import com.sujula.dto.response.analytics.AnalyticsResponses;
import com.sujula.dto.response.money.MoneyResponses;
import com.sujula.model.constant.LedgerEntryType;
import com.sujula.model.constant.PayoutStatus;
import com.sujula.service.analytics.VendorAnalyticsService;
import com.sujula.service.idempotency.IdempotencyService;
import com.sujula.service.money.VendorMoneyService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * A seller's own figures and their own money.
 *
 * <p>No vendor id appears in any path. The shop is resolved from the session, so
 * there is no parameter to change to read somebody else's takings.
 *
 * <p>Two rules hold across every response here. <strong>Money is always keyed by
 * currency</strong> — a shop that has traded in dalasi and CFA gets two figures,
 * never a sum, because adding them would need a rate that was not the one any of
 * those orders was settled at (C2). And <strong>the customer view carries no
 * person</strong>: counts and destination countries only, with small counts
 * suppressed, because one order to an unusual country beside a list of what was
 * in it names somebody without using their name.
 */
@RestController
@RequestMapping("/vendor")
@PreAuthorize("isAuthenticated()")
@Tag(name = "analytics", description = "A seller's performance, balance and payouts")
public class VendorAnalyticsController {

    private static final int MAX_PAGE_SIZE = 200;
    private static final String REQUEST_PAYOUT = "money.payout.request";

    private final VendorAnalyticsService analytics;
    private final VendorMoneyService money;
    private final AuthenticatedCaller caller;
    private final IdempotencyService idempotency;

    public VendorAnalyticsController(VendorAnalyticsService analytics, VendorMoneyService money,
                                     AuthenticatedCaller caller, IdempotencyService idempotency) {
        this.analytics = analytics;
        this.money = money;
        this.caller = caller;
        this.idempotency = idempotency;
    }

    // ── Analytics ────────────────────────────────────────────────────────────

    @GetMapping("/analytics/overview")
    @Operation(summary = "Headline figures",
               description = "Revenue, orders, average order value and conversion, against the "
                       + "same length of time immediately before — so a 28-day February against "
                       + "31 days of January does not show a fall that is only a calendar. "
                       + "Revenue is per currency and is never summed across them.")
    public ResponseEntity<AnalyticsResponses.Overview> overview(
            Authentication authentication,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(analytics.overview(caller.userId(authentication), from, to));
    }

    @GetMapping("/analytics/sales")
    @Operation(summary = "Sales over time",
               description = "One series per currency, bucketed by day, week or month. Empty "
                       + "buckets are present rather than omitted: a chart drawn from a sparse "
                       + "series closes the gaps and makes a dead fortnight look like a slow week.")
    public ResponseEntity<AnalyticsResponses.Sales> sales(
            Authentication authentication,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "day") String groupBy) {
        return ResponseEntity.ok(analytics.sales(caller.userId(authentication), from, to, groupBy));
    }

    @GetMapping("/analytics/products")
    @Operation(summary = "What sold, and what was only looked at",
               description = "Top sellers by units, with views, conversion and stock turn. Views "
                       + "count page loads rather than unique people, which the response says "
                       + "plainly — a conversion rate that quietly means something else is worse "
                       + "than none.")
    public ResponseEntity<AnalyticsResponses.Products> products(
            Authentication authentication,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(analytics.products(caller.userId(authentication), from, to, limit));
    }

    @GetMapping("/analytics/customers")
    @Operation(summary = "New against returning, and where parcels go",
               description = "Counts and destination countries. No names, no addresses, no order "
                       + "histories, and countries with only a handful of orders are left out "
                       + "entirely. Destinations are where the goods went, never where the payer "
                       + "was.")
    public ResponseEntity<AnalyticsResponses.Customers> customers(
            Authentication authentication,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(analytics.customers(caller.userId(authentication), from, to));
    }

    @GetMapping("/analytics/delivery")
    @Operation(summary = "How deliveries went",
               description = "Success rate out of parcels that reached an outcome, average hours "
                       + "from accepting to packed, and the destinations where parcels fail.")
    public ResponseEntity<AnalyticsResponses.Delivery> delivery(
            Authentication authentication,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(analytics.delivery(caller.userId(authentication), from, to));
    }

    // ── Money ────────────────────────────────────────────────────────────────

    @GetMapping("/balance")
    @Operation(summary = "What I am owed",
               description = "Available, pending, on hold and at risk — one entry per currency. "
                       + "Pending is escrow: earned, and held until the parcel is confirmed "
                       + "delivered, which is what lets a buyer in Madrid send money for goods "
                       + "they cannot inspect.")
    public ResponseEntity<MoneyResponses.Balance> balance(Authentication authentication) {
        return uncached(money.balance(caller.userId(authentication)));
    }

    @GetMapping("/transactions")
    @Operation(summary = "The ledger",
               description = "Every movement: sales, commission, refunds and payouts, signed, so "
                       + "the column adds up to the balance. Nothing here is ever edited — a "
                       + "refund is a new negative row rather than a smaller sale.")
    public ResponseEntity<MoneyResponses.Transactions> transactions(
            Authentication authentication,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) LedgerEntryType type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return uncached(money.transactions(caller.userId(authentication), currency, type, from, to,
                pageOf(page, size)));
    }

    @GetMapping("/payouts")
    @Operation(summary = "Transfers, past and pending")
    public ResponseEntity<MoneyResponses.Payouts> payouts(
            Authentication authentication,
            @RequestParam(required = false) PayoutStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return uncached(money.payouts(caller.userId(authentication), status, pageOf(page, size)));
    }

    @PostMapping("/payouts/request")
    @Operation(summary = "Ask to be paid",
               description = "Requests whatever is available in one currency — there is no amount "
                       + "to get wrong. Nothing is transferred: the platform reviews payouts "
                       + "first, because money leaving is the one action no later call can undo. "
                       + "The balance reflects the request immediately, so asking twice cannot "
                       + "claim the same money twice.")
    public ResponseEntity<MoneyResponses.PayoutRequested> requestPayout(
            Authentication authentication,
            @Valid @RequestBody(required = false) MoneyRequests.RequestPayout request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        Long userId = caller.userId(authentication);
        MoneyRequests.RequestPayout body = request != null ? request
                : new MoneyRequests.RequestPayout(null, null);

        return ResponseEntity.ok(idempotency.execute(
                IdempotencyService.scopeFor(userId, REQUEST_PAYOUT), idempotencyKey, body,
                200, MoneyResponses.PayoutRequested.class,
                () -> money.requestPayout(userId, body)));
    }

    @GetMapping("/statements/{period}")
    @Operation(summary = "A month's statement",
               description = "A PDF or CSV for one calendar month and one currency, with the "
                       + "balance brought forward and carried forward either side of the "
                       + "movements — so somebody can add the column up and get the last line. "
                       + "Period is YYYY-MM.")
    public ResponseEntity<byte[]> statement(
            Authentication authentication,
            @PathVariable String period,
            @RequestParam(required = false) String currency,
            @RequestParam(defaultValue = "pdf") String format) {

        MoneyResponses.Statement statement =
                money.statement(caller.userId(authentication), period, currency, format);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(statement.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + statement.filename() + "\"")
                // A statement is somebody's income. It has no business in a
                // shared cache.
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(statement.content());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * A seller's balance is private and changes.
     *
     * <p>{@code no-store} rather than merely private: a figure cached on a
     * shared machine is somebody's income sitting in a browser for the next
     * person, and a stale one is worse than none because it will be believed.
     */
    private static <T> ResponseEntity<T> uncached(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(body);
    }

    private static Pageable pageOf(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
    }
}
