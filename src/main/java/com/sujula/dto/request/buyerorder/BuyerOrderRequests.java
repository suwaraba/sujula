package com.sujula.dto.request.buyerorder;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** What a buyer sends about their own orders. */
public final class BuyerOrderRequests {

    private BuyerOrderRequests() {}

    /**
     * @param reason optional, and worth asking for: the commonest cancellation
     *               reasons are things the platform could fix
     */
    public record Cancel(@Size(max = 300) String reason) {}

    public record ConfirmReceipt(@Size(max = 300) String note) {}

    /**
     * A review of something they bought.
     *
     * @param rating one to five. Not optional — a review with no rating cannot
     *               be aggregated, and the aggregate is what other buyers read
     */
    public record PostReview(
            @NotNull @Min(1) @Max(5) Integer rating,
            @Size(max = 150) String title,
            @Size(max = 2000) String comment) {}
}
