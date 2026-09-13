package com.sujula.service.money;

import com.sujula.dto.request.money.MoneyRequests;
import com.sujula.dto.response.money.MoneyResponses;
import com.sujula.model.constant.LedgerEntryType;

import org.springframework.data.domain.Pageable;

/**
 * A seller's balance, their ledger, and getting paid.
 *
 * <p>Nothing here computes a balance of its own. Every figure comes from
 * {@link MoneyLedger}, which sums the rows, so the number on this surface and
 * the rows behind it cannot disagree — there is no second place keeping score.
 *
 * <p>Balances are per currency throughout. A shop that has traded in dalasi and
 * CFA has two, and this interface has no method that would return one number
 * for both (C2).
 */
public interface VendorMoneyService {

    /** Available, pending, on hold and at risk — one entry per currency. */
    MoneyResponses.Balance balance(Long vendorUserId);

    /** The ledger view: sales, commission, refunds and payouts, newest first. */
    MoneyResponses.Transactions transactions(Long vendorUserId, String currency,
                                             LedgerEntryType type,
                                             java.time.LocalDate from, java.time.LocalDate to,
                                             Pageable pageable);

    /** Past and in-flight transfers. */
    MoneyResponses.Payouts payouts(Long vendorUserId, com.sujula.model.constant.PayoutStatus status,
                                   Pageable pageable);

    /**
     * Asks to be paid whatever is available in one currency.
     *
     * <p>Creates a REQUESTED payout and commits the money to it in the ledger
     * straight away, so a second request cannot claim the same balance while the
     * first is being decided. Nothing is transferred: an administrator decides.
     */
    MoneyResponses.PayoutRequested requestPayout(Long vendorUserId,
                                                 MoneyRequests.RequestPayout request);

    /**
     * A month's statement, as a PDF or a CSV.
     *
     * @param period {@code YYYY-MM}
     * @param format {@code pdf} or {@code csv}
     */
    MoneyResponses.Statement statement(Long vendorUserId, String period, String currency,
                                       String format);
}
