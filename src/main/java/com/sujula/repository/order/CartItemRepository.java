package com.sujula.repository.order;

import com.sujula.model.order.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    List<CartItem> findByCartId(Long cartId);

    /**
     * Locates an existing line for the same product/variant pair.
     * Spring Data renders a null {@code variantId} as {@code IS NULL}, so this
     * matches variant-less lines correctly.
     */
    Optional<CartItem> findByCartIdAndProductIdAndVariantId(Long cartId, Long productId, Long variantId);

    void deleteByCartId(Long cartId);

    // ── Hydration ─────────────────────────────────────────────────────────────
    //
    // Split across two queries on purpose: Product.images and
    // ProductVariant.selectedValues are both List-typed collections, and fetching
    // both in one query throws MultipleBagFetchException. Running them in
    // sequence loads everything into the same persistence context, so the second
    // call populates the already-managed entities without extra lazy hits.

    /** Items with product, vendor and product images attached. */
    @Query("""
           SELECT DISTINCT i FROM CartItem i
             JOIN FETCH i.product p
             JOIN FETCH i.vendor
             LEFT JOIN FETCH p.images
           WHERE i.cart.id = :cartId
           """)
    List<CartItem> findByCartIdWithProductGraph(@Param("cartId") Long cartId);

    /** Variant-bearing items with the variant and its selected option values attached. */
    @Query("""
           SELECT DISTINCT i FROM CartItem i
             JOIN FETCH i.variant v
             LEFT JOIN FETCH v.selectedValues
           WHERE i.cart.id = :cartId
           """)
    List<CartItem> findByCartIdWithVariantGraph(@Param("cartId") Long cartId);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM CartItem i WHERE i.cart.id = :cartId")
    int deleteAllByCartId(@Param("cartId") Long cartId);
}
