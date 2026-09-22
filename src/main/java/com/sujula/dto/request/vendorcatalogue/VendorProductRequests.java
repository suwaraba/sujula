package com.sujula.dto.request.vendorcatalogue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.sujula.model.constant.DeliveryScope;
import com.sujula.model.constant.ProductCondition;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** What a seller sends about their own listings. */
public final class VendorProductRequests {

    private VendorProductRequests() {}

    /**
     * A new listing. Created as a DRAFT whatever is in here.
     *
     * <p>There is deliberately no status field. A seller who could name the
     * status on the way in could name PUBLISHED, and moderation would be
     * something you opt into.
     *
     * <p>Nor is there a currency. The price is in the vendor's own settlement
     * currency - listing currency and payout currency are the same thing by
     * construction, and letting a seller list in EUR while being paid in GMD
     * would put an FX conversion between what a buyer sees and what the seller
     * is owed that nobody has snapshotted.
     */
    public record CreateProduct(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 500) String shortDescription,
            @Size(max = 20000) String description,

            @NotNull @DecimalMin("0.00") BigDecimal price,
            @DecimalMin("0.00") BigDecimal compareAtPrice,

            @Size(max = 60) String sku,
            @Min(0) Integer stock,
            @Min(0) Integer lowStockThreshold,
            Boolean allowBackorder,

            Long categoryId,
            Long brandId,
            ProductCondition condition,
            DeliveryScope deliveryScope,

            /** Weight decides half of what delivery costs, so it is worth asking for. */
            @DecimalMin("0.0") Double weightKg,
            @Size(max = 60) String dimensions,
            @Size(max = 2) String country) {}

    /**
     * Editing a listing. Absent fields are left alone.
     *
     * <p>Some of these send the listing back for review and some do not - see
     * {@code ProductLifecycle.contentChanged}. The response says which happened
     * rather than leaving the seller to notice their product went offline.
     */
    public record UpdateProduct(
            @Size(max = 200) String name,
            @Size(max = 500) String shortDescription,
            @Size(max = 20000) String description,

            @DecimalMin("0.00") BigDecimal price,
            @DecimalMin("0.00") BigDecimal compareAtPrice,

            @Size(max = 60) String sku,
            @Min(0) Integer stock,
            @Min(0) Integer lowStockThreshold,
            Boolean allowBackorder,

            Long categoryId,
            Long brandId,
            ProductCondition condition,
            DeliveryScope deliveryScope,

            @DecimalMin("0.0") Double weightKg,
            @Size(max = 60) String dimensions,
            @Size(max = 2) String country) {}

    /** Sending it to be looked at. */
    public record SubmitForReview(@Size(max = 500) String note) {}

    // -- Variants ------------------------------------------------------------

    /**
     * @param optionValueIds one value per option the product defines. A variant
     *                       that names two colours, or no colour, is not a
     *                       variant of anything
     */
    public record CreateVariant(
            @Size(max = 60) String sku,
            @Min(0) Integer stock,
            @DecimalMin("0.00") BigDecimal priceOverride,
            @NotEmpty List<Long> optionValueIds) {}

    public record UpdateVariant(
            @Size(max = 60) String sku,
            @Min(0) Integer stock,
            @DecimalMin("0.00") BigDecimal priceOverride,
            Boolean active,
            List<Long> optionValueIds) {}

    // -- Media ---------------------------------------------------------------

    /**
     * Confirms a file that is already in storage.
     *
     * <p>The bytes never pass through this API. The client presigns an upload,
     * puts the file straight into object storage and sends the key here - an
     * image travelling through an application server is an image in three
     * access logs and a heap dump.
     */
    public record ConfirmMedia(
            @NotBlank @Size(max = 500) String fileUrl,
            @Size(max = 255) String originalFilename,
            @Size(max = 100) String contentType,
            @Min(1) Long sizeBytes,
            @Size(max = 200) String altText,
            Boolean makeDefault) {}

    /** Every image id, exactly once, in the order they should appear. */
    public record ReorderMedia(@NotEmpty List<Long> mediaIdsInOrder) {}

    // -- Translations --------------------------------------------------------

    /**
     * @param machineTranslated whether a machine wrote this. Worth recording and
     *                          worth showing: a buyer spending a month's
     *                          remittance on a description they cannot verify
     *                          deserves to know which kind of text it is
     */
    public record PutTranslation(
            @Size(max = 200) String name,
            @Size(max = 500) String shortDescription,
            @Size(max = 20000) String description,
            Boolean machineTranslated) {}

    // -- Bulk ----------------------------------------------------------------

    /**
     * A spreadsheet already in storage, to be read into draft listings.
     *
     * @param fileUrl the storage key, for the same reason as media
     * @param format  csv or xlsx. Checked against the file's own bytes rather
     *                than trusted, because a filename is a claim
     */
    public record BulkImport(
            @NotBlank @Size(max = 500) String fileUrl,
            @Size(max = 255) String originalFilename,
            @Size(max = 10) String format,
            /** Update listings whose SKU already exists rather than refusing them. */
            Boolean updateExisting) {}

    /** Column headings a seller's own spreadsheet uses, mapped onto ours. */
    public record ColumnMapping(@NotNull Map<String, String> columns) {}
}
