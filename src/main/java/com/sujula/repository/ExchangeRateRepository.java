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
}
