package com.sujula.dto.response.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sujula.model.constant.ImeiGrade;
import com.sujula.model.constant.ImeiStatus;
import com.sujula.model.constant.ProductStatus;
import com.sujula.model.constant.StockMovementReason;

/** What a seller sees of their own stock. */
public final class InventoryResponses {

    private InventoryResponses() {}

    /**
     * One sellable thing, as the inventory screen lists it.
     *
     * @param version  what an absolute set has to send back. Present on every
     *                 row so a client never has to fetch twice to correct a count
     * @param serialised whether this line is tracked handset by handset, in which
     *                 case the count is derived from the units and setting it
     *                 directly is refused
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(
            Long productId,
            Long variantId,
            String productName,
            String sku,
            String variantLabel,
            ProductStatus productStatus,
            boolean live,
            int stock,
            Integer lowStockThreshold,
            boolean lowStock,
            boolean outOfStock,
            boolean allowBackorder,
            BigDecimal price,
            String currency,
            Long version,
            boolean serialised,
            Integer sellableUnits,
            LocalDateTime lastMovementAt) {}

    /**
     * @param lowStockCount how many rows are at or below their threshold, across
     *                      the whole catalogue rather than this page - the number
     *                      a seller actually wants on the screen
     */
    public record Page(
            List<Item> items,
            int page, int size, long totalElements, int totalPages,
            long lowStockCount, long outOfStockCount) {}

    /** What a stock change did. */
    public record Adjusted(
            Long variantId,
            int stockBefore,
            int stockAfter,
            Long version,
            String message) {}

    public record BulkAdjusted(
            int requested,
            int applied,
            List<Adjusted> results,
            List<LineError> errors) {}

    public record LineError(Long variantId, String message) {}

    /**
     * One row of the ledger.
     *
     * @param quantityChange signed - negative took stock away. A ledger whose
     *                       rows sum to the current figure is one anybody can
     *                       check against a shelf
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Movement(
            Long id,
            StockMovementReason reason,
            int quantityChange,
            int stockBefore,
            int stockAfter,
            String reference,
            String note,
            String recordedBy,
            LocalDateTime recordedAt) {}

    public record Movements(
            Long variantId,
            String sku,
            int currentStock,
            List<Movement> movements,
            int page, int size, long totalElements, int totalPages) {}

    // -- Handsets ------------------------------------------------------------

    /**
     * @param imei shown in full to the seller who owns it, and to nobody else.
     *             It is how a stolen handset is traced, and a leaked list of
     *             them is a shopping list
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Handset(
            Long id,
            String imei,
            String imei2,
            String serialNumber,
            Long productId,
            String productName,
            Long variantId,
            String variantLabel,
            ImeiStatus status,
            ImeiGrade grade,
            String gradeNote,
            BigDecimal costPrice,
            Integer batteryHealth,
            LocalDate warrantyExpiresOn,
            String soldOnOrderNumber,
            LocalDateTime soldAt,
            String note,
            LocalDateTime registeredAt) {}

    public record HandsetPage(
            List<Handset> items,
            int page, int size, long totalElements, int totalPages,
            long sellable) {}

    /**
     * @param rejected the ones that did not register, and why. A box of twenty
     *                 with one mistyped code should put nineteen on the shelf
     */
    public record Registered(
            int requested,
            int registered,
            List<Handset> units,
            List<LineError> rejected,
            Integer stockAfter,
            String message) {}
}
