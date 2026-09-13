package com.sujula.dto.request.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.sujula.model.constant.ImeiGrade;
import com.sujula.model.constant.ImeiStatus;
import com.sujula.model.constant.StockMovementReason;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** What a seller sends about their own stock. */
public final class InventoryRequests {

    private InventoryRequests() {}

    /**
     * Changing one item's stock, either way round.
     *
     * <p>The two forms are not equivalent and the difference is concurrency. A
     * delta is safe whoever else is writing: {@code +5} is {@code +5}. An
     * absolute figure is not - two people counting the same shelf and saving 10
     * and 12 leaves whichever committed last, and the other is simply wrong with
     * nothing to say so. So an absolute set has to carry the version it was read
     * at, and a stale one is refused.
     *
     * @param setTo   the figure after, when the seller counted the shelf
     * @param delta   the change, when they know what moved
     * @param version required with {@code setTo}, ignored with {@code delta}
     */
    public record AdjustStock(
            Integer setTo,
            Integer delta,
            Long version,
            @NotNull StockMovementReason reason,
            @Size(max = 60) String reference,
            @Size(max = 300) String note) {

        /** Exactly one of the two forms, which the service enforces. */
        public boolean isAbsolute() {
            return setTo != null;
        }
    }

    /**
     * Several at once.
     *
     * <p>Deltas only, deliberately. A bulk absolute set is a bulk overwrite of
     * whatever else happened while the spreadsheet was open, and there is no
     * version to check per row that a seller would ever have.
     */
    public record BulkAdjust(
            @NotEmpty @Valid List<Line> lines,
            @NotNull StockMovementReason reason,
            @Size(max = 60) String reference,
            @Size(max = 300) String note) {}

    public record Line(
            @NotNull Long variantId,
            @NotNull Integer delta) {}

    // -- Handsets ------------------------------------------------------------

    /**
     * Registering physical handsets against a variant.
     *
     * <p>A batch, because a seller unpacking a box registers twenty at a time
     * and one request per phone over a mobile connection is twenty chances to
     * lose the thread.
     */
    public record RegisterImeiUnits(
            @NotNull Long productId,
            Long variantId,
            @NotEmpty @Valid List<ImeiUnitEntry> units) {}

    public record ImeiUnitEntry(
            /** Fifteen digits. Checked against its own Luhn digit, not just its length. */
            @NotBlank @Size(min = 14, max = 17) String imei,
            @Size(max = 17) String imei2,
            @Size(max = 40) String serialNumber,
            ImeiGrade grade,
            @Size(max = 300) String gradeNote,
            @DecimalMin("0.00") BigDecimal costPrice,
            @Min(0) @Max(100) Integer batteryHealth,
            LocalDate warrantyExpiresOn,
            @Size(max = 300) String note) {}

    /** Re-grading a handset, or moving it between states. */
    public record UpdateImeiUnit(
            ImeiGrade grade,
            @Size(max = 300) String gradeNote,
            ImeiStatus status,
            @Min(0) @Max(100) Integer batteryHealth,
            LocalDate warrantyExpiresOn,
            @Size(max = 300) String note) {}
}
