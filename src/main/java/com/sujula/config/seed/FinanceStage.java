package com.sujula.config.seed;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.sujula.model.constant.ImeiGrade;
import com.sujula.model.constant.ImeiStatus;
import com.sujula.model.constant.LedgerEntryType;
import com.sujula.model.constant.PayoutBatchStatus;
import com.sujula.model.constant.PayoutStatus;
import com.sujula.model.constant.ReportExportStatus;
import com.sujula.model.constant.ReportType;
import com.sujula.model.constant.StockMovementReason;
import com.sujula.model.finance.PayoutBatch;
import com.sujula.model.finance.ReportExport;
import com.sujula.model.inventory.ImeiUnit;
import com.sujula.model.inventory.StockMovement;
import com.sujula.model.money.FxSnapshot;
import com.sujula.model.money.VendorLedgerEntry;
import com.sujula.model.user.Payout;

/**
 * What each seller is owed, what has been paid, and what moved the stock.
 *
 * <p>The ledger is in the <b>seller's</b> currency throughout — dalasi for the
 * Banjul stores, CFA for the Dakar ones — never the buyer's, and never
 * converted for convenience. That is C2's other half: the listing currency is
 * the payout currency, and the rate an entry was born at travels with it in the
 * embedded snapshot so a settlement can still be explained a year later.
 *
 * <p>Entries come in pairs that have to balance: a sale and its commission, a
 * refund and its commission reversal, a payout and — where one bounced — its
 * reversal. A dataset with only the credits looks tidy and settles to the wrong
 * number.
 */
@Component
class FinanceStage implements SeedStage {

    private static final BigDecimal GMD_EUR = SeedCatalogue.rate("0.01341000");
    private static final BigDecimal GMD_GBP = SeedCatalogue.rate("0.01139000");

    @Override
    public String name() {
        return "Ledger, payouts, reports and inventory";
    }

    @Override
    public void seed(SeedCatalogue cat) {
        Map<String, PayoutBatch> batches = batches(cat);
        cat.flush();
        payouts(cat, batches);
        cat.flush();
        ledger(cat);
        reportExports(cat);
        imeiUnits(cat);
        stockMovements(cat);
    }

    // ── Payout batches ───────────────────────────────────────────────────────

    /**
     * Batches, one per status, each in a single currency.
     *
     * <p>Single-currency on purpose: a batch is a file a bank acts on, and a
     * file mixing dalasi and CFA is one no bank will take. The
     * awaiting-approval batch is the row worth having — it cannot be approved by
     * the person who prepared it, and that rule needs two different user ids on
     * the row to be testable at all.
     */
    private Map<String, PayoutBatch> batches(SeedCatalogue cat) {
        Long admin = cat.user("admin").getId();
        Long support = cat.user("support").getId();

        PayoutBatch settled = PayoutBatch.builder()
                .reference("PB-2024-0001").status(PayoutBatchStatus.SETTLED).currency("GMD")
                .preparedByUserId(support).preparedAt(cat.daysAgo(30))
                .approvedByUserId(admin).approvedAt(cat.daysAgo(29))
                .note("Monthly run for the Gambian stores.")
                .build();
        settled.applyTotals(SeedCatalogue.money("24180.00"), 2);
        cat.save(settled);

        PayoutBatch settledXof = PayoutBatch.builder()
                .reference("PB-2024-0002").status(PayoutBatchStatus.SETTLED).currency("XOF")
                .preparedByUserId(support).preparedAt(cat.daysAgo(30))
                .approvedByUserId(admin).approvedAt(cat.daysAgo(29))
                .note("Monthly run for the Senegalese stores.")
                .build();
        settledXof.applyTotals(SeedCatalogue.wholeUnits("482750"), 1);
        cat.save(settledXof);

        PayoutBatch approved = PayoutBatch.builder()
                .reference("PB-2024-0003").status(PayoutBatchStatus.APPROVED).currency("GMD")
                .preparedByUserId(support).preparedAt(cat.daysAgo(2))
                .approvedByUserId(admin).approvedAt(cat.daysAgo(1))
                .note("Approved; the bank file goes out tonight.")
                .build();
        approved.applyTotals(SeedCatalogue.money("9096.00"), 2);
        cat.save(approved);

        PayoutBatch awaiting = PayoutBatch.builder()
                .reference("PB-2024-0004").status(PayoutBatchStatus.AWAITING_APPROVAL)
                .currency("XOF")
                .preparedByUserId(support).preparedAt(cat.hoursAgo(6))
                .note("Prepared by support; needs a second pair of eyes.")
                .exclusions("dakar-tech: 20 000 XOF held against DSP-000002")
                .build();
        awaiting.applyTotals(SeedCatalogue.wholeUnits("85500"), 1);
        cat.save(awaiting);

        // Still being assembled. No approver, no total worth trusting yet.
        PayoutBatch draft = PayoutBatch.builder()
                .reference("PB-2024-0005").status(PayoutBatchStatus.DRAFT).currency("GMD")
                .preparedByUserId(support).preparedAt(cat.hoursAgo(1))
                .note("Next run. Still collecting eligible entries.")
                .build();
        draft.applyTotals(SeedCatalogue.money("0.00"), 0);
        cat.save(draft);

        PayoutBatch cancelled = PayoutBatch.builder()
                .reference("PB-2024-0006").status(PayoutBatchStatus.CANCELLED).currency("GMD")
                .preparedByUserId(support).preparedAt(cat.daysAgo(15))
                .cancelledReason("Built against the wrong period; rebuilt as PB-2024-0003.")
                .cancelledAt(cat.daysAgo(15).plusHours(2))
                .build();
        cancelled.applyTotals(SeedCatalogue.money("18200.00"), 3);
        cat.save(cancelled);

        // Handed back rather than stashed on the bean: this is a singleton,
        // and a field written during one run is a field the next run reads.
        Map<String, PayoutBatch> batches = new LinkedHashMap<>();
        batches.put("settled-gmd", settled);
        batches.put("settled-xof", settledXof);
        batches.put("approved-gmd", approved);
        batches.put("awaiting-xof", awaiting);
        batches.put("draft-gmd", draft);
        batches.put("cancelled-gmd", cancelled);
        return batches;
    }

    // ── Payouts ──────────────────────────────────────────────────────────────

    private void payouts(SeedCatalogue cat, Map<String, PayoutBatch> batches) {
        cat.save(Payout.builder()
                .user(cat.user("fatou")).vendor(cat.vendor("banjul-phones"))
                .requestedBy(cat.user("fatou")).requestedAt(cat.daysAgo(31))
                .period("2024-05")
                .amount(SeedCatalogue.money("18450.00")).currency("GMD")
                .status(PayoutStatus.COMPLETED)
                .reference("PO-2024-0001").batch(batches.get("settled-gmd"))
                .notes("Trust Bank Gambia, account ending 3445.")
                .processedAt(cat.daysAgo(29)).processedBy(cat.user("admin"))
                .attempts(1).lastAttemptAt(cat.daysAgo(29))
                .build());

        cat.save(Payout.builder()
                .user(cat.user("omar")).vendor(cat.vendor("dakar-tech"))
                .requestedBy(cat.user("omar")).requestedAt(cat.daysAgo(31))
                .period("2024-05")
                .amount(SeedCatalogue.wholeUnits("482750")).currency("XOF")
                .status(PayoutStatus.COMPLETED)
                .reference("PO-2024-0002").batch(batches.get("settled-xof"))
                .notes("Ecobank Sénégal.")
                .processedAt(cat.daysAgo(29)).processedBy(cat.user("admin"))
                .attempts(1).lastAttemptAt(cat.daysAgo(29))
                .build());

        cat.save(Payout.builder()
                .user(cat.user("awa")).vendor(cat.vendor("serrekunda-home"))
                .requestedBy(cat.user("awa")).requestedAt(cat.daysAgo(31))
                .period("2024-05")
                .amount(SeedCatalogue.money("5730.00")).currency("GMD")
                .status(PayoutStatus.COMPLETED)
                .reference("PO-2024-0003").batch(batches.get("settled-gmd"))
                .processedAt(cat.daysAgo(29)).processedBy(cat.user("admin"))
                .attempts(1).lastAttemptAt(cat.daysAgo(29))
                .build());

        cat.save(Payout.builder()
                .user(cat.user("fatou")).vendor(cat.vendor("banjul-phones"))
                .requestedBy(cat.user("fatou")).requestedAt(cat.daysAgo(3))
                .period("2024-06")
                .amount(SeedCatalogue.money("7820.00")).currency("GMD")
                .status(PayoutStatus.PROCESSING)
                .reference("PO-2024-0004").batch(batches.get("approved-gmd"))
                .attempts(1).lastAttemptAt(cat.hoursAgo(2))
                .build());

        cat.save(Payout.builder()
                .user(cat.user("awa")).vendor(cat.vendor("serrekunda-home"))
                .requestedBy(cat.user("awa")).requestedAt(cat.daysAgo(3))
                .period("2024-06")
                .amount(SeedCatalogue.money("1276.00")).currency("GMD")
                .status(PayoutStatus.PENDING)
                .reference("PO-2024-0005").batch(batches.get("approved-gmd"))
                .build());

        cat.save(Payout.builder()
                .user(cat.user("omar")).vendor(cat.vendor("dakar-tech"))
                .requestedBy(cat.user("omar")).requestedAt(cat.hoursAgo(8))
                .period("2024-06")
                .amount(SeedCatalogue.wholeUnits("85500")).currency("XOF")
                .status(PayoutStatus.REQUESTED)
                .reference("PO-2024-0006").batch(batches.get("awaiting-xof"))
                .build());

        // Bounced. The account details were wrong, and the failure reason is on
        // the row so the seller can be told something useful.
        cat.save(Payout.builder()
                .user(cat.user("awa")).vendor(cat.vendor("serrekunda-home"))
                .requestedBy(cat.user("awa")).requestedAt(cat.daysAgo(12))
                .period("2024-06")
                .amount(SeedCatalogue.money("470.00")).currency("GMD")
                .status(PayoutStatus.FAILED)
                .reference("PO-2024-0007")
                .failureReason("Bank rejected: account name does not match the account number.")
                .attempts(3).lastAttemptAt(cat.daysAgo(10))
                .processedBy(cat.user("admin"))
                .build());

        // Held because the store is suspended and its payouts are frozen. Not
        // failed, not cancelled — a third thing, and the reason is elsewhere on
        // the vendor row.
        cat.save(Payout.builder()
                .user(cat.user("lamin")).vendor(cat.vendor("kololi-style"))
                .requestedBy(cat.user("lamin")).requestedAt(cat.daysAgo(10))
                .period("2024-06")
                .amount(SeedCatalogue.money("3175.50")).currency("GMD")
                .status(PayoutStatus.ON_HOLD)
                .reference("PO-2024-0008")
                .notes("Payouts held: three open non-delivery cases.")
                .build());

        cat.save(Payout.builder()
                .user(cat.user("lamin")).vendor(cat.vendor("kololi-style"))
                .requestedBy(cat.user("lamin")).requestedAt(cat.daysAgo(16))
                .period("2024-05")
                .amount(SeedCatalogue.money("1665.00")).currency("GMD")
                .status(PayoutStatus.CANCELLED)
                .reference("PO-2024-0009")
                .notes("Cancelled when the batch it belonged to was rebuilt.")
                .build());
    }

    // ── The ledger ───────────────────────────────────────────────────────────

    /**
     * Every movement against a seller's balance, in that seller's currency.
     *
     * <p>{@code availableFrom} is what separates money a seller can draw from
     * money they merely have: an entry with no date on it is held, and a sale
     * becomes available once delivery is proven rather than once the card
     * cleared. Both kinds are here.
     */
    private void ledger(SeedCatalogue cat) {
        // SJL-1001: a sale, its commission and the delivery it paid for, all in
        // dalasi, all released when the parcel was proven delivered.
        entry(cat, "banjul-phones", LedgerEntryType.SALE, "8500.00", "GMD", "1001-bp",
                cat.daysAgo(9).plusHours(1), cat.daysAgo(12),
                "Sale — SJL-1001, Tecno Spark 10", "SJL-1001",
                FxSnapshot.published("GMD", "EUR", GMD_EUR, cat.daysAgo(12)));
        entry(cat, "banjul-phones", LedgerEntryType.COMMISSION, "-680.00", "GMD", "1001-bp",
                cat.daysAgo(9).plusHours(1), cat.daysAgo(12),
                "Commission 8% — SJL-1001", "SJL-1001",
                FxSnapshot.published("GMD", "EUR", GMD_EUR, cat.daysAgo(12)));

        // SJL-1002: still held. The Banjul half has not been delivered, so the
        // money is on the books and not drawable.
        entry(cat, "banjul-phones", LedgerEntryType.SALE, "8500.00", "GMD", "1002-bp",
                null, cat.daysAgo(4),
                "Sale — SJL-1002, Tecno Spark 10 (held until delivery)", "SJL-1002",
                FxSnapshot.published("GMD", "EUR", GMD_EUR, cat.daysAgo(4)));
        entry(cat, "banjul-phones", LedgerEntryType.COMMISSION, "-680.00", "GMD", "1002-bp",
                null, cat.daysAgo(4),
                "Commission 8% — SJL-1002", "SJL-1002",
                FxSnapshot.published("GMD", "EUR", GMD_EUR, cat.daysAgo(4)));

        // The Dakar half of the same payment, in CFA. One card charge, two
        // ledgers, two currencies (C3).
        entry(cat, "dakar-tech", LedgerEntryType.SALE, "95000", "XOF", "1002-dt",
                null, cat.daysAgo(4),
                "Vente — SJL-1002, Galaxy A15 (retenu jusqu'à la livraison)", "SJL-1002",
                FxSnapshot.published("XOF", "EUR", SeedCatalogue.rate("0.00151800"),
                        cat.daysAgo(4)));
        entry(cat, "dakar-tech", LedgerEntryType.COMMISSION, "-9500", "XOF", "1002-dt",
                null, cat.daysAgo(4),
                "Commission 10% — SJL-1002", "SJL-1002",
                FxSnapshot.published("XOF", "EUR", SeedCatalogue.rate("0.00151800"),
                        cat.daysAgo(4)));

        entry(cat, "banjul-phones", LedgerEntryType.SALE, "8775.00", "GMD", "1003-bp",
                cat.daysAgo(3), cat.daysAgo(5),
                "Sale — SJL-1003, Infinix Hot 30 (after coupon)", "SJL-1003",
                FxSnapshot.published("GMD", "GBP", GMD_GBP, cat.daysAgo(5)));
        entry(cat, "banjul-phones", LedgerEntryType.COMMISSION, "-702.00", "GMD", "1003-bp",
                cat.daysAgo(3), cat.daysAgo(5),
                "Commission 8% — SJL-1003", "SJL-1003",
                FxSnapshot.published("GMD", "GBP", GMD_GBP, cat.daysAgo(5)));
        // The charger refund, and the commission that came back with it. A
        // refund without its commission reversal overcharges the seller.
        entry(cat, "banjul-phones", LedgerEntryType.REFUND, "-1000.00", "GMD", "1003-bp",
                cat.hoursAgo(20), cat.hoursAgo(20),
                "Partial refund — SJL-1003, missing charger", "RFD-2024-000005",
                FxSnapshot.published("GMD", "GBP", GMD_GBP, cat.daysAgo(5)));
        entry(cat, "banjul-phones", LedgerEntryType.COMMISSION_REVERSAL, "80.00", "GMD", "1003-bp",
                cat.hoursAgo(20), cat.hoursAgo(20),
                "Commission reversed on the refunded portion", "RFD-2024-000005",
                FxSnapshot.published("GMD", "GBP", GMD_GBP, cat.daysAgo(5)));

        entry(cat, "dakar-tech", LedgerEntryType.SALE, "107500", "XOF", "1006-dt",
                cat.daysAgo(16).plusHours(2), cat.daysAgo(18),
                "Vente — SJL-1006", "SJL-1006", FxSnapshot.identity("XOF", cat.daysAgo(18)));
        entry(cat, "dakar-tech", LedgerEntryType.COMMISSION, "-10750", "XOF", "1006-dt",
                cat.daysAgo(16).plusHours(2), cat.daysAgo(18),
                "Commission 10% — SJL-1006", "SJL-1006",
                FxSnapshot.identity("XOF", cat.daysAgo(18)));
        entry(cat, "dakar-tech", LedgerEntryType.REFUND, "-12500", "XOF", "1006-dt",
                cat.daysAgo(10), cat.daysAgo(10),
                "Remboursement — batterie externe défectueuse", "RFD-2024-000001",
                FxSnapshot.identity("XOF", cat.daysAgo(18)));
        entry(cat, "dakar-tech", LedgerEntryType.COMMISSION_REVERSAL, "1250", "XOF", "1006-dt",
                cat.daysAgo(10), cat.daysAgo(10),
                "Commission reprise sur le remboursement", "RFD-2024-000001",
                FxSnapshot.identity("XOF", cat.daysAgo(18)));

        // A dispute freezes money rather than taking it. The hold and its
        // release are two entries, so the freeze is auditable afterwards.
        entry(cat, "dakar-tech", LedgerEntryType.DISPUTE_HOLD, "-20000", "XOF", "1006-dt",
                cat.daysAgo(7), cat.daysAgo(7),
                "Fonds gelés — litige DSP-000002", "DSP-000002",
                FxSnapshot.identity("XOF", cat.daysAgo(18)));
        entry(cat, "kololi-style", LedgerEntryType.DISPUTE_HOLD, "-2000.00", "GMD", "1007-ks",
                cat.daysAgo(20), cat.daysAgo(20),
                "Funds frozen — dispute DSP-000001", "DSP-000001",
                FxSnapshot.published("GMD", "EUR", GMD_EUR, cat.daysAgo(25)));
        entry(cat, "kololi-style", LedgerEntryType.DISPUTE_HOLD_RELEASE, "2000.00", "GMD",
                "1007-ks", cat.daysAgo(15), cat.daysAgo(15),
                "Hold released on resolution — DSP-000001", "DSP-000001",
                FxSnapshot.published("GMD", "EUR", GMD_EUR, cat.daysAgo(25)));
        entry(cat, "kololi-style", LedgerEntryType.SALE, "1850.00", "GMD", "1007-ks",
                cat.daysAgo(23), cat.daysAgo(25),
                "Sale — SJL-1007, wax print fabric", "SJL-1007",
                FxSnapshot.published("GMD", "EUR", GMD_EUR, cat.daysAgo(25)));
        entry(cat, "kololi-style", LedgerEntryType.COMMISSION, "-185.00", "GMD", "1007-ks",
                cat.daysAgo(23), cat.daysAgo(25),
                "Commission 10% — SJL-1007", "SJL-1007",
                FxSnapshot.published("GMD", "EUR", GMD_EUR, cat.daysAgo(25)));
        entry(cat, "kololi-style", LedgerEntryType.REFUND, "-1850.00", "GMD", "1007-ks",
                cat.daysAgo(14), cat.daysAgo(14),
                "Full refund — dispute decided for the buyer", "RFD-2024-000002",
                FxSnapshot.published("GMD", "EUR", GMD_EUR, cat.daysAgo(25)));
        entry(cat, "kololi-style", LedgerEntryType.COMMISSION_REVERSAL, "185.00", "GMD",
                "1007-ks", cat.daysAgo(14), cat.daysAgo(14),
                "Commission reversed with the refund", "RFD-2024-000002",
                FxSnapshot.published("GMD", "EUR", GMD_EUR, cat.daysAgo(25)));

        // Payouts leaving the balance, and one that bounced back into it.
        entry(cat, "banjul-phones", LedgerEntryType.PAYOUT, "-18450.00", "GMD", null,
                cat.daysAgo(29), cat.daysAgo(29),
                "Payout PO-2024-0001", "PO-2024-0001", null);
        entry(cat, "dakar-tech", LedgerEntryType.PAYOUT, "-482750", "XOF", null,
                cat.daysAgo(29), cat.daysAgo(29),
                "Virement PO-2024-0002", "PO-2024-0002", null);
        entry(cat, "serrekunda-home", LedgerEntryType.PAYOUT, "-470.00", "GMD", null,
                cat.daysAgo(11), cat.daysAgo(11),
                "Payout PO-2024-0007", "PO-2024-0007", null);
        entry(cat, "serrekunda-home", LedgerEntryType.PAYOUT_REVERSAL, "470.00", "GMD", null,
                cat.daysAgo(10), cat.daysAgo(10),
                "PO-2024-0007 returned by the bank", "PO-2024-0007", null);

        // A manual correction, with a person's name against it. An adjustment
        // nobody signed is an adjustment nobody can question.
        entry(cat, "serrekunda-home", LedgerEntryType.ADJUSTMENT, "-120.00", "GMD", null,
                cat.daysAgo(6), cat.daysAgo(6),
                "Correction: delivery charged twice on SJL-1004", "ADJ-2024-0001", null);
        entry(cat, "banjul-phones", LedgerEntryType.ADJUSTMENT, "250.00", "GMD", null,
                cat.daysAgo(5), cat.daysAgo(5),
                "Goodwill credit for a mis-set commission rate in May", "ADJ-2024-0002", null);
    }

    private void entry(SeedCatalogue cat, String vendorKey, LedgerEntryType type, String amount,
                       String currency, String vendorOrderKey, LocalDateTime availableFrom,
                       LocalDateTime occurredAt, String description, String reference,
                       FxSnapshot fx) {
        cat.save(VendorLedgerEntry.builder()
                .vendor(cat.vendor(vendorKey)).type(type)
                .amount(new BigDecimal(amount)).currency(currency)
                .vendorOrder(vendorOrderKey == null ? null : cat.vendorOrder(vendorOrderKey))
                .availableFrom(availableFrom).occurredAt(occurredAt)
                .description(description).reference(reference)
                .fx(fx)
                .createdBy(type == LedgerEntryType.ADJUSTMENT ? cat.user("admin") : null)
                .build());
    }

    // ── Report exports ───────────────────────────────────────────────────────

    private void reportExports(SeedCatalogue cat) {
        cat.save(ReportExport.builder()
                .reference("REX-2024-000001").type(ReportType.LEDGER)
                .status(ReportExportStatus.READY)
                .requestedByUserId(cat.user("admin").getId()).requestedByEmail("admin@sujula.gm")
                .fromDate(LocalDate.now().minusMonths(1)).toDate(LocalDate.now())
                .currency("GMD").format("CSV")
                .resultUrl("https://files.sujula.gm/exports/REX-2024-000001.csv")
                .rowCount(1842).resultExpiresAt(cat.daysAhead(6))
                .startedAt(cat.daysAgo(1)).finishedAt(cat.daysAgo(1).plusSeconds(38))
                .build());
        cat.save(ReportExport.builder()
                .reference("REX-2024-000002").type(ReportType.PAYOUTS)
                .status(ReportExportStatus.READY)
                .requestedByUserId(cat.user("support").getId())
                .requestedByEmail("support@sujula.gm")
                .fromDate(LocalDate.now().minusMonths(2)).toDate(LocalDate.now())
                .currency("XOF").format("XLSX")
                .resultUrl("https://files.sujula.gm/exports/REX-2024-000002.xlsx")
                .rowCount(64).resultExpiresAt(cat.daysAhead(4))
                .startedAt(cat.daysAgo(3)).finishedAt(cat.daysAgo(3).plusSeconds(12))
                .build());
        cat.save(ReportExport.builder()
                .reference("REX-2024-000003").type(ReportType.REVENUE)
                .status(ReportExportStatus.BUILDING)
                .requestedByUserId(cat.user("admin").getId()).requestedByEmail("admin@sujula.gm")
                .fromDate(LocalDate.now().minusYears(1)).toDate(LocalDate.now())
                .format("CSV")
                .startedAt(cat.hoursAgo(1))
                .build());
        cat.save(ReportExport.builder()
                .reference("REX-2024-000004").type(ReportType.PAYMENTS)
                .status(ReportExportStatus.QUEUED)
                .requestedByUserId(cat.user("support").getId())
                .requestedByEmail("support@sujula.gm")
                .fromDate(LocalDate.now().minusDays(7)).toDate(LocalDate.now())
                .format("CSV")
                .build());
        cat.save(ReportExport.builder()
                .reference("REX-2024-000005").type(ReportType.RECONCILIATION)
                .status(ReportExportStatus.FAILED)
                .requestedByUserId(cat.user("admin").getId()).requestedByEmail("admin@sujula.gm")
                .fromDate(LocalDate.now().minusMonths(6)).toDate(LocalDate.now())
                .format("CSV")
                .startedAt(cat.daysAgo(2)).finishedAt(cat.daysAgo(2).plusMinutes(4))
                .failureReason("Object storage is not configured on this deployment, so the "
                        + "finished file had nowhere to go.")
                .build());
        // Built, and the link has since lapsed. The row stays so the request is
        // still visible; the file is gone.
        cat.save(ReportExport.builder()
                .reference("REX-2024-000006").type(ReportType.LEDGER)
                .status(ReportExportStatus.EXPIRED)
                .requestedByUserId(cat.user("support").getId())
                .requestedByEmail("support@sujula.gm")
                .fromDate(LocalDate.now().minusMonths(3)).toDate(LocalDate.now().minusMonths(2))
                .currency("GMD").format("CSV")
                .rowCount(921).resultExpiresAt(cat.daysAgo(3))
                .startedAt(cat.daysAgo(10)).finishedAt(cat.daysAgo(10).plusSeconds(21))
                .build());
        // Scoped to one seller: what a store's own finance screen exports.
        cat.save(ReportExport.builder()
                .reference("REX-2024-000007").type(ReportType.LEDGER)
                .status(ReportExportStatus.READY)
                .requestedByUserId(cat.user("fatou").getId())
                .requestedByEmail("fatou.njie@banjulphones.gm")
                .fromDate(LocalDate.now().minusMonths(1)).toDate(LocalDate.now())
                .currency("GMD").vendorId(cat.vendor("banjul-phones").getId()).format("CSV")
                .resultUrl("https://files.sujula.gm/exports/REX-2024-000007.csv")
                .rowCount(112).resultExpiresAt(cat.daysAhead(5))
                .startedAt(cat.daysAgo(2)).finishedAt(cat.daysAgo(2).plusSeconds(6))
                .build());
    }

    // ── Serialised stock ─────────────────────────────────────────────────────

    /**
     * Individual handsets, by IMEI.
     *
     * <p>Phones are the volume category here and every one of them is a unique
     * object with its own history, so stock is not only a count. The sample
     * covers every status a unit can be in — including the blocked one, which is
     * the row that matters: a handset reported stolen must not be sellable,
     * and a system that only tracks quantities has nowhere to record that.
     */
    private void imeiUnits(SeedCatalogue cat) {
        imei(cat, "350123456789011", "356789012345011", "SN-SPK10-0011", "banjul-phones",
                "spark10", "spark10-128-black", ImeiStatus.SOLD, ImeiGrade.NEW,
                "6800.00", 100, "SJL-1001", cat.daysAgo(11), "1001-spark10", cat.daysAgo(11), null);
        imei(cat, "350123456789012", "356789012345012", "SN-SPK10-0012", "banjul-phones",
                "spark10", "spark10-128-blue", ImeiStatus.SOLD, ImeiGrade.NEW,
                "6800.00", 100, "SJL-1002", cat.daysAgo(3), "1002-spark10", cat.daysAgo(3), null);
        imei(cat, "350123456789013", null, "SN-SPK10-0013", "banjul-phones",
                "spark10", "spark10-128-black", ImeiStatus.IN_STOCK, ImeiGrade.NEW,
                "6800.00", 100, null, null, null, null, null);
        imei(cat, "350123456789014", null, "SN-SPK10-0014", "banjul-phones",
                "spark10", "spark10-128-black", ImeiStatus.IN_STOCK, ImeiGrade.NEW,
                "6800.00", 100, null, null, null, null, null);
        // Held against an order that has not been paid for yet. Not sellable,
        // not sold.
        imei(cat, "350123456789015", null, "SN-SPK10-0015", "banjul-phones",
                "spark10", "spark10-128-black", ImeiStatus.RESERVED, ImeiGrade.NEW,
                "6800.00", 100, "SJL-1008", null, "1008-spark10", cat.hoursAgo(6),
                "Reserved for the guest order SJL-1008.");
        imei(cat, "350987654321098", null, "SN-HOT30-0098", "banjul-phones",
                "hot30", null, ImeiStatus.SOLD, ImeiGrade.NEW,
                "7900.00", 100, "SJL-1003", cat.daysAgo(4), "1003-hot30", cat.daysAgo(4), null);
        imei(cat, "350987654321099", null, "SN-HOT30-0099", "banjul-phones",
                "hot30", null, ImeiStatus.IN_STOCK, ImeiGrade.NEW,
                "7900.00", 100, null, null, null, null, null);
        imei(cat, "351122334455011", null, "SN-IP11-0011", "banjul-phones",
                "iphone11", null, ImeiStatus.IN_STOCK, ImeiGrade.A_GRADE,
                "17800.00", 91, null, null, null, null,
                "Light marks on the frame; screen clean.");
        imei(cat, "351122334455012", null, "SN-IP11-0012", "banjul-phones",
                "iphone11", null, ImeiStatus.IN_STOCK, ImeiGrade.B_GRADE,
                "16200.00", 86, null, null, null, null,
                "Scuffed corner. Priced down on the shop floor.");
        // Came back from a buyer and has not been graded again yet.
        imei(cat, "351122334455013", null, "SN-IP11-0013", "banjul-phones",
                "iphone11", null, ImeiStatus.RETURNED, ImeiGrade.C_GRADE,
                "14000.00", 79, "SJL-0994", cat.daysAgo(40), null, null,
                "Returned as faulty; battery at 79%.");
        imei(cat, "351122334455014", null, "SN-IP11-0014", "banjul-phones",
                "iphone11", null, ImeiStatus.IN_REPAIR, ImeiGrade.C_GRADE,
                "14000.00", 74, null, null, null, null,
                "Charging port being replaced.");
        imei(cat, "351122334455015", null, "SN-IP11-0015", "banjul-phones",
                "iphone11", null, ImeiStatus.WRITTEN_OFF, ImeiGrade.FOR_PARTS,
                "9000.00", 41, null, null, null, null,
                "Water damage. Board pulled for the parts lot.");
        // Reported stolen. Nothing may sell this, whatever the stock count says.
        imei(cat, "351122334455016", null, "SN-IP11-0016", "banjul-phones",
                "iphone11", null, ImeiStatus.BLOCKED, ImeiGrade.A_GRADE,
                "17800.00", 93, null, null, null, null,
                "Reported stolen to GSMA. Held pending the police report.");
        imei(cat, "352233445566011", null, "SN-A15-0011", "dakar-tech",
                "galaxya15", "a15-noir", ImeiStatus.IN_STOCK, ImeiGrade.NEW,
                "76000", 100, null, null, null, null, null);
        imei(cat, "352233445566012", null, "SN-A15-0012", "dakar-tech",
                "galaxya15", "a15-bleu", ImeiStatus.SOLD, ImeiGrade.NEW,
                "76000", 100, "SJL-1006", cat.daysAgo(17), "1006-a15", cat.daysAgo(17), null);
    }

    private void imei(SeedCatalogue cat, String imei, String imei2, String serial,
                      String vendorKey, String productKey, String variantKey,
                      ImeiStatus status, ImeiGrade grade, String costPrice, Integer battery,
                      String soldOnOrderNumber, LocalDateTime soldAt, String orderItemKey,
                      LocalDateTime assignedAt, String note) {
        cat.save(ImeiUnit.builder()
                .imei(imei).imei2(imei2).serialNumber(serial)
                .vendor(cat.vendor(vendorKey)).product(cat.product(productKey))
                .variant(variantKey == null ? null : cat.variant(variantKey))
                .status(status).grade(grade)
                .costPrice(new BigDecimal(costPrice)).batteryHealth(battery)
                .warrantyExpiresOn(LocalDate.now().plusMonths(10))
                .soldOnOrderNumber(soldOnOrderNumber).soldAt(soldAt)
                .orderItem(orderItemKey == null ? null : cat.orderItem(orderItemKey))
                .assignedAt(assignedAt)
                .note(note)
                .registeredBy(cat.user(vendorKey.equals("dakar-tech") ? "omar" : "fatou"))
                .build());
    }

    // ── Stock movements ──────────────────────────────────────────────────────

    /**
     * Why a stock figure is what it is.
     *
     * <p>Every reason the model defines appears here. The before and after
     * counts are stored rather than only the delta, because a movement that says
     * "−1" tells you nothing about whether the arithmetic was right at the time.
     */
    private void stockMovements(SeedCatalogue cat) {
        movement(cat, "banjul-phones", "spark10", "spark10-128-black",
                StockMovementReason.RESTOCK, 20, 4, 24, "PO-SUPPLIER-8841",
                "Twenty units from the Dakar distributor.", "fatou");
        movement(cat, "banjul-phones", "spark10", "spark10-128-black",
                StockMovementReason.SALE, -1, 10, 9, "SJL-1001", null, null);
        movement(cat, "banjul-phones", "spark10", "spark10-128-blue",
                StockMovementReason.SALE, -1, 8, 7, "SJL-1002", null, null);
        movement(cat, "banjul-phones", "spark10", "spark10-128-black",
                StockMovementReason.SERIALISED_UNIT, -1, 10, 9, "IMEI-350123456789015",
                "Reserved against SJL-1008 by IMEI.", "fatou");
        movement(cat, "banjul-phones", "hot30", null,
                StockMovementReason.SALE, -1, 12, 11, "SJL-1003", null, null);
        movement(cat, "banjul-phones", "iphone11", null,
                StockMovementReason.DAMAGE, -1, 5, 4, "IMEI-351122334455015",
                "Water damage found on inspection; written off.", "fatou");
        movement(cat, "banjul-phones", "iphone11", null,
                StockMovementReason.CORRECTION, 1, 3, 4, "STOCKTAKE-2024-06",
                "Stocktake found one more on the repair bench than the system had.", "fatou");
        movement(cat, "banjul-phones", "partslot", null,
                StockMovementReason.BULK_ADJUSTMENT, 6, 0, 6, "STOCKTAKE-2024-06",
                "Six boxes made up from written-off units.", "fatou");
        movement(cat, "serrekunda-home", "castironpot", null,
                StockMovementReason.SALE, -1, 19, 18, "SJL-1004", null, null);
        movement(cat, "serrekunda-home", "castironpot", null,
                StockMovementReason.RETURN, 1, 17, 18, "RET-2024-000006",
                "Wrong size sent; back on the shelf unused.", "awa");
        movement(cat, "serrekunda-home", "blender", null,
                StockMovementReason.LOSS, -2, 2, 0, "STOCKTAKE-2024-06",
                "Two missing after the shop move. Listing unpublished.", "awa");
        movement(cat, "serrekunda-home", "castironpot", null,
                StockMovementReason.TRANSFER, -3, 21, 18, "TRF-2024-0004",
                "Three moved to the Brikama stall.", "awa");
        movement(cat, "dakar-tech", "galaxya15", "a15-bleu",
                StockMovementReason.SALE, -1, 8, 7, "SJL-1006", null, null);
        movement(cat, "dakar-tech", "galaxya15", "a15-noir",
                StockMovementReason.RESTOCK, 10, 0, 10, "PO-FOURNISSEUR-2291",
                "Dix unités reçues.", "omar");
        movement(cat, "dakar-tech", "powerbank", null,
                StockMovementReason.RETURN, 1, 31, 32, "RET-2024-000007",
                "Retour défectueux; mis de côté.", "omar");
        movement(cat, "kololi-style", "waxfabric", null,
                StockMovementReason.RETURN, 1, 8, 9, "RET-2024-000008",
                "Returned after the dispute; back in stock.", "lamin");
    }

    private void movement(SeedCatalogue cat, String vendorKey, String productKey,
                          String variantKey, StockMovementReason reason, int change,
                          int before, int after, String reference, String note, String byKey) {
        cat.save(StockMovement.builder()
                .vendor(cat.vendor(vendorKey)).product(cat.product(productKey))
                .variant(variantKey == null ? null : cat.variant(variantKey))
                .reason(reason)
                .quantityChange(change).stockBefore(before).stockAfter(after)
                .reference(reference).note(note)
                .recordedBy(byKey == null ? null : cat.user(byKey))
                .build());
    }
}
