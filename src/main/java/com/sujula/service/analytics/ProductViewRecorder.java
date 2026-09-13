package com.sujula.service.analytics;

import java.time.LocalDate;
import java.time.ZoneOffset;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.model.analytics.ProductViewStat;
import com.sujula.repository.analytics.ProductViewStatRepository;
import com.sujula.repository.product.ProductRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Counts a look at a listing.
 *
 * <p>Three things make this safe to call on the hottest read on the platform.
 *
 * <p><strong>The database does the addition.</strong> A read-modify-write would
 * lose views under any concurrency at all, and this is the most concurrent write
 * here by a wide margin.
 *
 * <p><strong>It has its own transaction.</strong> {@code REQUIRES_NEW}, so a
 * failed count cannot roll back the product page that triggered it. A shopper
 * being shown an error because a statistic could not be filed would be absurd.
 *
 * <p><strong>It never throws.</strong> A view counter is not worth a failed
 * request, so everything is caught and logged. The number being slightly low is
 * a far better outcome than a page that does not load.
 */
@Slf4j
@Component
public class ProductViewRecorder {

    private final ProductViewStatRepository stats;
    private final ProductRepository products;

    public ProductViewRecorder(ProductViewStatRepository stats, ProductRepository products) {
        this.stats = stats;
        this.products = products;
    }

    /** Adds one to today's count for a product. Never throws. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long productId) {
        if (productId == null) {
            return;
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        try {
            if (stats.increment(productId, today) > 0) {
                return;
            }
            // No row for today yet. Create it at one - the view that created the
            // row is itself a view, and starting at zero would lose one a day
            // per product.
            products.findById(productId).ifPresent(product -> stats.save(ProductViewStat.builder()
                    .product(product)
                    .vendor(product.getVendor())
                    .viewedOn(today)
                    .views(1L)
                    .build()));
        } catch (DataIntegrityViolationException raced) {
            // Two first views of the day at once; the unique constraint let one
            // insert. Add to the winner's row rather than failing.
            try {
                stats.increment(productId, today);
            } catch (RuntimeException stillFailing) {
                log.debug("[Views] Could not count a view of product {}: {}",
                        productId, stillFailing.toString());
            }
        } catch (RuntimeException failed) {
            // A statistic is never worth a failed page load.
            log.debug("[Views] Could not count a view of product {}: {}", productId, failed.toString());
        }
    }
}
