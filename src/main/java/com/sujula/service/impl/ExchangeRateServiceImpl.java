package com.sujula.service.impl;


import com.sujula.dto.request.ExchangeRateRequest;
import com.sujula.dto.response.ExchangeRateResponse;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.ExchangeRate;
import com.sujula.repository.ExchangeRateRepository;
import com.sujula.service.ExchangeRateService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ExchangeRateServiceImpl implements ExchangeRateService {

    private final ExchangeRateRepository exchangeRateRepository;

    @Override
    @Transactional(readOnly = true)
    public Page<ExchangeRateResponse> findAll(Pageable pageable) {
        return exchangeRateRepository.findAll(pageable)
                .map(ExchangeRateResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public ExchangeRateResponse findById(Long id) {
        return ExchangeRateResponse.from(getExchangeRate(id));
    }

    @Override
    @Transactional
    public ExchangeRateResponse create(ExchangeRateRequest request) {
        String fromCurrency = request.getFromCurrency().toUpperCase();
        String currency = request.getCurrency().toUpperCase();
        String country = request.getCountry() != null ? request.getCountry().toUpperCase() : null;

        exchangeRateRepository.findByFromCurrencyAndCurrencyAndRateDate(fromCurrency, currency, request.getRateDate())
                .ifPresent(existing -> {
                    throw new BadRequestException(
                            "An exchange rate for " + fromCurrency + " -> " + currency
                                    + " on " + request.getRateDate() + " already exists");
                });

        ExchangeRate exchangeRate = ExchangeRate.builder()
                .fromCurrency(fromCurrency)
                .currency(currency)
                .country(country)
                .rate(request.getRate())
                .rateDate(request.getRateDate())
                .build();

        return ExchangeRateResponse.from(exchangeRateRepository.save(exchangeRate));
    }

    @Override
    @Transactional
    public ExchangeRateResponse update(Long id, ExchangeRateRequest request) {
        ExchangeRate exchangeRate = getExchangeRate(id);

        String fromCurrency = request.getFromCurrency().toUpperCase();
        String currency = request.getCurrency().toUpperCase();
        String country = request.getCountry() != null ? request.getCountry().toUpperCase() : null;

        exchangeRateRepository.findByFromCurrencyAndCurrencyAndRateDate(fromCurrency, currency, request.getRateDate())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new BadRequestException(
                            "An exchange rate for " + fromCurrency + " -> " + currency
                                    + " on " + request.getRateDate() + " already exists");
                });

        exchangeRate.setFromCurrency(fromCurrency);
        exchangeRate.setCurrency(currency);
        exchangeRate.setCountry(country);
        exchangeRate.setRate(request.getRate());
        exchangeRate.setRateDate(request.getRateDate());

        return ExchangeRateResponse.from(exchangeRateRepository.save(exchangeRate));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        ExchangeRate exchangeRate = getExchangeRate(id);
        exchangeRateRepository.delete(exchangeRate);
    }

    private ExchangeRate getExchangeRate(Long id) {
        return exchangeRateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ExchangeRate", id));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, BigDecimal> getLatestRates(String targetCurrency, Collection<String> fromCurrencies) {
        if (fromCurrencies == null || fromCurrencies.isEmpty()) {
            return Map.of();
        }
        return exchangeRateRepository.findLatestRates(targetCurrency.toUpperCase(), fromCurrencies)
                .stream()
                .collect(Collectors.toMap(ExchangeRate::getFromCurrency, ExchangeRate::getRate));
    }
}