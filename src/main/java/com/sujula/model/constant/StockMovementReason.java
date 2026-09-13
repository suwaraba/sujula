package com.sujula.model.constant;

/**
 * Why a stock figure changed.
 *
 * <p>The reason is the point of the ledger. A seller reconciling a shelf
 * against a screen is not asking "what is the number" — they can count that
 * themselves — they are asking why the two disagree, and the answer is always
 * one of these.
 */
public enum StockMovementReason {

    /** Stock arrived. The seller says how much. */
    RESTOCK,

    /**
     * The seller counted the shelf and the system was wrong.
     *
     * <p>Distinct from RESTOCK because it means something different: goods did
     * not arrive, a previous number was mistaken. A month of corrections and no
     * restocks is a shop with a theft problem, and a ledger that called them all
     * restocks would hide it.
     */
    CORRECTION,

    /** Sold. Written when an order reserves the goods, not when it ships. */
    SALE,

    /** An order was cancelled or refunded and the goods came back to the shelf. */
    RETURN,

    /** Broken, spoiled or otherwise gone. */
    DAMAGE,

    /** Gone, and nobody knows where. Kept apart from DAMAGE for the same reason. */
    LOSS,

    /** Moved to or from another of the seller's locations. */
    TRANSFER,

    /** Set as part of a bulk upload or an import. */
    BULK_ADJUSTMENT,

    /** A handset was registered, sold or written off, and the count followed. */
    SERIALISED_UNIT
}
