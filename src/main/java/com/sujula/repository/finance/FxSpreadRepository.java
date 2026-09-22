package com.sujula.repository.finance;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sujula.model.finance.FxSpread;

public interface FxSpreadRepository extends JpaRepository<FxSpread, Long> {

    /**
     * Every spread in force at an instant, newest first.
     *
     * <p>Takes a moment rather than assuming now, because the question worth
     * asking of this table is usually about the past: "what spread was applied to
     * this order in March" is answerable only if the row that was in force then
     * is still here and still findable.
     */
    @Query("SELECT s FROM FxSpread s WHERE s.effectiveFrom <= :at "
         + "ORDER BY s.effectiveFrom DESC, s.id DESC")
    List<FxSpread> findInForceAt(@Param("at") LocalDateTime at);

    @Query("SELECT s FROM FxSpread s "
         + "WHERE (:currency IS NULL OR UPPER(s.fromCurrency) = UPPER(:currency) "
         + "     OR UPPER(s.toCurrency) = UPPER(:currency)) "
         + "ORDER BY s.effectiveFrom DESC, s.id DESC")
    Page<FxSpread> history(@Param("currency") String currency, Pageable pageable);
}
