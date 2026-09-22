package com.sujula.controller;

import com.sujula.dto.response.buyerorder.BuyerOrderResponses;
import com.sujula.service.buyerorder.BuyerOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * The page for somebody with no account.
 *
 * <p>The sister in Serrekunda did not sign up for anything. She has a phone
 * number and a text message, and what that message can usefully contain is a
 * link — so this endpoint is open, and the tracking code is the only credential
 * it accepts.
 *
 * <p>Which means the code must be unguessable and the page must be worth
 * nothing to a stranger who finds it. It carries no name, no street, no phone
 * number, no price and no order number: a city, a country, how many parcels
 * there are, how many have arrived, and a sequence of fixed phrases. Anyone who
 * intercepts the SMS learns that a parcel is on its way to a city.
 */
@RestController
@RequestMapping("/track")
@Tag(name = "tracking", description = "Public parcel tracking for a recipient with no account")
public class PublicTrackingController {

    private final BuyerOrderService orders;

    public PublicTrackingController(BuyerOrderService orders) {
        this.orders = orders;
    }

    @GetMapping("/{trackingCode}")
    @Operation(summary = "Track a parcel by code",
               description = "Open, because the person waiting for the parcel may have no account "
                       + "and no email — an SMS code is the design target. What it returns is "
                       + "bounded to what is safe for whoever ends up holding the code: a "
                       + "destination city, parcel counts, an estimate and platform-written status "
                       + "phrases. No names, no addresses, no amounts.")
    public ResponseEntity<BuyerOrderResponses.PublicTracking> track(@PathVariable String trackingCode) {
        return ResponseEntity.ok()
                // Private, because a shared cache holding this would serve one
                // recipient's parcel to the next person asking through the same
                // proxy — and on the networks this is read over, that proxy exists.
                .cacheControl(CacheControl.maxAge(30, TimeUnit.SECONDS).cachePrivate())
                .body(orders.publicTracking(trackingCode));
    }
}
