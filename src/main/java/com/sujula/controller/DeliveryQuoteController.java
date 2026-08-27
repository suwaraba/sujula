package com.sujula.controller;

import com.sujula.dto.request.delivery.DeliveryQuoteRequest;
import com.sujula.service.DeliveryPricingService;
import com.sujula.service.delivery.DeliveryQuote;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * What delivery would cost for a basket that has not been ordered yet.
 *
 * <p>Open to guests as well as signed-in shoppers: a delivery price is part of
 * what a shopper is deciding on, and hiding it behind a login would leave a
 * guest cart unable to show a total.
 */
@RestController
public class DeliveryQuoteController {

    private final DeliveryPricingService deliveryPricingService;

    public DeliveryQuoteController(DeliveryPricingService deliveryPricingService) {
        this.deliveryPricingService = deliveryPricingService;
    }

    @PostMapping("/api/delivery/quote")
    public ResponseEntity<DeliveryQuote> quote(@Valid @RequestBody DeliveryQuoteRequest request) {
        return ResponseEntity.ok(deliveryPricingService.quote(request));
    }
}
