package com.sujula.dto.request.aftersales;

import java.math.BigDecimal;
import java.util.List;

import com.sujula.model.constant.DisputeReason;
import com.sujula.model.constant.ReturnReason;
import com.sujula.model.constant.ReviewReportReason;
import com.sujula.model.constant.ThreadSubject;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** What a buyer, a seller or a moderator sends after the sale. */
public final class AfterSalesRequests {

    private AfterSalesRequests() {}

    // ── Returns ──────────────────────────────────────────────────────────────

    /**
     * Opening a return against one seller's slice.
     *
     * <p>Takes a vendor order rather than an order, which is C3 in the request
     * shape itself: there is no way to express "return this whole payment"
     * because a payment covers several sellers who each decide separately.
     */
    public record OpenReturn(
            @NotNull(message = "Which part of the order this is about")
            Long vendorOrderId,

            @NotNull(message = "Say why it is coming back — it decides who pays the carriage")
            ReturnReason reason,

            @Size(max = 1000) String description,

            /**
             * Which items, and how many of each.
             *
             * <p>Required rather than optional-meaning-everything. A buyer who
             * ordered three cases and wants to send one back is the ordinary
             * case, and a default of "all of it" would refund two nobody
             * complained about.
             */
            @NotEmpty(message = "Say which items are coming back")
            @Size(max = 50) @Valid List<ReturnItem> items,

            /** A cracked screen is a photograph, not a sentence. */
            @Size(max = 10) List<@Size(max = 1000) String> photoUrls) {}

    public record ReturnItem(
            @NotNull(message = "Which item") Long orderItemId,
            @NotNull @Min(value = 1, message = "At least one") Integer quantity) {}

    /** The seller agreeing, with whatever they want the buyer to do next. */
    public record ApproveReturn(@Size(max = 1000) String note) {}

    /** The seller refusing, which needs a reason the buyer can read. */
    public record RejectReturn(
            @NotBlank(message = "Say why. A refusal with no reason is what starts a dispute")
            @Size(max = 1000) String reason) {}

    /**
     * Money instead of the goods coming back.
     *
     * <p>The amount is in the buyer's currency, because that is the number they
     * will see arrive. What comes off the seller is the same sum at the order's
     * own frozen rate, computed rather than sent — a seller who could name both
     * halves independently could name a rate.
     */
    public record OfferPartialRefund(
            @NotNull(message = "How much you are offering")
            @DecimalMin(value = "0.01", message = "An offer of nothing is a rejection")
            BigDecimal amount,

            @Size(max = 1000) String note) {}

    /** The seller confirming the goods are back on their counter. */
    public record ReturnReceived(
            @Size(max = 500) String note,

            /**
             * Whether what came back is what went out.
             *
             * <p>Asked rather than assumed. A seller who receives a different
             * handset has to be able to say so at the moment they open the box,
             * and a flow that only offered "received" would make the next step
             * an argument with no record behind it.
             */
            Boolean asExpected) {}

    /** The buyer taking it out of the seller's hands. */
    public record EscalateReturn(
            @NotBlank(message = "Say what is unresolved")
            @Size(max = 2000) String description) {}

    // ── Disputes ─────────────────────────────────────────────────────────────

    public record OpenDispute(
            @NotNull(message = "Which part of the order this is about")
            Long vendorOrderId,

            @NotNull(message = "What the dispute is about") DisputeReason reason,

            @NotBlank(message = "Say what happened") @Size(max = 2000) String description,

            /** What they say they are owed, in what they paid. Capped at the slice. */
            @DecimalMin(value = "0.00") BigDecimal amount,

            /** The return this grew out of, where it grew out of one. */
            Long returnRequestId) {}

    public record DisputeMessage(
            @NotBlank(message = "Say something") @Size(max = 4000) String body) {}

    public record DisputeEvidence(
            @NotBlank(message = "A file is required") @Size(max = 1000) String url,
            @Size(max = 100) String contentType,

            @NotBlank(message = "Say what it shows — a moderator otherwise has a picture of a box")
            @Size(max = 300) String caption) {}

    public record WithdrawDispute(@Size(max = 500) String reason) {}

    // ── Reviews ──────────────────────────────────────────────────────────────

    /** Changing a review inside the window. Every field optional; absent means unchanged. */
    public record EditReview(
            @Min(1) @Max(5) Integer rating,
            @Size(max = 150) String title,
            @Size(max = 4000) String comment) {}

    /** The seller's one answer. */
    public record ReplyToReview(
            @NotBlank(message = "Say something") @Size(max = 2000) String body) {}

    public record ReportReview(
            @NotNull(message = "Say which rule it breaks") ReviewReportReason reason,
            @Size(max = 1000) String detail) {}

    // ── Messages ─────────────────────────────────────────────────────────────

    /**
     * Opening a conversation, which must be about something.
     *
     * <p>Exactly one of {@code orderId} and {@code productId} is set, checked in
     * the service. There is no shape of this request that opens a channel to a
     * stranger.
     */
    public record OpenThread(
            @NotNull(message = "Say whether this is about an order or a product")
            ThreadSubject subject,

            Long orderId,
            Long productId,

            @NotBlank(message = "A subject line — the seller sees this in a list of thirty")
            @Size(max = 200) String title,

            @NotBlank(message = "Say something") @Size(max = 4000) String body) {}

    public record SendMessage(
            @NotBlank(message = "Say something") @Size(max = 4000) String body) {}
}
