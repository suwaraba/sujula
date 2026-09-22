package com.sujula.service.money.impl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.dto.request.money.MoneyRequests;
import com.sujula.dto.response.money.MoneyResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.LedgerEntryType;
import com.sujula.model.constant.PayoutStatus;
import com.sujula.model.money.FxSnapshot;
import com.sujula.model.money.VendorLedgerEntry;
import com.sujula.model.user.Payout;
import com.sujula.model.user.Vendor;
import com.sujula.repository.money.PayoutRepository;
import com.sujula.repository.money.VendorLedgerEntryRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.money.MoneyLedger;
import com.sujula.service.money.StatementRenderer;
import com.sujula.service.money.VendorMoneyService;
import com.sujula.service.reference.CurrencyCatalogue;

import lombok.extern.slf4j.Slf4j;

/**
 * The seller's money desk.
 *
 * <p>Reads are projections of the ledger and nothing else. There is no balance
 * column anywhere for this to keep in step with, which is what makes the figure
 * on the dashboard and the rows on the statement the same fact rather than two
 * facts that agree until they do not.
 *
 * <p>The one write is a payout request, and it does not move money. It records
 * that a seller asked and commits the balance against the request so the same
 * money cannot be claimed twice; an administrator decides whether it is paid.
 */
@Slf4j
@Service
public class VendorMoneyServiceImpl implements VendorMoneyService {

    private final VendorLedgerEntryRepository entries;
    private final PayoutRepository payouts;
    private final VendorRepository vendors;
    private final MoneyLedger ledger;
    private final StatementRenderer statements;
    private final CurrencyCatalogue currencies;

    public VendorMoneyServiceImpl(VendorLedgerEntryRepository entries, PayoutRepository payouts,
                                  VendorRepository vendors, MoneyLedger ledger,
                                  StatementRenderer statements, CurrencyCatalogue currencies) {
        this.entries = entries;
        this.payouts = payouts;
        this.vendors = vendors;
        this.ledger = ledger;
        this.statements = statements;
        this.currencies = currencies;
    }

    // ── Balance ──────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public MoneyResponses.Balance balance(Long vendorUserId) {
        Vendor vendor = requireVendor(vendorUserId);
        Map<String, MoneyLedger.Balance> balances = ledger.balances(vendor.getId());

        List<MoneyResponses.CurrencyBalance> rows = new ArrayList<>();
        for (MoneyLedger.Balance balance : balances.values()) {
            rows.add(new MoneyResponses.CurrencyBalance(
                    balance.currency(), balance.available(), balance.pending(),
                    balance.inFlight(), balance.atRisk(), balance.total()));
        }

        if (rows.isEmpty()) {
            // A shop that has not sold anything has a balance of zero in its own
            // currency, which is a more useful answer than an empty list a client
            // has to special-case.
            String settlement = vendor.getSettlementCurrency();
            rows.add(new MoneyResponses.CurrencyBalance(settlement,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO));
        }

        return new MoneyResponses.Balance(rows, LocalDateTime.now(),
                rows.size() > 1
                        ? "This shop holds money in more than one currency. The balances are kept "
                          + "apart and are never added: converting them would need a rate that was "
                          + "not the one any of these orders was settled at."
                        : null);
    }

    // ── The ledger ───────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public MoneyResponses.Transactions transactions(Long vendorUserId, String currency,
                                                    LedgerEntryType type,
                                                    LocalDate from, LocalDate to,
                                                    Pageable pageable) {
        Vendor vendor = requireVendor(vendorUserId);
        Page<VendorLedgerEntry> page = entries.findLedger(
                vendor.getId(), normaliseCurrency(currency), type,
                from == null ? null : from.atStartOfDay(),
                to == null ? null : to.atStartOfDay(),
                pageable);

        List<MoneyResponses.Transaction> rows = page.getContent().stream()
                .map(VendorMoneyServiceImpl::toTransaction)
                .toList();

        return new MoneyResponses.Transactions(rows, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(),
                currency == null && ledger.balances(vendor.getId()).size() > 1
                        ? "Rows from more than one currency are interleaved here. Filter by "
                          + "currency before adding a column up."
                        : null);
    }

    private static MoneyResponses.Transaction toTransaction(VendorLedgerEntry entry) {
        FxSnapshot fx = entry.getFx();
        return new MoneyResponses.Transaction(
                entry.getId(), entry.getType(), entry.getDescription(),
                entry.getAmount(), entry.getCurrency(),
                entry.getOccurredAt(), entry.getReference(),
                entry.getVendorOrder() == null ? null : entry.getVendorOrder().getId(),
                entry.getVendorOrder() == null || entry.getVendorOrder().getOrder() == null
                        ? null : entry.getVendorOrder().getOrder().getOrderNumber(),
                entry.isHeld(), entry.getAvailableFrom(),
                fx == null || !fx.isRecorded() || fx.isIdentity() ? null
                        : new MoneyResponses.Fx(fx.getDisplayCurrency(), fx.getNativeCurrency(),
                                                fx.getRate(), fx.getRateAt()));
    }

    // ── Payouts ──────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public MoneyResponses.Payouts payouts(Long vendorUserId, PayoutStatus status, Pageable pageable) {
        Vendor vendor = requireVendor(vendorUserId);
        Page<Payout> page = payouts.findForVendor(vendor.getId(), status, pageable);

        List<MoneyResponses.PayoutRow> rows = page.getContent().stream()
                .map(payout -> new MoneyResponses.PayoutRow(
                        payout.getId(), payout.getReference(), payout.getStatus(),
                        payout.getAmount(), payout.getCurrency(), payout.getPeriod(),
                        payout.getRequestedAt(), payout.getProcessedAt(),
                        payout.getFailureReason(),
                        payout.getStatus() == PayoutStatus.REQUESTED
                                ? "Waiting for the platform to approve it."
                                : null))
                .toList();

        return new MoneyResponses.Payouts(rows, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public MoneyResponses.PayoutRequested requestPayout(Long vendorUserId,
                                                        MoneyRequests.RequestPayout request) {
        Vendor vendor = requireVendor(vendorUserId);
        String currency = request != null && request.normalisedCurrency() != null
                ? request.normalisedCurrency()
                : vendor.getSettlementCurrency();
        currencies.require(currency);

        MoneyLedger.Balance balance = ledger.balance(vendor.getId(), currency);
        BigDecimal available = balance.available();

        if (available.signum() <= 0) {
            // Not a rule somebody set — there is simply nothing to ask for. The
            // message says which of the two reasons it is, because "you have
            // money coming but not yet" and "you have none" are very different
            // things to be told.
            throw new BadRequestException(balance.pending().signum() > 0
                    ? "There is nothing available to pay out in " + currency + " yet. "
                      + balance.pending() + " " + currency + " is still held against parcels that "
                      + "have not been confirmed as delivered."
                    : "There is no " + currency + " balance to pay out.");
        }

        Payout payout = payouts.save(Payout.builder()
                .user(vendor.getUser())
                .vendor(vendor)
                .amount(available)
                .currency(currency)
                .status(PayoutStatus.REQUESTED)
                .reference(newReference())
                .requestedBy(vendor.getUser())
                .requestedAt(LocalDateTime.now())
                .notes(request == null ? null : request.note())
                .build());

        // Committed to the ledger now rather than on approval, so a second
        // request cannot claim the same money while this one is being decided.
        ledger.postPayout(vendor, payout);

        log.info("[Money] Vendor {} requested a payout of {} {} — {}",
                vendor.getId(), available, currency, payout.getReference());

        return new MoneyResponses.PayoutRequested(payout.getId(), payout.getReference(),
                payout.getStatus(), available, currency, payout.getRequestedAt(),
                "Requested. The platform reviews payouts before any money moves, so this is not "
                        + "yet a transfer. Your available balance already reflects it.");
    }

    // ── Statements ───────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN') or (hasRole('VENDOR') and #vendorUserId == authentication.principal.id)")
    public MoneyResponses.Statement statement(Long vendorUserId, String period, String currency,
                                              String format) {
        Vendor vendor = requireVendor(vendorUserId);
        YearMonth month = parsePeriod(period);
        String settled = normaliseCurrency(currency) != null
                ? normaliseCurrency(currency)
                : soleCurrencyFor(vendor, month);

        LocalDate from = month.atDay(1);
        LocalDate to = month.plusMonths(1).atDay(1);

        // The opening balance is what makes the rows checkable. Without it a
        // statement is a list of movements with nothing to anchor them against
        // what the seller was told last month.
        BigDecimal opening = orZero(entries.balanceBefore(vendor.getId(), settled, from.atStartOfDay()));
        List<VendorLedgerEntry> rows = entries.findForPeriod(
                vendor.getId(), settled, from.atStartOfDay(), to.atStartOfDay());

        BigDecimal movement = rows.stream()
                .map(VendorLedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<MoneyResponses.StatementLine> totals = new ArrayList<>();
        for (Object[] row : entries.totalsByTypeForPeriod(
                vendor.getId(), settled, from.atStartOfDay(), to.atStartOfDay())) {
            totals.add(new MoneyResponses.StatementLine(
                    (LedgerEntryType) row[0], ((Number) row[2]).longValue(), (BigDecimal) row[1]));
        }

        MoneyResponses.StatementPeriod statement = new MoneyResponses.StatementPeriod(
                month.toString(), from, to, settled,
                currencies.round(opening, settled),
                currencies.round(opening.add(movement), settled),
                totals,
                rows.stream().map(VendorMoneyServiceImpl::toTransaction).toList());

        return switch (formatOf(format)) {
            case CSV -> statements.csv(vendor, statement);
            case PDF -> statements.pdf(vendor, statement);
        };
    }

    private enum Format { PDF, CSV }

    private static Format formatOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return Format.PDF;
        }
        return switch (raw.trim().toLowerCase()) {
            case "pdf" -> Format.PDF;
            case "csv" -> Format.CSV;
            default -> throw new BadRequestException(
                    "A statement comes as pdf or csv. '" + raw + "' is neither.");
        };
    }

    private static YearMonth parsePeriod(String period) {
        if (period == null || period.isBlank()) {
            throw new BadRequestException("A period is required, as YYYY-MM — for example 2026-09.");
        }
        try {
            return YearMonth.parse(period.trim());
        } catch (DateTimeParseException malformed) {
            throw new BadRequestException(
                    "'" + period + "' is not a month. Use YYYY-MM, for example 2026-09.");
        }
    }

    /**
     * Which currency to render when the caller did not say.
     *
     * <p>One statement covers one currency, because a statement's opening and
     * closing balance are only meaningful within one. Where a shop has traded in
     * several in the month, it is asked rather than guessed — picking one would
     * silently produce a document that omits half the money.
     */
    private String soleCurrencyFor(Vendor vendor, YearMonth month) {
        List<String> traded = entries.currenciesFor(vendor.getId());
        if (traded.size() > 1) {
            throw new BadRequestException(
                    "This shop holds money in " + String.join(", ", traded)
                            + ". A statement covers one currency, because its opening and closing "
                            + "balances are only meaningful within one — say which.");
        }
        return traded.isEmpty() ? vendor.getSettlementCurrency() : traded.get(0);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String normaliseCurrency(String currency) {
        return currency == null || currency.isBlank() ? null
                : currency.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private String newReference() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String reference = "PAY-" + UUID.randomUUID().toString()
                    .replace("-", "").substring(0, 10).toUpperCase(java.util.Locale.ROOT);
            if (!payouts.existsByReference(reference)) {
                return reference;
            }
        }
        throw new IllegalStateException("Could not allocate a payout reference");
    }

    private Vendor requireVendor(Long vendorUserId) {
        return vendors.findByUserId(vendorUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Vendor for user", vendorUserId));
    }
}
