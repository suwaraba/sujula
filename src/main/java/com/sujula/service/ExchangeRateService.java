package com.sujula.service;

import com.sujula.dto.request.ExchangeRateRequest;
import com.sujula.dto.response.ExchangeRateResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;

public interface ExchangeRateService {

    /** A published rate together with the business date it actually represents. */
    record PublishedRate(BigDecimal rate, LocalDateTime rateAt) {}

    Page<ExchangeRateResponse> findAll(Pageable pageable);

    ExchangeRateResponse findById(Long id);

    ExchangeRateResponse create(ExchangeRateRequest request);

    ExchangeRateResponse update(Long id, ExchangeRateRequest request);

    void delete(Long id);

    Map<String, BigDecimal> getLatestRates(String targetCurrency, Collection<String> fromCurrencies);

    /**
     * Latest persisted rates without discarding their provenance.
     *
     * <p>Use this whenever the result will be frozen onto a financial record.
     * {@link #getLatestRates} remains for transient catalogue/display reads that
     * only need the number.
     */
    Map<String, PublishedRate> getLatestPublishedRates(
            String targetCurrency, Collection<String> fromCurrencies);
}
