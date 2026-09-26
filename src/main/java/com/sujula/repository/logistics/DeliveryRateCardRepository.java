package com.sujula.repository.logistics;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sujula.model.constant.DeliveryMode;
import com.sujula.model.logistics.DeliveryRateCard;

public interface DeliveryRateCardRepository extends JpaRepository<DeliveryRateCard, Long> {

    /**
     * Every card that prices something on {@code day}, most specific first.
     *
     * <p>The caller picks the first whose scope matches — zone before country,
     * a named mode before all modes. Specificity is sorted here so the choice is
     * made by one rule in one place rather than by whichever row the database
     * happened to return.
     */
    @Query("SELECT c FROM DeliveryRateCard c WHERE c.active = TRUE "
         + "AND c.effectiveFrom <= :day "
         + "AND (c.effectiveUntil IS NULL OR c.effectiveUntil >= :day) "
         + "ORDER BY c.effectiveFrom DESC, c.id DESC")
    List<DeliveryRateCard> findInForceOn(@Param("day") LocalDate day);

    /**
     * Cards covering exactly the same scope, so a new one can close the old.
     *
     * <p>Two live cards for one scope on one day is a mistake rather than a tie,
     * and this is what lets the write path notice it before it happens.
     */
    @Query("SELECT c FROM DeliveryRateCard c WHERE c.active = TRUE "
         + "AND ((:zoneId IS NULL AND c.zone IS NULL) OR c.zone.id = :zoneId) "
         + "AND ((:countryCode IS NULL AND c.countryCode IS NULL) "
         + "     OR UPPER(c.countryCode) = UPPER(CAST(:countryCode AS String))) "
         + "AND ((:mode IS NULL AND c.mode IS NULL) OR c.mode = :mode) "
         + "AND (c.effectiveUntil IS NULL OR c.effectiveUntil >= :day) "
         + "ORDER BY c.effectiveFrom ASC")
    List<DeliveryRateCard> findOverlapping(@Param("zoneId") Long zoneId,
                                           @Param("countryCode") String countryCode,
                                           @Param("mode") DeliveryMode mode,
                                           @Param("day") LocalDate day);

    @Query("SELECT c FROM DeliveryRateCard c "
         + "WHERE (:zoneId IS NULL OR c.zone.id = :zoneId) "
         + "AND (:countryCode IS NULL OR UPPER(c.countryCode) = UPPER(CAST(:countryCode AS String))) "
         + "AND (:mode IS NULL OR c.mode = :mode) "
         + "AND (:activeOnly = FALSE OR (c.active = TRUE "
         + "     AND (c.effectiveUntil IS NULL OR c.effectiveUntil >= :today))) "
         + "ORDER BY c.effectiveFrom DESC, c.id DESC")
    Page<DeliveryRateCard> search(@Param("zoneId") Long zoneId,
                                  @Param("countryCode") String countryCode,
                                  @Param("mode") DeliveryMode mode,
                                  @Param("activeOnly") boolean activeOnly,
                                  @Param("today") LocalDate today,
                                  Pageable pageable);

    long countByZoneId(Long zoneId);
}
