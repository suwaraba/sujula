package com.sujula.controller;

import com.sujula.dto.request.reference.FxQuoteRequest;
import com.sujula.dto.response.reference.ReferenceResponses;
import com.sujula.service.reference.FxQuoteService;
import com.sujula.service.reference.ReferenceDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Currencies, what they are worth, and holding one of those answers still.
 *
 * <p>Public, because a storefront decides what currency selector to draw before
 * anyone has signed in, and because a shipping estimate in the wrong currency is
 * no estimate at all.
 *
 * <p>The two rate endpoints make different promises and are named to keep them
 * apart. {@code /rates} is indicative: what the pair was last published at, with
 * the age attached, committing to nothing. {@code /quote} commits — this rate,
 * for this window. A client charging against the first is charging against a
 * number that has already moved.
 */
@RestController
@RequestMapping("/currencies")
@Validated
@Tag(name = "currencies", description = "Supported currencies, indicative rates, and held quotes")
public class CurrencyController {

    private final ReferenceDataService reference;
    private final FxQuoteService fx;
    private final AuthenticatedCaller caller;

    public CurrencyController(ReferenceDataService reference, FxQuoteService fx,
                              AuthenticatedCaller caller) {
        this.reference = reference;
        this.fx = fx;
        this.caller = caller;
    }

    @GetMapping
    @Operation(summary = "Currencies this marketplace prices in",
               description = "Each carries its minorUnits — the decimal places the currency "
                       + "actually has. XOF has none, so a client that formats every amount to two "
                       + "places will show CFA totals that cannot be tendered.")
    public ResponseEntity<ReferenceResponses.Currencies> currencies() {
        return ResponseEntity.ok(reference.currencies());
    }

    @GetMapping("/rates")
    @Operation(summary = "What a pair was last published at",
               description = "Indicative, with fetchedAt so a client can say how old it is. "
                       + "'inverted: true' means no direct rate was stored and this is the "
                       + "reciprocal of the opposite pair, which carries no spread in this "
                       + "direction. To charge against a rate, hold one with POST /currencies/quote.")
    public ResponseEntity<ReferenceResponses.Rate> rate(
            @RequestParam("base") @NotBlank @Size(min = 3, max = 3) String base,
            @RequestParam("quote") @NotBlank @Size(min = 3, max = 3) String quote) {
        return ResponseEntity.ok(fx.rate(base, quote));
    }

    @PostMapping("/quote")
    @Operation(summary = "Hold a rate for checkout",
               description = "Locks the rate for a fixed window so the total a buyer agrees to is "
                       + "the total they are charged. Guest-compatible: the id is the credential, "
                       + "and a quote created while signed in is additionally bound to that account.")
    public ResponseEntity<ReferenceResponses.FxQuote> createQuote(
            Authentication authentication, @Valid @RequestBody FxQuoteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(fx.createQuote(request, caller.userIdOrNull(authentication)));
    }

    @GetMapping("/quote/{quoteId}")
    @Operation(summary = "Read a held rate back",
               description = "A quote that has expired, or that belongs to another account, is "
                       + "reported as not found — confirming it exists would tell whoever guessed "
                       + "the id that they guessed right.")
    public ResponseEntity<ReferenceResponses.FxQuote> quote(Authentication authentication,
                                                            @PathVariable String quoteId) {
        return ResponseEntity.ok(fx.get(quoteId, caller.userIdOrNull(authentication)));
    }
}
