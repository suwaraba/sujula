package com.sujula.repository;

import com.sujula.model.ExchangeRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, Long> {

    Optional<ExchangeRate> findByFromCurrencyAndCurrencyAndRateDate(
            String fromCurrency, String currency, LocalDate rateDate);

    Optional<ExchangeRate> findTopByFromCurrencyAndCurrencyOrderByRateDateDesc(
            String fromCurrency, String currency);

    Optional<ExchangeRate> findTopByFromCurrencyAndCurrencyAndCountryOrderByRateDateDesc(
            String fromCurrency, String currency, String country);

    @Query("SELECT e FROM ExchangeRate e WHERE e.currency = :currency AND e.fromCurrency IN :fromCurrencies " +
            "AND e.rateDate = (SELECT MAX(e2.rateDate) FROM ExchangeRate e2 " +
            "WHERE e2.currency = e.currency AND e2.fromCurrency = e.fromCurrency)")
    List<ExchangeRate> findLatestRates(
            @Param("currency") String currency,
            @Param("fromCurrencies") Collection<String> fromCurrencies);

    /**
     * Rate history, for explaining a figure somebody was charged.
     *
     * <p>Every row ever recorded rather than the latest, because the question
     * this table exists to answer is about the past: an order converted in March
     * carries a rate, and somebody eventually asks where that rate came from.
     */
    @Query("SELECT r FROM ExchangeRate r "
         + "WHERE (:currency IS NULL OR UPPER(r.fromCurrency) = UPPER(CAST(:currency AS String)) "
         + "     OR UPPER(r.currency) = UPPER(CAST(:currency AS String))) "
         + "AND (CAST(:from AS LocalDate) IS NULL OR r.rateDate >= :from) "
         + "AND (CAST(:to AS LocalDate) IS NULL OR r.rateDate <= :to) "
         + "ORDER BY r.rateDate DESC, r.id DESC")
    org.springframework.data.domain.Page<ExchangeRate> history(
            @Param("currency") String currency,
            @Param("from") java.time.LocalDate from,
            @Param("to") java.time.LocalDate to,
            org.springframework.data.domain.Pageable pageable);

    /** Every pair the platform has ever recorded a rate for. */
    @Query("SELECT DISTINCT r.fromCurrency, r.currency FROM ExchangeRate r")
    List<Object[]> knownPairs();
}