package com.sujula.service.inventory;

import org.springframework.data.domain.Pageable;

import com.sujula.dto.request.inventory.InventoryRequests;
import com.sujula.dto.response.inventory.InventoryResponses;
import com.sujula.model.constant.ImeiStatus;

/**
 * A seller's stock: what they have, what changed it, and which handsets.
 *
 * <p>Every method resolves through the vendor, so no argument reaches another
 * seller's shelf.
 *
 * <p>Two ideas run through it. <strong>The movement is the record.</strong>
 * Nothing here sets a number without saying why, and the audit includes sales
 * as well as corrections, because an audit that omitted the orders would be
 * wrong in the case a seller opens it for. <strong>A serialised line counts
 * itself.</strong> Where handsets are tracked individually, the stock figure is
 * how many are in stock rather than a second opinion about the same shelf.
 */
public interface InventoryService {

    /** Everything sellable, newest movement first, with a low-stock filter. */
    InventoryResponses.Page list(Long userId, boolean lowStockOnly, boolean outOfStockOnly,
                                 String search, Pageable pageable);

    /**
     * Changes one item's stock.
     *
     * <p>Absolute or by delta, and the two are not equivalent: an absolute
     * figure has to carry the version it was read at, because two people
     * counting the same shelf would otherwise silently overwrite each other.
     */
    InventoryResponses.Adjusted adjust(Long userId, Long variantId,
                                       InventoryRequests.AdjustStock request);

    /**
     * Several at once, by delta only.
     *
     * <p>A bad line fails alone. A seller correcting forty counts should not
     * lose thirty-nine of them to one typo.
     */
    InventoryResponses.BulkAdjusted bulkAdjust(Long userId, InventoryRequests.BulkAdjust request);

    /** What happened to this item's stock, and why. */
    InventoryResponses.Movements movements(Long userId, Long variantId, Pageable pageable);

    // -- Handsets ------------------------------------------------------------

    InventoryResponses.HandsetPage handsets(Long userId, Long variantId, ImeiStatus status,
                                            Pageable pageable);

    /**
     * Registers physical handsets against a variant.
     *
     * <p>The IMEI is checked against its own Luhn digit and against the whole
     * platform, not just this shop: the same handset on two shelves is a phone
     * somebody has sold twice.
     */
    InventoryResponses.Registered registerHandsets(Long userId,
                                                   InventoryRequests.RegisterImeiUnits request);

    /** Re-grades a handset or moves it between states, and re-counts the shelf. */
    InventoryResponses.Handset updateHandset(Long userId, Long unitId,
                                             InventoryRequests.UpdateImeiUnit request);
}
