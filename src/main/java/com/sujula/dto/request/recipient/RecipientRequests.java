package com.sujula.dto.request.recipient;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * What somebody with no account sends about her own parcel.
 *
 * <p>Every one of these carries a {@code code} and nothing that identifies a
 * destination for it. That is the property worth stating plainly: there is no
 * field anywhere in this file for a phone number or an email address, so there
 * is no request that can ask for a code to be sent somewhere the order does not
 * already say. An endpoint that took one would be an endpoint for having
 * somebody else's parcel code delivered to you.
 */
public final class RecipientRequests {

    private RecipientRequests() {}

    /**
     * The six digits, presented with every instruction.
     *
     * <p>Not burned on use — see {@code ParcelAccessCode}. A recipient who
     * chooses a counter and then wants a different day would otherwise have to
     * telephone another continent between the two.
     */
    public record Code(
            @NotBlank(message = "Enter the six-digit code")
            @Pattern(regexp = "^\\d{6}$", message = "The code is six digits")
            String code) {}

    /** Send it to a counter instead of the door. */
    public record ChoosePickupPoint(
            @NotBlank(message = "Enter the six-digit code")
            @Pattern(regexp = "^\\d{6}$", message = "The code is six digits")
            String code,

            @NotNull(message = "Choose a collection point")
            Long pickupPointId) {}

    /**
     * Come on a different day.
     *
     * <p>A window rather than a time, because a driver covering Serrekunda
     * cannot promise eleven o'clock and a recipient who was told eleven and
     * waited until two has been let down by a number the system invented.
     */
    public record Reschedule(
            @NotBlank(message = "Enter the six-digit code")
            @Pattern(regexp = "^\\d{6}$", message = "The code is six digits")
            String code,

            @NotNull(message = "Say when the driver should come")
            LocalDateTime from,

            @NotNull(message = "Say when the window closes")
            LocalDateTime until,

            @Size(max = 200) String note) {}

    /**
     * Leave it without me.
     *
     * <p>One of {@code location} and {@code person} is required, which the
     * service enforces rather than the annotations: "leave it somewhere" is not
     * something a driver can act on, and a parcel left on the strength of it is
     * a parcel nobody can account for.
     */
    public record AuthoriseSafeDrop(
            @NotBlank(message = "Enter the six-digit code")
            @Pattern(regexp = "^\\d{6}$", message = "The code is six digits")
            String code,

            /** Where, in her own words. Never parsed — "behind the shop" is an address here. */
            @Size(max = 300) String location,

            /** Who may take it. A first name is enough and all that is wanted. */
            @Size(max = 120) String person,

            /**
             * False to take the permission back.
             *
             * <p>Not a separate endpoint, because it is the same decision being
             * made the other way, and because somebody who got home early needs
             * the withdrawal to be as easy as the authorisation was. Null means
             * true: a form that posts a location and a name is authorising.
             */
            Boolean authorised) {

        /** Whether this is a permission being given rather than taken back. */
        public boolean isGranting() {
            return authorised == null || authorised;
        }
    }
}
