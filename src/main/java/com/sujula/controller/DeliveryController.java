package com.sujula.controller;

import com.sujula.dto.request.delivery.ServiceabilityRequests;
import com.sujula.dto.response.delivery.ServiceabilityResponses;
import com.sujula.service.delivery.ServiceabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Can you deliver here, and what does it cost.
 *
 * <p>Both public, because both are asked before a basket exists — the first on a
 * product page, the second in a cart — and a shopper who has to sign in to find
 * out whether you reach their town will not sign in.
 *
 * <p>Neither writes anything. POST rather than GET because a destination is an
 * address: too long for a query string, and not something to leave in access
 * logs and browser history.
 */
@RestController
@RequestMapping("/delivery")
@Tag(name = "delivery", description = "Serviceability and shipping prices, before there is an order")
public class DeliveryController {

    private final ServiceabilityService serviceability;
    private final AuthenticatedCaller caller;

    public DeliveryController(ServiceabilityService serviceability, AuthenticatedCaller caller) {
        this.serviceability = serviceability;
        this.caller = caller;
    }

    @PostMapping("/serviceability")
    @Operation(summary = "Whether goods can get from there to here",
               description = "Origin and destination, each given as coordinates, a vendor or pickup "
                       + "point id, or an address to geocode — or a deliveryContextId for the "
                       + "destination. Returns each mode with an ETA, and the nearest collection "
                       + "points. An unavailable mode always says why.")
    public ResponseEntity<ServiceabilityResponses.Serviceability> serviceability(
            Authentication authentication,
            @Valid @RequestBody ServiceabilityRequests.Serviceability request) {
        return ResponseEntity.ok(
                serviceability.check(request, caller.userIdOrNull(authentication)));
    }

    @PostMapping("/quote")
    @Operation(summary = "What shipping costs, per mode",
               description = "For a basket the client has already totalled: its weight and its "
                       + "value. Priced from the same rate card checkout uses, so the cart's "
                       + "shipping row and the final charge agree. 'complete: false' means a rate "
                       + "was missing and the figures must not be charged.")
    public ResponseEntity<ServiceabilityResponses.Quote> quote(
            Authentication authentication,
            @Valid @RequestBody ServiceabilityRequests.Quote request) {
        return ResponseEntity.ok(
                serviceability.quote(request, caller.userIdOrNull(authentication)));
    }
}
