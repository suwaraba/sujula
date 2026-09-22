package com.sujula.controller;

import com.sujula.dto.request.delivery.DeliveryContextRequests;
import com.sujula.dto.response.delivery.DeliveryContextResponse;
import com.sujula.service.delivery.DeliveryContextService;
import com.sujula.service.idempotency.IdempotencyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Where a basket is going, named once.
 *
 * <p>Open to guests, because most shopping happens before anyone signs in and a
 * shipping estimate is exactly what someone wants before they commit to an
 * account.
 *
 * <p>The id this returns is a bearer credential for a guest — whoever holds it
 * can read the destination. It is long, random and short-lived for that reason.
 * A context created while signed in is additionally bound to that account, so
 * holding the id is no longer sufficient.
 */
@RestController
@RequestMapping("/delivery-contexts")
@Tag(name = "delivery-contexts",
     description = "The destination the cart, the quote and checkout all price against")
public class DeliveryContextController {

    private static final String OPERATION = "delivery-contexts.create";

    private final DeliveryContextService contexts;
    private final AuthenticatedCaller caller;
    private final IdempotencyService idempotency;

    public DeliveryContextController(DeliveryContextService contexts, AuthenticatedCaller caller,
                                     IdempotencyService idempotency) {
        this.contexts = contexts;
        this.caller = caller;
        this.idempotency = idempotency;
    }

    @PostMapping
    @Operation(summary = "Resolve a destination and get its id",
               description = "Give a saved address, a pair of coordinates, or a written address to "
                       + "geocode — or nothing at all, which still returns a context carrying the "
                       + "country and currency inferred from the caller. Send an Idempotency-Key so "
                       + "a retry does not create a second context.")
    public ResponseEntity<DeliveryContextResponse> create(
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody DeliveryContextRequests.Create request,
            HttpServletRequest httpRequest) {

        Long userId = caller.userIdOrNull(authentication);

        // A guest has no account to scope the key to, so guests share one scope
        // per endpoint. The fingerprint check inside the service is what keeps
        // that safe: a collision between two different bodies is refused rather
        // than served, so nobody is handed another shopper's context.
        String scope = userId == null
                ? IdempotencyService.anonymousScope(OPERATION)
                : IdempotencyService.scopeFor(userId, OPERATION);

        DeliveryContextResponse created = idempotency.execute(
                scope, idempotencyKey, request, HttpStatus.CREATED.value(),
                DeliveryContextResponse.class,
                () -> contexts.create(userId, request, httpRequest));

        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{contextId}")
    @Operation(summary = "Read a delivery context",
               description = "Token-scoped: the id is the credential. A context that has expired, "
                       + "or that belongs to an account other than the caller's, is reported as not "
                       + "found — confirming it exists would tell whoever guessed the id that they "
                       + "guessed right.")
    public ResponseEntity<DeliveryContextResponse> get(Authentication authentication,
                                                       @PathVariable String contextId) {
        return ResponseEntity.ok(contexts.get(contextId, caller.userIdOrNull(authentication)));
    }
}
