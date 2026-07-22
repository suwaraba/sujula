package com.sujula.repository.order;

import com.sujula.model.Cart;
import org.springframework.data.jpa.repository.JpaRepository;
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
