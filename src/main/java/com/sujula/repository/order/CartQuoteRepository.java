package com.sujula.repository.order;

import com.sujula.model.order.CartQuote;
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
public interface CartQuoteRepository extends JpaRepository<CartQuote, String> {

    /**
     * A quote that can still be paid at, with its lines.
     *
     * <p>Expiry is in the query so an expired quote is indistinguishable from one
     * that never existed, and the lines are fetched because checkout needs every
     * one of them — a quote loaded without them is twenty more queries at the
     * moment that matters most.
     *
     * <p>Consumption is deliberately not filtered here: a client polling after
     * checkout should see its quote reported as spent rather than as missing.
     */
    @Query("SELECT DISTINCT q FROM CartQuote q LEFT JOIN FETCH q.lines "
         + "WHERE q.id = :id AND q.expiresAt > CURRENT_TIMESTAMP")
    Optional<CartQuote> findLive(@Param("id") String id);

    /**
     * Claims the quote row before checkout observes any of its spendable state.
     *
     * <p>Different idempotency keys intentionally have independent attempts,
     * so their only common serialization point is the quote itself. Expiry is
     * deliberately checked by the caller after it owns the lock: checkout must
     * not decide whether a quote is spendable before it has serialized against
     * another checkout for that same quote.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT q FROM CartQuote q WHERE q.id = :id")
    Optional<CartQuote> findByIdForUpdate(@Param("id") String id);

    /** Housekeeping. An unspent expired quote is of no further use to anyone. */
    @Modifying
    @Query("DELETE FROM CartQuote q WHERE q.expiresAt < :before AND q.consumedAt IS NULL")
    int deleteExpiredUnusedBefore(@Param("before") LocalDateTime before);
}
