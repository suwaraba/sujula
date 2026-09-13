package com.sujula.repository.reference;

import com.sujula.model.reference.FxQuote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface FxQuoteRepository extends JpaRepository<FxQuote, String> {

    /**
     * A quote that can still be paid at.
     *
     * <p>Expiry is in the query, so an expired quote is indistinguishable from
     * one that never existed and no caller can forget to check. Consumption is
     * deliberately <em>not</em> in it: a client that reloads a confirmation page
     * should see its quote reported as spent rather than as missing.
     */
    @Query("SELECT q FROM FxQuote q WHERE q.id = :id AND q.expiresAt > CURRENT_TIMESTAMP")
    Optional<FxQuote> findLive(@Param("id") String id);

    /** Housekeeping. An expired quote is a bearer credential nobody should still hold. */
    @Modifying
    @Query("DELETE FROM FxQuote q WHERE q.expiresAt < :before AND q.consumedAt IS NULL")
    int deleteExpiredUnusedBefore(@Param("before") LocalDateTime before);
}
