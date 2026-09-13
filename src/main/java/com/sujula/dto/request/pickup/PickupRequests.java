package com.sujula.dto.request.pickup;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** What a pickup point operator sends. */
public final class PickupRequests {

    private PickupRequests() {}

    /**
     * Applying to hold other people's parcels.
     *
     * <p>An address and a position are both required, and they are not the same
     * thing. The address is what a shopper reads; the position is what the app
     * navigates to and what the collection geofence is measured against — and in
     * this market most addresses do not resolve to a point on their own, so
     * asking for one and deriving the other would fail on the ordinary case.
     */
    public record Apply(
            @NotBlank(message = "A name is required — it is what shoppers will look for")
            @Size(max = 150) String name,

            @NotBlank(message = "A street address is required") @Size(max = 300)
            String addressStreet,

            @Size(max = 100) String addressApartment,

            @NotBlank(message = "A town is required") @Size(max = 120) String city,
            @Size(max = 120) String state,
            @Size(max = 20) String postalCode,

            @Pattern(regexp = "^[A-Za-z]{2}$", message = "A country is a two-letter code, like GM")
            String countryCode,

            @NotNull(message = "A position is required — most addresses here do not resolve to one")
            @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,

            @NotNull(message = "A position is required")
            @DecimalMin("-180.0") @DecimalMax("180.0") Double lng,

            @NotBlank(message = "A phone number is required — it is how drivers reach you")
            @Size(max = 30) String contactPhone,

            @Size(max = 200) String contactEmail,

            @NotBlank(message = "Say who runs the counter") @Size(max = 150) String managerName,

            @NotBlank(message = "Say when you are open — a shopper sent to a closed counter has "
                    + "been misdirected")
            @Size(max = 500) String openingHours,

            @Min(value = 1, message = "A counter that can hold nothing is not a pickup point")
            @Max(value = 5000, message = "That is a warehouse rather than a counter")
            Integer capacity,

            @Size(max = 500) String profileImageUrl) {
    }

    /**
     * Changing how a counter runs.
     *
     * <p>Everything is optional; what is sent is changed and what is not is left
     * alone. Nothing here touches status or earnings — an operator who could
     * approve themselves would make the review meaningless.
     */
    public record UpdatePoint(
            @Size(max = 150) String name,
            @Size(max = 500) String openingHours,

            @Min(value = 1, message = "A counter that can hold nothing is not a pickup point")
            @Max(value = 5000) Integer capacity,

            @Min(value = 1, message = "Parcels need at least a day to be collected")
            @Max(value = 90, message = "Anything longer than three months is storage, not pickup")
            Integer storageDays,

            @Size(max = 30) String contactPhone,
            @Size(max = 200) String contactEmail,
            @Size(max = 500) String profileImageUrl,

            /**
             * Shut until this moment.
             *
             * <p>Null clears it and reopens. A point closed this way keeps the
             * parcels it already holds — the people waiting on them did not
             * choose the closure — and stops being offered new ones.
             */
            LocalDateTime closedUntil,

            @Size(max = 300) String closureReason) {
    }

    /**
     * Taking a parcel in from a driver.
     *
     * <p>The operator verifies the driver's code, which is the receiving party
     * checking the giving party — the same shape as every other link in the
     * chain, and the only thing a code can prove.
     */
    public record AcceptParcel(
            @NotBlank(message = "The driver's code is required — it is the proof this happened")
            @Pattern(regexp = "\\d{4,8}", message = "A handover code is 4 to 8 digits")
            String code,

            /** The signed token from the parcel label, where it was scanned. */
            @Size(max = 400) String qrToken,

            /**
             * Where on the shelf it goes.
             *
             * <p>Optional: the counter allocates one when the operator does not
             * care, which is most of the time. It is a location label rather
             * than a credential — anybody who can see it can already see the
             * parcel.
             */
            @Size(max = 12) String shelfCode,

            @Size(max = 300) String note,
            @Size(max = 64) String clientEventId) {

        public String cleanedCode() {
            return code == null ? null : code.trim();
        }
    }

    /**
     * Turning a parcel away at the door.
     *
     * <p>The reason decides what happens to it, so it is a code rather than free
     * text: a damaged parcel is a different problem from a full shelf, and only
     * one of them is the driver's to solve.
     */
    public record RejectParcel(
            @NotNull(message = "A reason is required") Reason reason,

            @Size(max = 400) String note,
            @Size(max = 500) String photoUrl,
            @Size(max = 64) String clientEventId) {

        public enum Reason {
            /** Arrived damaged. Goes back with evidence. */
            DAMAGED,
            /** No shelf space. The driver takes it elsewhere. */
            OVER_CAPACITY,
            /** Not what the manifest said, or not addressed here. */
            WRONG_PARCEL,
            /** Too big or too heavy for this counter. */
            TOO_LARGE,
            /** The counter is closing and cannot take it today. */
            CLOSING;

            /** Whether the parcel has a problem, as against the counter having one. */
            public boolean isParcelsFault() {
                return this == DAMAGED || this == WRONG_PARCEL || this == TOO_LARGE;
            }
        }
    }

    /**
     * Handing a parcel to the person it is for.
     *
     * <p>Two checks, and both are asked for. The code proves they were told it
     * by whoever sent the parcel; the name is what the operator reads off the
     * shelf and compares to the person in front of them. A code alone would let
     * anybody who overheard it collect; a name alone would let anybody who read
     * the label.
     */
    public record ReleaseParcel(
            @NotBlank(message = "The recipient's code is required")
            @Pattern(regexp = "\\d{4,8}", message = "A collection code is 4 to 8 digits")
            String code,

            @NotBlank(message = "Write down the name of the person collecting")
            @Size(max = 200) String collectedByName,

            /** An identity document number, where the operator asked for one. */
            @Size(max = 60) String identityShown,

            @Size(max = 500) String signatureUrl,
            @Size(max = 500) String photoUrl,
            @Size(max = 300) String note,
            @Size(max = 64) String clientEventId) {

        public String cleanedCode() {
            return code == null ? null : code.trim();
        }
    }

    /** Sending a parcel back after nobody came for it. */
    public record ReturnParcel(
            @Size(max = 400) String note,
            @Size(max = 64) String clientEventId) {
    }

    /** What a shopper is searching for. */
    public record NearbySearch(
            @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
            @DecimalMin("-180.0") @DecimalMax("180.0") Double lng,
            @DecimalMin(value = "0.1", message = "A radius has to be more than nothing")
            @DecimalMax(value = "200.0", message = "Anything beyond 200km is not a pickup point")
            Double radius,
            @Size(max = 120) String city) {
    }
}
