package com.sujula.service.money.impl;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.model.constant.LedgerEntryType;
import com.sujula.model.constant.PayoutStatus;
import com.sujula.model.constant.ReportExportStatus;
import com.sujula.model.finance.ReportExport;
import com.sujula.model.money.VendorLedgerEntry;
import com.sujula.model.order.Payment;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.user.Payout;
import com.sujula.repository.PaymentRepository;
import com.sujula.repository.finance.ReportExportRepository;
import com.sujula.repository.money.PayoutRepository;
import com.sujula.repository.money.VendorLedgerEntryRepository;
import com.sujula.repository.order.VendorOrderRepository;
import com.sujula.service.StorageService;
import com.sujula.service.reference.CurrencyCatalogue;

import lombok.extern.slf4j.Slf4j;

/**
 * Builds the file a finance export asked for.
 *
 * <p>Kept apart from the worker for the same reason the catalogue jobs are: the
 * worker decides what to run and this does the reading, so one failing export
 * cannot take the queue with it.
 *
 * <p>Every report is sectioned by currency and none of them contains a total
 * across currencies. A spreadsheet is precisely where somebody would sum a
 * column without noticing it spans two, so the file does not put the two in one
 * column to begin with (C2).
 */
@Slf4j
@Component
public class ReportExportProcessor {

    /** Rows read at a time, so a year of ledger does not arrive in one list. */
    private static final int PAGE = 500;

    /** More than this and the file is refused rather than built and never opened. */
    private static final int MAX_ROWS = 200_000;

    private final ReportExportRepository exports;
    private final VendorLedgerEntryRepository ledger;
    private final PayoutRepository payouts;
    private final PaymentRepository payments;
    private final VendorOrderRepository vendorOrders;
    private final StorageService storage;
    private final CurrencyCatalogue currencies;

    @Value("${sujula.money.export-ttl-hours:24}")
    private long ttlHours;

    public ReportExportProcessor(ReportExportRepository exports,
                                 VendorLedgerEntryRepository ledger, PayoutRepository payouts,
                                 PaymentRepository payments, VendorOrderRepository vendorOrders,
                                 StorageService storage, CurrencyCatalogue currencies) {
        this.exports = exports;
        this.ledger = ledger;
        this.payouts = payouts;
        this.payments = payments;
        this.vendorOrders = vendorOrders;
        this.storage = storage;
        this.currencies = currencies;
    }

    @Transactional
    public void runOne(Long exportId) {
        ReportExport export = exports.findById(exportId).orElse(null);
        if (export == null || export.getStatus() != ReportExportStatus.QUEUED) {
            return;
        }
        export.setStatus(ReportExportStatus.BUILDING);
        export.setStartedAt(LocalDateTime.now());
        exports.save(export);

        LocalDate from = export.getFromDate() == null
                ? LocalDate.now().minusMonths(1) : export.getFromDate();
        LocalDate to = export.getToDate() == null ? LocalDate.now() : export.getToDate();

        List<String[]> rows = switch (export.getType()) {
            case LEDGER -> ledgerRows(export, from, to);
            case REVENUE -> revenueRows(export, from, to);
            case PAYOUTS -> payoutRows(export, from, to);
            case PAYMENTS -> paymentRows(export, from, to);
            case RECONCILIATION -> reconciliationRows(export);
        };

        if (rows.size() > MAX_ROWS) {
            // Refused rather than truncated. A file silently missing its last
            // hundred thousand rows is worse than no file: somebody reconciles
            // against it and the difference is the truncation.
            throw new IllegalStateException(
                    "That window holds " + rows.size() + " rows, above the " + MAX_ROWS
                            + " limit. Narrow the dates or name a single seller — a truncated "
                            + "finance file is worse than none, because it reconciles to the "
                            + "wrong answer.");
        }

        // A byte-order mark, deliberately. Without it Excel opens a UTF-8 CSV as
        // the local code page, and every accented character in a Senegalese
        // seller's name comes out as mojibake.
        byte[] content = ("﻿" + toCsv(rows)).getBytes(StandardCharsets.UTF_8);

        String url = storage.upload("finance-exports",
                export.getReference().toLowerCase(Locale.ROOT) + ".csv", content, "text/csv");

        export.setResultUrl(url);
        export.setRowCount(Math.max(0, rows.size() - 1));   // the header is not a row of data
        export.setResultExpiresAt(LocalDateTime.now().plusHours(ttlHours));
        export.setStatus(ReportExportStatus.READY);
        export.setFinishedAt(LocalDateTime.now());
        exports.save(export);

        log.info("[Money] Export {} built: {} rows", export.getReference(), export.getRowCount());
    }

    /**
     * Records a failure on its own transaction.
     *
     * <p>REQUIRES_NEW because the transaction that failed is being rolled back,
     * and a failure reason written inside it would roll back with it — leaving a
     * job stuck in BUILDING with nothing saying why.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long exportId, String reason) {
        exports.findById(exportId).ifPresent(export -> {
            export.setStatus(ReportExportStatus.FAILED);
            export.setFailureReason(reason == null ? "Unknown failure" : truncate(reason, 1000));
            export.setFinishedAt(LocalDateTime.now());
            exports.save(export);
        });
    }

    /** Marks built files whose link has lapsed, so the row keeps telling the truth. */
    @Transactional
    public int expireLapsed() {
        List<ReportExport> lapsed = exports.findLapsed(LocalDateTime.now());
        for (ReportExport export : lapsed) {
            export.setStatus(ReportExportStatus.EXPIRED);
            exports.save(export);
        }
        return lapsed.size();
    }

    // ── The reports themselves ───────────────────────────────────────────────

    private List<String[]> ledgerRows(ReportExport export, LocalDate from, LocalDate to) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] { "occurred_at", "vendor_id", "store", "type", "amount", "currency",
                                "available_from", "vendor_order_id", "order_id", "payout_id",
                                "reference", "description" });

        int page = 0;
        while (true) {
            var slice = ledger.explore(export.getVendorId(), export.getCurrency(), null, null, null,
                    from.atStartOfDay(), to.plusDays(1).atStartOfDay().minusNanos(1),
                    PageRequest.of(page, PAGE));
            for (VendorLedgerEntry entry : slice.getContent()) {
                rows.add(new String[] {
                        text(entry.getOccurredAt()),
                        text(entry.getVendor() == null ? null : entry.getVendor().getId()),
                        entry.getVendor() == null ? "" : entry.getVendor().getStoreName(),
                        text(entry.getType()),
                        text(entry.getAmount()),
                        entry.getCurrency(),
                        text(entry.getAvailableFrom()),
                        text(entry.getVendorOrder() == null ? null : entry.getVendorOrder().getId()),
                        text(entry.getVendorOrder() == null || entry.getVendorOrder().getOrder() == null
                                ? null : entry.getVendorOrder().getOrder().getId()),
                        text(entry.getPayout() == null ? null : entry.getPayout().getId()),
                        entry.getReference(), entry.getDescription() });
            }
            if (slice.isLast()) break;
            page++;
        }
        return rows;
    }

    private List<String[]> revenueRows(ReportExport export, LocalDate from, LocalDate to) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] { "currency", "gross_sales", "commission", "commission_reversed",
                                "refunds", "net_commission", "entries" });

        // One line per currency and no grand total. A spreadsheet is exactly
        // where somebody sums a column without noticing it spans two currencies.
        Map<String, BigDecimal[]> totals = new LinkedHashMap<>();
        Map<String, Long> counts = new LinkedHashMap<>();

        for (Object[] row : ledger.platformTotalsByType(
                from.atStartOfDay(), to.plusDays(1).atStartOfDay(), export.getVendorId())) {
            LedgerEntryType type = (LedgerEntryType) row[0];
            String code = (String) row[1];
            BigDecimal amount = row[2] == null ? BigDecimal.ZERO : (BigDecimal) row[2];
            long count = ((Number) row[3]).longValue();
            if (export.getCurrency() != null && !export.getCurrency().equalsIgnoreCase(code)) {
                continue;
            }

            BigDecimal[] line = totals.computeIfAbsent(code, key -> new BigDecimal[] {
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO });
            switch (type) {
                case SALE -> line[0] = line[0].add(amount);
                case COMMISSION -> line[1] = line[1].add(amount.abs());
                case COMMISSION_REVERSAL -> line[2] = line[2].add(amount);
                case REFUND -> line[3] = line[3].add(amount.abs());
                default -> { }
            }
            counts.merge(code, count, Long::sum);
        }

        totals.forEach((code, line) -> rows.add(new String[] {
                code,
                text(currencies.round(line[0], code)),
                text(currencies.round(line[1], code)),
                text(currencies.round(line[2], code)),
                text(currencies.round(line[3], code)),
                text(currencies.round(line[1].subtract(line[2]), code)),
                text(counts.getOrDefault(code, 0L)) }));
        return rows;
    }

    private List<String[]> payoutRows(ReportExport export, LocalDate from, LocalDate to) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] { "payout_id", "reference", "vendor_id", "store", "amount",
                                "currency", "status", "attempts", "batch", "requested_at",
                                "processed_at", "failure_reason" });

        for (Payout payout : payouts.findAll()) {
            LocalDateTime asked = payout.getRequestedAt() == null
                    ? payout.getCreatedAt() : payout.getRequestedAt();
            if (asked == null || asked.toLocalDate().isBefore(from)
                    || asked.toLocalDate().isAfter(to)) {
                continue;
            }
            if (export.getCurrency() != null
                    && !export.getCurrency().equalsIgnoreCase(payout.getCurrency())) {
                continue;
            }
            if (export.getVendorId() != null && (payout.getVendor() == null
                    || !export.getVendorId().equals(payout.getVendor().getId()))) {
                continue;
            }
            rows.add(new String[] {
                    text(payout.getId()), payout.getReference(),
                    text(payout.getVendor() == null ? null : payout.getVendor().getId()),
                    payout.getVendor() == null ? "" : payout.getVendor().getStoreName(),
                    text(payout.getAmount()), payout.getCurrency(),
                    text(payout.getStatus()), text(payout.getAttempts()),
                    payout.getBatch() == null ? "" : payout.getBatch().getReference(),
                    text(asked), text(payout.getProcessedAt()),
                    payout.getFailureReason() });
        }
        return rows;
    }

    private List<String[]> paymentRows(ReportExport export, LocalDate from, LocalDate to) {
        List<String[]> rows = new ArrayList<>();
        // payer_country and destination_country are both here because on this
        // platform they are routinely different, and a finance file that carried
        // only one of them cannot answer either question honestly (C1).
        rows.add(new String[] { "payment_id", "order_number", "status", "method", "amount",
                                "currency", "refunded", "payer_country", "destination_country",
                                "transaction_id", "paid_at" });

        int page = 0;
        while (true) {
            var slice = payments.adminSearch(null, null, export.getCurrency(), null,
                    from.atStartOfDay(), to.plusDays(1).atStartOfDay(),
                    PageRequest.of(page, PAGE));
            for (Payment payment : slice.getContent()) {
                var order = payment.getOrder();
                rows.add(new String[] {
                        text(payment.getId()),
                        order == null ? "" : order.getOrderNumber(),
                        text(payment.getStatus()), text(payment.getMethod()),
                        text(payment.getAmount()), payment.getCurrency(),
                        text(payment.getAmountRefunded()),
                        order == null ? "" : order.getBillingCountry(),
                        order == null ? "" : order.getShippingCountry(),
                        payment.getTransactionId(), text(payment.getPaidAt()) });
            }
            if (slice.isLast()) break;
            page++;
        }
        return rows;
    }

    private List<String[]> reconciliationRows(ReportExport export) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] { "currency", "escrow_held", "available_to_vendors", "paid_out",
                                "in_flight", "taken_from_buyers", "note" });

        Map<String, BigDecimal> escrow = sums(ledger.escrowByCurrency());
        Map<String, BigDecimal> available = sums(ledger.availableByCurrency());
        Map<String, BigDecimal> paidOut = new LinkedHashMap<>();
        Map<String, BigDecimal> inFlight = new LinkedHashMap<>();
        for (Payout payout : payouts.findAll()) {
            if (payout.getCurrency() == null) continue;
            if (payout.getStatus() == PayoutStatus.COMPLETED) {
                paidOut.merge(payout.getCurrency(), value(payout.getAmount()), BigDecimal::add);
            } else if (payout.getStatus() != null && payout.getStatus().isOpen()) {
                inFlight.merge(payout.getCurrency(), value(payout.getAmount()), BigDecimal::add);
            }
        }
        Map<String, BigDecimal> taken = new LinkedHashMap<>();
        for (Object[] row : payments.takenByCurrency()) {
            taken.put((String) row[0], value((BigDecimal) row[1]).subtract(value((BigDecimal) row[2])));
        }

        java.util.SortedSet<String> codes = new java.util.TreeSet<>();
        codes.addAll(escrow.keySet());
        codes.addAll(available.keySet());
        codes.addAll(paidOut.keySet());
        codes.addAll(inFlight.keySet());
        codes.addAll(taken.keySet());

        for (String code : codes) {
            if (export.getCurrency() != null && !export.getCurrency().equalsIgnoreCase(code)) {
                continue;
            }
            boolean comparable = taken.containsKey(code);
            rows.add(new String[] {
                    code,
                    text(escrow.getOrDefault(code, BigDecimal.ZERO)),
                    text(available.getOrDefault(code, BigDecimal.ZERO)),
                    text(paidOut.getOrDefault(code, BigDecimal.ZERO)),
                    text(inFlight.getOrDefault(code, BigDecimal.ZERO)),
                    comparable ? text(taken.get(code)) : "",
                    comparable
                            ? "Buyers charged in this currency; cross-border orders settle in the "
                              + "seller's, so the two sides are not directly comparable."
                            : "Settlement currency only — no buyer was charged in it." });
        }
        return rows;
    }

    // ── CSV ──────────────────────────────────────────────────────────────────

    private static String toCsv(List<String[]> rows) {
        StringBuilder out = new StringBuilder();
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                if (i > 0) out.append(',');
                out.append(quote(row[i]));
            }
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * Quotes a field, and defuses one that a spreadsheet would execute.
     *
     * <p>A field beginning with =, +, - or @ is a formula to Excel, and these
     * files carry seller-supplied text — a store called {@code =cmd|...} in a
     * finance export opened by somebody in accounts is a real attack, not a
     * theoretical one. Prefixing a quote makes it text without changing what it
     * reads as.
     */
    private static String quote(String value) {
        if (value == null || value.isEmpty()) return "";
        String cleaned = value;
        char first = cleaned.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@'
                || first == '\t' || first == '\r') {
            cleaned = "'" + cleaned;
        }
        if (cleaned.contains(",") || cleaned.contains("\"") || cleaned.contains("\n")
                || cleaned.contains("\r")) {
            return '"' + cleaned.replace("\"", "\"\"") + '"';
        }
        return cleaned;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static BigDecimal value(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }

    private static Map<String, BigDecimal> sums(List<Object[]> rows) {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        for (Object[] row : rows) {
            map.put((String) row[0], value((BigDecimal) row[1]));
        }
        return map;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
