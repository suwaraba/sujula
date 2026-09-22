package com.sujula.controller;

import java.util.concurrent.TimeUnit;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sujula.dto.request.recipient.RecipientRequests;
import com.sujula.dto.response.recipient.RecipientResponses;
import com.sujula.service.recipient.RecipientParcelService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * The only surface on this platform with no account behind it.
 *
 * <p>Every route here is open, and that is not a gap — it is C5. The person the
 * parcel is for may have a phone number and nothing else: no account, no app, no
 * email, no way to click a link in an inbox she does not have. She is also the
 * only person who knows whether she will be at home on Thursday, so a system
 * that made her sign in to say so would have moved that decision to her brother
 * in Madrid, who does not know either.
 *
 * <p>Open, so two credentials rather than a session:
 *
 * <ul>
 *   <li>The <b>tracking code</b> in the path reads the page. Unguessable, and
 *       what it opens carries a first name, a town and a set of fixed phrases —
 *       nothing a stranger who found the message could use.</li>
 *   <li>A <b>six-digit code</b> in the body authorises the three changes. It is
 *       asked for here and delivered to the contact on the order; the request
 *       for it takes no destination, so there is no shape of call that has
 *       somebody else's code sent to you.</li>
 * </ul>
 *
 * <p>Distinct from {@code /track/{code}}, which is the whole order: three
 * sellers make three parcels that arrive on three days, and "send it to the shop
 * by the ferry instead" is a decision about one box.
 */
@RestController
@RequestMapping("/parcels")
@Tag(name = "parcels", description = "The recipient's own parcel, for somebody with no account")
public class RecipientParcelController {

    private final RecipientParcelService parcels;

    public RecipientParcelController(RecipientParcelService parcels) {
        this.parcels = parcels;
    }

    @GetMapping("/{trackingCode}")
    @Operation(summary = "One parcel, as the person waiting for it sees it",
               description = "Open, because possession of the code is the credential. Carries a "
                       + "first name, a town, parcel count, fixed status phrases and — once it is "
                       + "going to one — a collection point's public address. No surname, no "
                       + "street, no phone number, no order number and no prices.")
    public ResponseEntity<RecipientResponses.Parcel> parcel(@PathVariable String trackingCode) {
        return ResponseEntity.ok()
                // Private, because a shared cache holding this would serve one
                // recipient's parcel to the next person through the same proxy —
                // and on the networks this is read over, that proxy exists.
                .cacheControl(CacheControl.maxAge(30, TimeUnit.SECONDS).cachePrivate())
                .body(parcels.parcel(trackingCode));
    }

    @PostMapping("/{trackingCode}/request-code")
    @Operation(summary = "Ask for the six digits",
               description = "Takes no destination and has nowhere to put one: where the code goes "
                       + "is the contact on the order, read by the server. Three an hour per "
                       + "parcel, because whoever is asking is anonymous by design and must not be "
                       + "able to fill the buyer's inbox by holding down a button.")
    public ResponseEntity<RecipientResponses.CodeSent> requestCode(
            @PathVariable String trackingCode) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(parcels.requestCode(trackingCode));
    }

    @PostMapping("/{trackingCode}/choose-pickup-point")
    @Operation(summary = "Send it to a counter instead of the door",
               description = "The counter is checked against the parcel's destination country — "
                       + "the delivery context — and never against anything about the person who "
                       + "paid. A buyer in Madrid redirecting his sister's parcel must not be "
                       + "offered a counter in Madrid.")
    public ResponseEntity<RecipientResponses.InstructionRecorded> choosePickupPoint(
            @PathVariable String trackingCode,
            @Valid @RequestBody RecipientRequests.ChoosePickupPoint request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(parcels.choosePickupPoint(trackingCode, request));
    }

    @PostMapping("/{trackingCode}/reschedule")
    @Operation(summary = "Come on a different day",
               description = "A window rather than a time. A driver covering Serrekunda cannot "
                       + "promise eleven o'clock, and a recipient who was told eleven and waited "
                       + "until two has been let down by a number the system invented.")
    public ResponseEntity<RecipientResponses.InstructionRecorded> reschedule(
            @PathVariable String trackingCode,
            @Valid @RequestBody RecipientRequests.Reschedule request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(parcels.reschedule(trackingCode, request));
    }

    @PostMapping("/{trackingCode}/authorise-safe-drop")
    @Operation(summary = "Leave it with the neighbour",
               description = "The one instruction that changes what counts as proof of delivery, "
                       + "so it is recorded with the code that was presented and the words that "
                       + "were used rather than stored as a flag. Post it with authorised=false to "
                       + "take the permission back.")
    public ResponseEntity<RecipientResponses.InstructionRecorded> authoriseSafeDrop(
            @PathVariable String trackingCode,
            @Valid @RequestBody RecipientRequests.AuthoriseSafeDrop request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(parcels.authoriseSafeDrop(trackingCode, request));
    }
}
