package com.sujula.repository.delivery;

import com.sujula.model.constant.HandoverCodeType;
import com.sujula.model.delivery.HandoverCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface HandoverCodeRepository extends JpaRepository<HandoverCode, Long> {

    /**
     * Latest active (unused, not expired) code for a delivery leg.
     * Uses PESSIMISTIC_WRITE so concurrent generate() calls serialize on this row.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM HandoverCode h " +
           "WHERE h.delivery.id = :deliveryId " +
           "  AND h.codeType = :codeType " +
           "  AND h.used = false " +
           "  AND h.expiresAt > :now " +
           "ORDER BY h.createdAt DESC")
    Optional<HandoverCode> findActiveForUpdate(
            @Param("deliveryId") Long deliveryId,
            @Param("codeType") HandoverCodeType codeType,
            @Param("now") LocalDateTime now);

    /** Plain read (no lock) — used for display purposes only. */
    Optional<HandoverCode> findFirstByDeliveryIdAndCodeTypeAndUsedFalseAndExpiresAtAfter(
            Long deliveryId, HandoverCodeType codeType, LocalDateTime now);

    /** Validate a code presented by a recipient. */
    Optional<HandoverCode> findByDeliveryIdAndCodeTypeAndCodeAndUsedFalseAndExpiresAtAfter(
            Long deliveryId, HandoverCodeType codeType, String code, LocalDateTime now);

    List<HandoverCode> findByDeliveryId(Long deliveryId);

    // ── Vendor release codes ─────────────────────────────────────────────────

    /**
     * The live release code for a vendor order, if there is one.
     *
     * <p>At most one is live at a time: reissuing invalidates the previous, so a
     * code that has leaked is dead rather than one of several that all work.
     */
    @Query("SELECT h FROM HandoverCode h WHERE h.vendorOrder.id = :vendorOrderId "
         + "AND h.codeType = com.sujula.model.constant.HandoverCodeType.VENDOR_RELEASE "
         + "AND h.used = FALSE AND h.invalidatedAt IS NULL "
         + "ORDER BY h.createdAt DESC LIMIT 1")
    Optional<HandoverCode> findLiveReleaseCode(@Param("vendorOrderId") Long vendorOrderId);

    /**
     * The code a driver is presenting, matched against the live one only.
     *
     * <p>Scoped to unused, un-invalidated and unexpired in the query rather than
     * checked afterwards, because a check after the fact is the one that ships
     * missing.
     */
    @Query("SELECT h FROM HandoverCode h WHERE h.vendorOrder.id = :vendorOrderId "
         + "AND h.codeType = com.sujula.model.constant.HandoverCodeType.VENDOR_RELEASE "
         + "AND h.code = :code AND h.used = FALSE AND h.invalidatedAt IS NULL "
         + "AND h.expiresAt > :now")
    Optional<HandoverCode> findPresentedReleaseCode(@Param("vendorOrderId") Long vendorOrderId,
                                                    @Param("code") String code,
                                                    @Param("now") java.time.LocalDateTime now);

    /**
     * How many release codes have been issued for this slice since a moment.
     *
     * <p>What the rate limit reads. Counting the rows rather than a counter on
     * the vendor order means the limit cannot be reset by anything that forgets
     * to increment.
     */
    @Query("SELECT COUNT(h) FROM HandoverCode h WHERE h.vendorOrder.id = :vendorOrderId "
         + "AND h.codeType = com.sujula.model.constant.HandoverCodeType.VENDOR_RELEASE "
         + "AND h.createdAt > :since")
    long countReleaseCodesSince(@Param("vendorOrderId") Long vendorOrderId,
                                @Param("since") java.time.LocalDateTime since);

    @Query("SELECT h FROM HandoverCode h WHERE h.vendorOrder.id = :vendorOrderId "
         + "AND h.codeType = com.sujula.model.constant.HandoverCodeType.VENDOR_RELEASE "
         + "AND h.used = FALSE AND h.invalidatedAt IS NULL")
    List<HandoverCode> findLiveReleaseCodes(@Param("vendorOrderId") Long vendorOrderId);
}
