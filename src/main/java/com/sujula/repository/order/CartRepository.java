package com.sujula.repository.order;

import com.sujula.model.order.Cart;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface CartRepository extends JpaRepository<Cart, Long> {

    Optional<Cart> findByUserId(Long userId);

    Optional<Cart> findBySessionId(String sessionId);

    /**
     * Row-level exclusive lock on the cart, taken before any mutation.
     *
     * <p>Serialising concurrent writes to a single cart is what makes
     * add/update/merge safe: without it two simultaneous "add to cart" calls can
     * both miss the existing line and race to insert duplicates. Contention is
     * negligible because a cart only ever has one owner. Must be called inside
     * an active transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Cart c WHERE c.id = :id")
    Optional<Cart> findByIdForUpdate(@Param("id") Long id);

    /**
     * Loads the cart shell plus its item rows in one round trip.
     *
     * <p>Only the {@code items} bag is fetch-joined here — the per-item Product,
     * Vendor and Variant graphs are hydrated separately by
     * {@link CartItemRepository}, because Hibernate rejects more than one
     * {@code List} fetch in a single query (MultipleBagFetchException).
     */
    @Query("SELECT c FROM Cart c LEFT JOIN FETCH c.items WHERE c.user.id = :userId")
    Optional<Cart> findByUserIdWithItems(@Param("userId") Long userId);

    @Query("SELECT c FROM Cart c LEFT JOIN FETCH c.items WHERE c.sessionId = :sessionId")
    Optional<Cart> findBySessionIdWithItems(@Param("sessionId") String sessionId);

    /**
     * Bulk-delete guest carts (no user association) whose TTL has expired.
     * The CASCADE on Cart.items handles orphan CartItem rows automatically.
     *
     * @param cutoff any cart with expiresAt before this timestamp is removed
     * @return number of carts deleted
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM Cart c WHERE c.user IS NULL AND c.expiresAt < :cutoff")
    int deleteExpiredGuestCarts(@Param("cutoff") LocalDateTime cutoff);
}
