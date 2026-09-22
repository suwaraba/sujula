package com.sujula.dto.request.admin;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.sujula.model.constant.ReportType;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * What an administrator sends when they move money.
 *
 * <p>Two rules run through all of it. A refund names a <em>vendor order</em>,
 * never an order and never a percentage, because a refund is one seller's
 * sub-order coming back and a proportion of a multi-vendor payment is a figure
 * that belongs to nobody (C3). And every amount names its currency, because on
 * this platform the buyer paid in one and the seller settles in another, and an
 * amount without a currency is an instruction somebody will carry out in the
 * wrong one (C2).
 */
public final class AdminMoneyRequests {

    private AdminMoneyRequests() {}

    // ── Refunds ──────────────────────────────────────────────────────────────

    /**
     * Giving a buyer their money back for one seller's part of an order.
     *
     * <p>Amounts are optional: leave both null for the whole sub-order, which is
     * the ordinary case and the one least likely to be typed wrong.
     */
    public record Refund(
            @NotNull(message = "Which seller's part of the order — a refund is never a proportion")
            Long vendorOrderId,

            /**
             * In the buyer's currency — what actually goes back to their card.
             *
             * <p>Null with {@link #amountNative} null means the whole sub-order.
             * Sending only one of the two is refused: the pair has to be
             * consistent at the order's own frozen rate, and deriving the missing
             * half here would re-convert at today's rate and charge the platform
             * or the seller the difference.
             */
            @DecimalMin(value = "0.01", message = "A refund of nothing is not a refund")
            BigDecimal amount,

            /** In the seller's currency — what comes off their balance. */
            @DecimalMin(value = "0.01") BigDecimal amountNative,

            @NotBlank(message = "Say why — the buyer and the seller both see this")
            @Size(max = 500) String reason,

            /** Required above the step-up threshold; ignored below it. */
            String password,
            String totpCode) {

        public boolean isFullRefund() {
            return amount == null && amountNative == null;
        }
    }

    // ── Payout batches ───────────────────────────────────────────────────────

    public record PrepareBatch(
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$", message = "Three-letter currency code")
            String currency,

            /**
             * Only pay out balances of at least this much.
             *
             * <p>A transfer costs the platform a fee whatever it carries, and
             * sending somebody forty dalasi costs more than it moves. Null uses
             * the platform's own floor.
             */
            @DecimalMin("0.00") BigDecimal minimumAmount,

            /** Narrow the run to named sellers; empty or null takes everyone eligible. */
            List<Long> vendorIds,

            @Size(max = 500) String note,

            @NotBlank(message = "Your password — money leaving cannot be undone")
            String password,
            String totpCode) {}

    public record ApproveBatch(
            @Size(max = 500) String note,

            @NotBlank(message = "Your password — this releases the transfers")
            String password,
            String totpCode) {}

    public record CancelBatch(
            @NotBlank(message = "Say why") @Size(max = 500) String reason) {}

    public record RetryPayoutItem(
            @NotBlank(message = "Say why this is being tried again") @Size(max = 500)
            String reason) {}

    // ── FX ───────────────────────────────────────────────────────────────────

    public record RefreshRates(
            /** Which currencies to pull. Null or empty refreshes every pair in use. */
            List<@Pattern(regexp = "^[A-Za-z]{3}$") String> currencies,

            @Size(max = 300) String note) {}

    public record SetSpread(
            /** Null is a wildcard — "whatever we are converting out of". */
            @Pattern(regexp = "^[A-Za-z]{3}$") String fromCurrency,

            @Pattern(regexp = "^[A-Za-z]{3}$") String toCurrency,

            /**
             * Basis points. 150 is 1.5%.
             *
             * <p>Capped well below anything defensible, because the failure mode
             * is somebody typing 150 meaning 1.5% into a field that wanted a
             * percentage — or the reverse — and a spread of 150% would be charged
             * to real buyers before anybody noticed.
             */
            @NotNull @Min(value = 0, message = "A negative spread pays buyers to convert")
            @Max(value = 2000, message = "Above 20% — check whether you mean basis points")
            Integer basisPoints,

            /**
             * When it starts. Null is now; never in the past.
             *
             * <p>Orders already converted carry the rate and the moment they were
             * converted at. A backdated spread would say they were charged
             * something they were not.
             */
            LocalDateTime effectiveFrom,

            @NotBlank(message = "Say why — this is the buyer's money")
            @Size(max = 500) String reason) {}

    // ── Reports ──────────────────────────────────────────────────────────────

    public record RequestExport(
            @NotNull ReportType type,

            LocalDate fromDate,
            LocalDate toDate,

            /** Null gives one section per currency rather than one meaningless total. */
            @Pattern(regexp = "^[A-Za-z]{3}$") String currency,

            Long vendorId,

            @Pattern(regexp = "^(CSV|JSON)$", message = "CSV or JSON") String format) {}
}
