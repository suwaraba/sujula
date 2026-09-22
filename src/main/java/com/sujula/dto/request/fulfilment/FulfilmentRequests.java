package com.sujula.dto.request.fulfilment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * What a seller sends while working an order.
 *
 * <p>Deliberately small. Almost every fulfilment step is a statement that
 * something physical happened — the goods are packed, this handset is the one in
 * the box — and the server decides what that means for the order. A request
 * body that carried the resulting status would be a client telling the server
 * what to believe.
 */
public final class FulfilmentRequests {

    private FulfilmentRequests() {}

    /**
     * Turning an order down.
     *
     * <p>The reason is required, and it is required because of who reads it:
     * a buyer who has already been charged and is about to be told they are not
     * getting their goods. "Rejected" on its own turns into a support ticket, and
     * the person best placed to avoid that is the seller closing the order.
     */
    public record Reject(
            @NotBlank(message = "Say why you cannot fulfil this order — the buyer is told, and "
                    + "has already paid")
            @Size(min = 5, max = 400, message = "A reason must be between 5 and 400 characters")
            String reason) {

        /** Trimmed, because a reason of spaces passes @NotBlank's cousin but not this. */
        public String cleaned() {
            return reason == null ? null : reason.trim();
        }
    }

    /**
     * Binding one physical handset to one line of the order.
     *
     * <p>The IMEI is typed off the handset itself, so the format is checked here
     * and the check digit in the service: fifteen digits is a shape mistake,
     * a bad Luhn digit is a transcription mistake, and the two deserve
     * different messages.
     */
    public record AssignImei(
            @NotBlank(message = "An IMEI is required")
            @Pattern(regexp = "\\d{15}",
                     message = "An IMEI is exactly 15 digits. Dial *#06# on the handset to see it.")
            String imei) {

        public String cleaned() {
            return imei == null ? null : imei.trim();
        }
    }
}
