package com.sujula.service.impl;

import com.sujula.model.ExchangeRate;
import com.sujula.repository.ExchangeRateRepository;
import com.sujula.service.ExchangeRateService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExchangeRateServiceImplTest {

    @Test
    void publishedReadReturnsThePersistedRateAndBusinessDateTogether() {
        ExchangeRateRepository repository = mock(ExchangeRateRepository.class);
        ExchangeRate persisted = ExchangeRate.builder()
                .fromCurrency("GMD")
                .currency("EUR")
                .rate(new BigDecimal("0.01456789"))
                .rateDate(LocalDate.of(2026, 9, 17))
                .build();
        when(repository.findLatestRates("EUR", List.of("GMD")))
                .thenReturn(List.of(persisted));

        Map<String, ExchangeRateService.PublishedRate> result =
                new ExchangeRateServiceImpl(repository)
                        .getLatestPublishedRates("eur", List.of("GMD"));

        assertEquals(0, persisted.getRate().compareTo(result.get("GMD").rate()));
        assertEquals(persisted.getRateDate().atStartOfDay(), result.get("GMD").rateAt());
    }
}
