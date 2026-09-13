package com.sujula.controller;

import com.sujula.dto.request.geo.GeoRequests;
import com.sujula.dto.response.geo.GeoResponses;
import com.sujula.service.geo.GeoLookupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Looking places up, before anybody has committed to anything.
 *
 * <p>Public, because they are needed before there is an account: a shopper
 * checking whether their address is recognised, or a storefront working out
 * which currency to open in, has not signed in yet and should not have to.
 *
 * <p>Nothing here writes. That is what makes them safe to call on every
 * keystroke of an address form, and it is why they take a POST and still change
 * nothing — the address being looked up is too long and too personal for a query
 * string that ends up in access logs and browser history.
 */
@RestController
@RequestMapping("/geo")
@Tag(name = "geo", description = "Address lookup and shopper context. Public, and nothing is stored.")
public class GeoController {

    private final GeoLookupService geo;

    public GeoController(GeoLookupService geo) {
        this.geo = geo;
    }

    @PostMapping("/validate-address")
    @Operation(summary = "Check an address without saving it",
               description = "Returns the resolved point and how much it is worth. A confidence "
                       + "below INTERPOLATED means ask the buyer to place the pin — normal for much "
                       + "of the region, not a failure. 'available: false' means this deployment "
                       + "cannot geocode at all, which is a different thing from not finding it.")
    public ResponseEntity<GeoResponses.AddressLookup> validateAddress(
            @Valid @RequestBody GeoRequests.ValidateAddress request) {
        return ResponseEntity.ok(geo.validate(request));
    }

    @PostMapping("/reverse")
    @Operation(summary = "Name a point on the map",
               description = "What the buyer just dropped a pin on: country, city and a formatted "
                       + "address to show back to them.")
    public ResponseEntity<GeoResponses.AddressLookup> reverse(
            @Valid @RequestBody GeoRequests.Reverse request) {
        return ResponseEntity.ok(geo.reverse(request));
    }

    @GetMapping("/resolve-context")
    @Operation(summary = "Where this shopper appears to be",
               description = "Country, currency, language and timezone, from the CDN's country "
                       + "header or the caller's IP. A guess to open the storefront with, never a "
                       + "decision: let the shopper change it. Always answers — an unresolvable "
                       + "caller gets the home market rather than an error.")
    public ResponseEntity<GeoResponses.ResolvedContext> resolveContext(HttpServletRequest request) {
        return ResponseEntity.ok(geo.resolveContext(request));
    }
}
