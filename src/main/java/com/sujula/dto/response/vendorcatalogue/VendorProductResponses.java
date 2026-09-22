package com.sujula.dto.response.vendorcatalogue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sujula.model.constant.CatalogueJobStatus;
import com.sujula.model.constant.CatalogueJobType;
import com.sujula.model.constant.DeliveryScope;
import com.sujula.model.constant.MediaStatus;
import com.sujula.model.constant.ProductCondition;
import com.sujula.model.constant.ProductStatus;

/**
 * What a seller sees of their own listings.
 *
 * <p>Records rather than entities. A {@code Product} reaches its {@code Vendor},
 * which reaches its {@code User} - password hash, TOTP secret - and its
 * {@code BankAccount} list, which after the converter runs holds a decrypted
 * account number. Serialising the entity would hand a client all of it.
 */
public final class VendorProductResponses {

    private VendorProductResponses() {}

    /** One row in the seller's list. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Summary(
            Long id,
            String name,
            String slug,
            String sku,
            ProductStatus status,
            /** Whether a buyer can see it today. Follows from the status, never set. */
            boolean live,
            BigDecimal price,
            /** The vendor's own currency, which is also what they are paid in. */
            String currency,
            Integer stock,
            boolean lowStock,
            String leadImageUrl,
            int variantCount,
            int imageCount,
            String blockedReason,
            LocalDateTime updatedAt) {}

    /**
     * @param counts how many listings sit in each status, so the back office can
     *               draw its tabs without a second round trip
     */
    public record Page(
            List<Summary> items,
            int page, int size, long totalElements, int totalPages,
            Map<ProductStatus, Long> counts) {}

    /**
     * The full listing.
     *
     * @param moderation where it stands with us, and what to do about it
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Detail(
            Long id,
            String name,
            String slug,
            String shortDescription,
            String description,

            ProductStatus status,
            boolean live,
            Moderation moderation,

            BigDecimal price,
            BigDecimal compareAtPrice,
            String currency,

            String sku,
            Integer stock,
            Integer lowStockThreshold,
            boolean allowBackorder,

            Long categoryId,
            String categoryName,
            Long brandId,
            String brandName,
            ProductCondition condition,
            DeliveryScope deliveryScope,

            Double weightKg,
            String dimensions,
            String country,

            List<Media> media,
            List<Variant> variants,
            List<OptionSummary> options,
            List<TranslationSummary> translations,

            Integer totalSold,
            BigDecimal rating,
            Integer totalReviews,

            LocalDateTime createdAt,
            LocalDateTime updatedAt) {}

    /**
     * Where the listing stands with moderation.
     *
     * @param editsNeedReview whether the changes made since approval will send
     *                        it back. Answered before the seller publishes
     *                        rather than after, so nothing goes offline as a
     *                        surprise
     * @param reason          why it was refused, if it was. Kept after a later
     *                        approval: a seller arguing about a week's delay
     *                        needs the history
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Moderation(
            boolean editable,
            boolean canSubmit,
            boolean canPublish,
            boolean editsNeedReview,
            String reason,
            LocalDateTime submittedAt,
            LocalDateTime reviewedAt,
            LocalDateTime publishedAt,
            LocalDateTime unpublishedAt,
            LocalDateTime archivedAt) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Media(
            Long id,
            String url,
            String altText,
            int sortOrder,
            boolean isDefault,
            MediaStatus status,
            String originalFilename,
            Long sizeBytes) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Variant(
            Long id,
            String sku,
            Integer stock,
            BigDecimal priceOverride,
            /** What this variant actually costs, override applied. */
            BigDecimal effectivePrice,
            boolean active,
            List<VariantValue> values) {}

    public record VariantValue(Long optionValueId, String option, String value) {}

    public record OptionSummary(Long id, String code, String name, List<VariantValue> values) {}

    public record TranslationSummary(
            String locale, String name, boolean machineTranslated, LocalDateTime updatedAt) {}

    /** The whole translation, for the editor. */
    public record Translation(
            String locale,
            String name,
            String shortDescription,
            String description,
            boolean machineTranslated,
            LocalDateTime updatedAt) {}

    // -- Acknowledgements ----------------------------------------------------

    /**
     * @param needsReview whether this change sent the listing back to the queue,
     *                    and {@code reason} why. A seller whose product silently
     *                    left the catalogue after a price edit will believe the
     *                    platform broke
     */
    public record Saved(
            Long id,
            ProductStatus status,
            boolean live,
            boolean needsReview,
            String message) {}

    /**
     * @param deleted true when the listing was genuinely removed, which happens
     *                only if nobody ever ordered it. Otherwise it is archived,
     *                because an order line points at it and a buyer's receipt
     *                has to keep resolving
     */
    public record Removed(Long id, boolean deleted, ProductStatus status, String message) {}

    // -- Bulk ----------------------------------------------------------------

    /**
     * A bulk job, as the seller polls it.
     *
     * @param reference what they poll with. Not the row id: a job id anyone can
     *                  count through would hand out other sellers' import
     *                  errors, which name their products, prices and SKUs
     * @param errors    the reason this feature is worth building. "Failed" has
     *                  wasted their evening; "row 37: category 'Phonez' does not
     *                  exist" has found it for them
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Job(
            String reference,
            CatalogueJobType type,
            CatalogueJobStatus status,
            String originalFilename,
            String format,
            int totalRows,
            int succeededRows,
            int failedRows,
            String failureReason,
            List<RowError> errors,
            int errorsShown,
            long errorsTotal,
            String downloadUrl,
            LocalDateTime downloadExpiresAt,
            LocalDateTime createdAt,
            LocalDateTime startedAt,
            LocalDateTime finishedAt) {}

    /** @param row as numbered in the seller's own file, header included */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RowError(int row, String field, String message, String value) {}

    /** The columns an import understands, so a client can offer a template. */
    public record ImportTemplate(List<String> requiredColumns, List<String> optionalColumns) {}
}
