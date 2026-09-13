package com.sujula.controller;

import com.sujula.dto.request.address.AddressRequests;
import com.sujula.dto.response.address.AddressResponses;
import com.sujula.service.AddressService;
import com.sujula.service.idempotency.IdempotencyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The signed-in buyer's address book.
 *
 * <p>No account id appears in any of these paths. Every operation resolves its
 * owner from the authenticated principal, and the one id that does appear —
 * {@code /me/addresses/{id}} — is matched against that owner inside the query,
 * so another buyer's address is reported as not found rather than refused. An
 * address carries a name, a phone number and a location: confirming one exists
 * is itself worth withholding.
 *
 * <p>Controllers bind DTOs and nothing else. Conversion lives in the service,
 * and no entity reaches this class.
 */
@RestController
@RequestMapping("/me/addresses")
@PreAuthorize("isAuthenticated()")
@Tag(name = "me-addresses", description = "The signed-in buyer's saved delivery addresses")
public class MeAddressController {

    private static final String OPERATION = "me.addresses.create";

    private final AddressService addresses;
    private final AuthenticatedCaller caller;
    private final IdempotencyService idempotency;

    public MeAddressController(AddressService addresses, AuthenticatedCaller caller,
                               IdempotencyService idempotency) {
        this.addresses = addresses;
        this.caller = caller;
        this.idempotency = idempotency;
    }

    @GetMapping
    @Operation(summary = "Every saved address",
               description = "Default first, then newest. Deleted addresses are not listed.")
    public ResponseEntity<List<AddressResponses.Address>> list(Authentication authentication) {
        return ResponseEntity.ok(addresses.listMine(caller.userId(authentication)));
    }

    /**
     * {@code Idempotency-Key} is what makes a retry safe here.
     *
     * <p>A save that times out on a slow connection is indistinguishable, from
     * the client's side, from one that never arrived — so clients retry, and
     * without a key the buyer ends up with the address twice.
     */
    @PostMapping
    @Operation(summary = "Save an address",
               description = "Geocoded when the client sends no coordinates. The response carries "
                       + "geocodeConfidence: below INTERPOLATED, ask the buyer to confirm the pin. "
                       + "Send an Idempotency-Key so a retried save does not create a second address.")
    public ResponseEntity<AddressResponses.Address> create(
            Authentication authentication,
            @Parameter(description = "A unique value per save, so a retry is answered rather than repeated")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AddressRequests.Create request) {

        Long userId = caller.userId(authentication);
        AddressResponses.Address saved = idempotency.execute(
                IdempotencyService.scopeFor(userId, OPERATION), idempotencyKey, request,
                HttpStatus.CREATED.value(), AddressResponses.Address.class,
                () -> addresses.add(userId, request));

        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @GetMapping("/{addressId}")
    @Operation(summary = "One saved address")
    public ResponseEntity<AddressResponses.Address> one(Authentication authentication,
                                                        @PathVariable Long addressId) {
        return ResponseEntity.ok(addresses.getMine(caller.userId(authentication), addressId));
    }

    @PatchMapping("/{addressId}")
    @Operation(summary = "Change part of an address",
               description = "Only the fields sent are changed. Re-geocodes when the edit moves the "
                       + "address — never when it only changes who is at it, and never over a pin "
                       + "the owner has confirmed.")
    public ResponseEntity<AddressResponses.Address> patch(
            Authentication authentication, @PathVariable Long addressId,
            @Valid @RequestBody AddressRequests.Patch request) {
        return ResponseEntity.ok(addresses.patch(caller.userId(authentication), addressId, request));
    }

    @DeleteMapping("/{addressId}")
    @Operation(summary = "Remove an address",
               description = "Kept as a record when an order was placed against it, so that order "
                       + "still resolves; deleted outright when none was. It leaves the address book "
                       + "either way.")
    public ResponseEntity<AddressResponses.Deletion> delete(Authentication authentication,
                                                            @PathVariable Long addressId) {
        return ResponseEntity.ok(addresses.remove(caller.userId(authentication), addressId));
    }

    @PostMapping("/{addressId}/confirm-pin")
    @Operation(summary = "Confirm where this address actually is",
               description = "For the addresses a geocoder could only place approximately, which in "
                       + "much of the region is most of them. What the owner confirms outranks "
                       + "anything resolved later, and later edits will not move it.")
    public ResponseEntity<AddressResponses.Address> confirmPin(
            Authentication authentication, @PathVariable Long addressId,
            @Valid @RequestBody AddressRequests.ConfirmPin request) {
        return ResponseEntity.ok(
                addresses.confirmPin(caller.userId(authentication), addressId, request));
    }
}
