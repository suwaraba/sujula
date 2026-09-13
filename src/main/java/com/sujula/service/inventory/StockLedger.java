package com.sujula.service.inventory;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.sujula.exceptions.BadRequestException;
import com.sujula.model.constant.StockMovementReason;
import com.sujula.model.inventory.StockMovement;
import com.sujula.model.products.Product;
import com.sujula.model.products.ProductVariant;
import com.sujula.model.user.User;
import com.sujula.repository.inventory.StockMovementRepository;
import com.sujula.repository.product.ProductRepository;
import com.sujula.repository.product.ProductVariantRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * The only thing that moves stock.
 *
 * <p>It exists for the reason the custody chain exists. A stock figure that can
 * be assigned directly is a figure nobody can explain, and the moment a seller
 * counts nine on the shelf against eleven on the screen, "the number is eleven"
 * stops being an answer. So every change is a movement with a reason, and the
 * figure is what the movements add up to.
 *
 * <p><strong>Including sales.</strong> That is the part that makes this worth
 * building rather than decorating: an audit showing a seller's own corrections
 * and silently omitting the orders that took the stock is wrong in precisely the
 * case they opened it for. So {@code OrderServiceImpl} reserves and releases
 * through here too, and the ledger is complete rather than partial.
 *
 * <p>The movement is written before the figure is saved, and both are in the
 * caller's transaction. A ledger that could commit without its consequence, or a
 * consequence without its ledger row, would be worse than neither.
 */
@Slf4j
@Component
public class StockLedger {

    private final StockMovementRepository movements;
    private final ProductVariantRepository variants;
    private final ProductRepository products;

    public StockLedger(StockMovementRepository movements, ProductVariantRepository variants,
                       ProductRepository products) {
        this.movements = movements;
        this.variants = variants;
        this.products = products;
    }

    /**
     * Moves a variant's stock by a signed amount.
     *
     * @param change negative takes stock away, positive puts it back
     * @param actor  the person responsible, or null for something the system did
     *               on its own - a sale has an order behind it rather than a
     *               person
     * @return the figure afterwards
     */
    public int adjustVariant(ProductVariant variant, int change, StockMovementReason reason,
                             String reference, String note, User actor) {

        int before = variant.getStock() == null ? 0 : variant.getStock();
        int after = requireNonNegative(before + change, variant.getSku());

        variant.setStock(after);
        variants.save(variant);
        record(variant.getProduct(), variant, change, before, after, reason, reference, note, actor);
        return after;
    }

    /** The same for a product with no variants, where the stock lives on the product. */
    public int adjustProduct(Product product, int change, StockMovementReason reason,
                             String reference, String note, User actor) {

        int before = product.getStock() == null ? 0 : product.getStock();
        int after = requireNonNegative(before + change, product.getSku());

        product.setStock(after);
        products.save(product);
        record(product, null, change, before, after, reason, reference, note, actor);
        return after;
    }

    /**
     * Sets a variant's stock to an absolute figure, recording the difference.
     *
     * <p>The movement is still signed, because a ledger whose rows sum to the
     * current number is one anybody can check. A row saying "set to 12" tells a
     * reconciliation nothing about what happened to the three that went missing;
     * "-3, CORRECTION" tells it everything.
     */
    public int setVariant(ProductVariant variant, int target, StockMovementReason reason,
                          String reference, String note, User actor) {
        int before = variant.getStock() == null ? 0 : variant.getStock();
        return adjustVariant(variant, target - before, reason, reference, note, actor);
    }

    public int setProduct(Product product, int target, StockMovementReason reason,
                          String reference, String note, User actor) {
        int before = product.getStock() == null ? 0 : product.getStock();
        return adjustProduct(product, target - before, reason, reference, note, actor);
    }

    /**
     * Writes the row.
     *
     * <p>A zero-change movement is still recorded. "Somebody counted the shelf
     * and it was right" is information, and a ledger that dropped it would make
     * a stocktake look like it never happened.
     */
    private void record(Product product, ProductVariant variant, int change,
                        int before, int after, StockMovementReason reason,
                        String reference, String note, User actor) {

        movements.save(StockMovement.builder()
                // Denormalised from the product: every read of this table is
                // "show me this seller's movements", and reaching the vendor
                // through two joins is a cost paid on the query a back office
                // runs constantly.
                .vendor(product.getVendor())
                .product(product)
                .variant(variant)
                .reason(reason)
                .quantityChange(change)
                .stockBefore(before)
                .stockAfter(after)
                .reference(truncate(reference, 60))
                .note(truncate(note, 300))
                .recordedBy(actor)
                .recordedAt(LocalDateTime.now())
                .build());
    }

    /**
     * Refuses to take a shelf below empty.
     *
     * <p>Negative stock is not a state a shop can be in, and allowing it would
     * let a double-reserve hide as a number instead of failing where it happened.
     * Backorders are expressed by the product's own flag, not by a count below
     * zero.
     */
    private static int requireNonNegative(int after, String sku) {
        if (after < 0) {
            throw new BadRequestException(
                    "That would take " + (sku == null ? "this item" : sku)
                    + " below zero. Check the figure, or record what actually happened to the "
                    + "missing units.");
        }
        return after;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
