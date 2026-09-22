package com.sujula.dto.request.admin;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.sujula.model.constant.ModerationReason;
import com.sujula.model.constant.SanctionType;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** What an administrator sends when deciding who may trade and what they may list. */
public final class AdminModerationRequests {

    private AdminModerationRequests() {}

    // ── Stores ───────────────────────────────────────────────────────────────

    public record ApproveStore(@Size(max = 500) String note) {}

    public record RejectStore(
            @NotBlank(message = "A reason is required — the seller is shown it and can fix it")
            @Size(max = 1000) String reason) {}

    public record SuspendStore(
            @NotBlank(message = "A reason is required") @Size(max = 1000) String reason,
            ModerationReason category,

            /**
             * Whether the seller's money is held as well.
             *
             * <p>Defaults to true and is asked rather than assumed, because the
             * two are different judgements: a store suspended for a licence that
             * expired should still be paid for what it already delivered, while
             * one suspended for taking money and not sending goods should not.
             */
            Boolean holdPayouts) {}

    /**
     * A new commission rate, from a date.
     *
     * <p>{@code effectiveFrom} may not be in the past. Backdating would re-price
     * orders that have already been settled and paid out.
     */
    public record ChangeCommission(
            @NotNull(message = "What percentage")
            @DecimalMin(value = "0.00", message = "A rate cannot be negative")
            @DecimalMax(value = "50.00", message = "More than half is not a commission")
            BigDecimal rate,

            @NotNull(message = "From when — a commission change is never backdated")
            LocalDateTime effectiveFrom,

            @NotBlank(message = "Say why. The seller will ask, and \"an administrator changed it\" "
                    + "is not an answer")
            @Size(max = 500) String note) {}

    // ── KYC ──────────────────────────────────────────────────────────────────

    public record ApproveKyc(@Size(max = 500) String note) {}

    public record RejectKyc(
            @NotBlank(message = "Say what is wrong with it, so they can send a better one")
            @Size(max = 1000) String reason) {}

    // ── Products ─────────────────────────────────────────────────────────────

    public record ApproveProduct(@Size(max = 500) String note) {}

    public record RejectProduct(
            @NotNull(message = "Which rule it breaks") ModerationReason reason,
            @NotBlank(message = "Say what is wrong, so the seller can fix it")
            @Size(max = 1000) String detail) {}

    public record SuspendProduct(
            @NotNull(message = "Which rule it breaks") ModerationReason reason,
            @NotBlank(message = "Say what is wrong") @Size(max = 1000) String detail,

            /** Whether this also warrants looking at the seller rather than the listing. */
            Boolean openCaseAgainstSeller) {}

    /** Listing something for a seller who cannot do it themselves. */
    public record CreateProduct(
            @NotNull(message = "Which seller this belongs to") Long vendorId,
            @NotBlank(message = "A name") @Size(max = 250) String name,
            @Size(max = 4000) String description,

            @NotNull(message = "A price")
            @DecimalMin(value = "0.01", message = "A price of nothing is not a price")
            BigDecimal price,

            @NotBlank(message = "Which currency the seller is listing in")
            @Size(min = 3, max = 3) String priceCurrency,

            @Min(0) Integer stock,
            Long categoryId,

            @NotBlank(message = "Say why you are listing this on their behalf")
            @Size(max = 500) String reason) {}

    public record PatchProduct(
            @Size(max = 250) String name,
            @Size(max = 4000) String description,
            @DecimalMin(value = "0.01") BigDecimal price,
            @Min(0) Integer stock,

            @NotBlank(message = "Say why you are editing somebody else's listing")
            @Size(max = 500) String reason) {}

    // ── Reviews ──────────────────────────────────────────────────────────────

    /**
     * Deciding a reported review.
     *
     * <p>Taking one down needs a rule it broke. A seller who dislikes a review
     * is not a rule, and a marketplace that removed reviews on request would
     * have none worth reading — which costs the honest sellers most.
     */
    public record ModerateReview(
            ModerationReason reason,
            @NotBlank(message = "Say what you decided and why")
            @Size(max = 1000) String note) {}

    // ── Cases ────────────────────────────────────────────────────────────────

    /**
     * Deciding a case, and issuing whatever it warrants.
     *
     * <p>{@code sanctionType} may be absent, which is the commonest outcome:
     * most reports are answered by looking and finding nothing. Making "no
     * sanction" the default rather than a special case is what stops a queue
     * from producing punishments simply because it exists.
     */
    public record ResolveCase(
            @NotNull(message = "Upheld or dismissed") Boolean upheld,

            @NotBlank(message = "Say what you decided and why — the person is shown it")
            @Size(max = 2000) String note,

            /** Absent means no sanction, which is what most cases deserve. */
            SanctionType sanctionType,

            @Min(value = 1, message = "At least a day")
            @Max(value = 365, message = "More than a year is a ban, not a suspension")
            Integer suspensionDays,

            @Size(max = 40) String restrictedPermission) {}
}
