package com.sujula.controller;


import com.sujula.dto.request.ExchangeRateRequest;
import com.sujula.dto.response.ExchangeRateResponse;
import com.sujula.dto.response.PagedResponse;
import com.sujula.service.ExchangeRateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/exchange-rates")
@RequiredArgsConstructor
public class ExchangeRateController {

    private final ExchangeRateService exchangeRateService;

    @GetMapping
    public ResponseEntity<PagedResponse<ExchangeRateResponse>> getAllPaged(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PagedResponse.of(exchangeRateService.findAll(PageRequest.of(page, size))));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ExchangeRateResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(exchangeRateService.findById(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ExchangeRateResponse> create(@Valid @RequestBody ExchangeRateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exchangeRateService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ExchangeRateResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody ExchangeRateRequest request) {
        return ResponseEntity.ok(exchangeRateService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        exchangeRateService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
