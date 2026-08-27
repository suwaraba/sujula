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
}
