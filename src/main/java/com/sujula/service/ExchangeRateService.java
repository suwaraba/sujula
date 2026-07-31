package com.sujula.service;

import com.sujula.dto.request.ExchangeRateRequest;
import com.sujula.dto.response.ExchangeRateResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;

public interface ExchangeRateService {

    Page<ExchangeRateResponse> findAll(Pageable pageable);

    ExchangeRateResponse findById(Long id);

    ExchangeRateResponse create(ExchangeRateRequest request);

    ExchangeRateResponse update(Long id, ExchangeRateRequest request);

    void delete(Long id);

    Map<String, BigDecimal> getLatestRates(String targetCurrency, Collection<String> fromCurrencies);
}
