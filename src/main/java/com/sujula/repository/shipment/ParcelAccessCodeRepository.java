package com.sujula.repository.shipment;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sujula.model.shipment.ParcelAccessCode;

@Repository
public interface ParcelAccessCodeRepository extends JpaRepository<ParcelAccessCode, Long> {

    /**
     * The code being presented, matched against the live one only.
     *
     * <p>Expiry, invalidation and the attempt cap are all in the query rather
     * than checked afterwards. A check after the fact is the one that ships
     * missing, and what it would let through here is a stranger giving somebody
     * else's parcel a new destination.
     */
    @Query("SELECT c FROM ParcelAccessCode c WHERE c.shipment.id = :shipmentId "
         + "AND c.code = :code AND c.invalidatedAt IS NULL AND c.expiresAt > :now "
         + "AND c.failedAttempts < :maxAttempts")
    Optional<ParcelAccessCode> findPresented(@Param("shipmentId") Long shipmentId,
                                             @Param("code") String code,
                                             @Param("now") LocalDateTime now,
                                             @Param("maxAttempts") int maxAttempts);

    /** Every code that would still be accepted for this parcel. Usually one. */
    @Query("SELECT c FROM ParcelAccessCode c WHERE c.shipment.id = :shipmentId "
         + "AND c.invalidatedAt IS NULL AND c.expiresAt > :now ORDER BY c.createdAt DESC")
    List<ParcelAccessCode> findLive(@Param("shipmentId") Long shipmentId,
                                    @Param("now") LocalDateTime now);

    /**
     * Codes that were killed by wrong guesses and have not expired yet.
     *
     * <p>Only read to tell somebody why their code stopped working. A burned
     * code and a code that was never sent both fail to match, and "that has
     * expired" sent to somebody who has just mistyped five times tells them to
     * wait when what they should do is ask for another.
     */
    @Query("SELECT c FROM ParcelAccessCode c WHERE c.shipment.id = :shipmentId "
         + "AND c.invalidatedAt IS NOT NULL AND c.failedAttempts >= :maxAttempts "
         + "AND c.expiresAt > :now ORDER BY c.invalidatedAt DESC")
    List<ParcelAccessCode> findBurned(@Param("shipmentId") Long shipmentId,
                                      @Param("maxAttempts") int maxAttempts,
                                      @Param("now") LocalDateTime now);

    /**
     * How many have been asked for lately.
     *
     * <p>Counts rows rather than a counter, so nothing that forgets to increment
     * can reset the limit — and so nobody can have the buyer emailed forty times
     * by hammering a tracking code they found.
     */
    @Query("SELECT COUNT(c) FROM ParcelAccessCode c WHERE c.shipment.id = :shipmentId "
         + "AND c.createdAt > :since")
    long countSince(@Param("shipmentId") Long shipmentId, @Param("since") LocalDateTime since);
}
