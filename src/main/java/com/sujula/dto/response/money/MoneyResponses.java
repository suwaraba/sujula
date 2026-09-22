package com.sujula.dto.response.money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.sujula.model.constant.LedgerEntryType;
import com.sujula.model.constant.PayoutStatus;

/**
 * What a seller is told about their money.
 *
 * <p>Every figure carries its currency and none of them is a total across two.
 * A balance is a list, not a number — one entry per currency the shop has traded
 * in — which makes the single-currency case one entry long and the
 * multi-currency case impossible to get wrong (C2).
 */
public final class MoneyResponses {

    private MoneyResponses() {}

    /** Everything a seller is owed, one entry per currency. */
    public record Balance(List<CurrencyBalance> byCurrency, LocalDateTime asAt, String note) {}

    /**
     * One currency's position.
     *
     * @param available money posted, out of escrow and not committed to an open
     *                  transfer — what could be paid today
     * @param pending   earned, but held against parcels that have not arrived.
     *                  This is escrow, which is the whole reason a buyer in
     *                  Madrid will send money for goods they cannot inspect
     * @param onHold    already committed to a transfer that has not settled.
     *                  Shown rather than subtracted again: the payout entry has
     *                  already taken it out of {@code available}
     * @param atRisk    sales with a refund still being decided. Not withheld —
     *                  nothing has been decided — but visible, so an approval is
     *                  not a surprise
     */
    public record CurrencyBalance(String currency, BigDecimal available, BigDecimal pending,
                                  BigDecimal onHold, BigDecimal atRisk, BigDecimal total) {}

    /** The ledger, as a seller reads it. */
    public record Transactions(List<Transaction> entries, int page, int size,
                               long totalEntries, int totalPages, String note) {}

    /**
     * One movement.
     *
     * <p>Signed: positive came in, negative went out. A seller adding this
     * column up gets their balance, which is the only way to make a balance
     * checkable.
     */
    public record Transaction(Long id, LedgerEntryType type, String description,
                              BigDecimal amount, String currency,
                              LocalDateTime occurredAt, String reference,
                              Long vendorOrderId, String orderNumber,
                              boolean heldInEscrow, LocalDateTime availableFrom,
                              Fx fx) {}

    /** What the buyer paid and the rate it became this at. */
    public record Fx(String paidIn, String settledIn, BigDecimal rate, LocalDateTime rateAt) {}

    // ── Payouts ──────────────────────────────────────────────────────────────

    public record Payouts(List<PayoutRow> payouts, int page, int size,
                          long totalPayouts, int totalPages) {}

    public record PayoutRow(Long id, String reference, PayoutStatus status,
                            BigDecimal amount, String currency,
                            String period, LocalDateTime requestedAt, LocalDateTime processedAt,
                            String failureReason, String note) {}

    /** What a request produced. */
    public record PayoutRequested(Long id, String reference, PayoutStatus status,
                                  BigDecimal amount, String currency,
                                  LocalDateTime requestedAt, String message) {}

    // ── Statements ───────────────────────────────────────────────────────────

    /**
     * A rendered statement, ready to stream.
     *
     * <p>Built from the ledger for a calendar month, with an opening and closing
     * balance so the rows in between can be checked against what the seller was
     * told last month. Without those two figures a statement is a list of
     * movements with nothing to anchor them.
     */
    public record Statement(byte[] content, String filename, String contentType) {}

    /** One month, one currency, as the statement document lays it out. */
    public record StatementPeriod(String period, LocalDate from, LocalDate to, String currency,
                                  BigDecimal openingBalance, BigDecimal closingBalance,
                                  List<StatementLine> totals, List<Transaction> entries) {}

    public record StatementLine(LedgerEntryType type, long count, BigDecimal total) {}
}
