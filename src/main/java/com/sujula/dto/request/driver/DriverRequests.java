package com.sujula.dto.request.driver;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.sujula.model.constant.VehicleType;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * What a driver's app sends.
 *
 * <p>Written for a phone on a bad connection in one hand and a parcel in the
 * other. Every field a driver has to type is either a short code or a choice
 * from a list; everything else — position, accuracy, timestamps — comes from the
 * device, and the parts that constitute proof are required rather than optional.
 */
public final class DriverRequests {

    private DriverRequests() {}

    // ── Becoming a driver ────────────────────────────────────────────────────

    /**
     * Applying to carry other people's goods.
     *
     * <p>The identity fields are required because of what the job is: a driver
     * holds a phone worth more than they earn in a month and turns up at a
     * buyer's family's home. An application with nothing to check is not an
     * application.
     */
    public record Apply(
            @NotBlank(message = "A phone number is required — it is how dispatch reaches you")
            @Size(max = 30) String phone,

            @NotNull(message = "Say what you drive") VehicleType vehicleType,

            @Size(max = 20) String vehiclePlate,
            @Size(max = 100) String vehicleModel,
            @Size(max = 50) String vehicleColor,

            @NotBlank(message = "A licence number is required")
            @Size(max = 100) String licenseNumber,

            LocalDate licenseExpiresOn,

            @NotBlank(message = "An identity document number is required")
            @Size(max = 60) String idDocumentNumber,

            @Size(max = 30) String idDocumentType,
            @Size(max = 500) String idDocumentUrl,
            @Size(max = 500) String licenseDocumentUrl,

            /** Somebody who will answer if the driver cannot be reached. */
            @Size(max = 120) String nextOfKinName,
            @Size(max = 30) String nextOfKinPhone,

            @NotBlank(message = "Say which area you cover")
            @Size(max = 120) String zone,

            @Pattern(regexp = "^[A-Za-z]{2}$", message = "A country is a two-letter code, like GM")
            String countryCode,

            Integer maxWeightKg) {
    }

    /** Changing what you drive or where you cover. */
    public record UpdateProfile(
            @Size(max = 30) String phone,
            VehicleType vehicleType,
            @Size(max = 20) String vehiclePlate,
            @Size(max = 100) String vehicleModel,
            @Size(max = 50) String vehicleColor,
            @Size(max = 120) String zone,
            @Pattern(regexp = "^[A-Za-z]{2}$", message = "A country is a two-letter code")
            String countryCode,
            Integer maxWeightKg,
            @Size(max = 500) String avatarUrl) {
    }

    /**
     * Going online or off.
     *
     * <p>A driver decides this; the platform decides whether they are approved
     * at all. The two are separate, and going online does not make a suspended
     * driver eligible for anything.
     */
    public record SetAvailability(
            @NotNull(message = "Say ONLINE or OFFLINE") Availability availability) {

        public enum Availability { ONLINE, OFFLINE }

        public boolean online() {
            return availability == Availability.ONLINE;
        }
    }

    /**
     * Where the driver is.
     *
     * <p>Accepted only while online, and throttled: a phone pinging every second
     * is a phone with a flat battery by eleven o'clock, on a platform where the
     * driver may not have anywhere to charge it.
     */
    public record Ping(
            @NotNull(message = "A latitude is required")
            @DecimalMin(value = "-90.0") @DecimalMax(value = "90.0") Double lat,

            @NotNull(message = "A longitude is required")
            @DecimalMin(value = "-180.0") @DecimalMax(value = "180.0") Double lng,

            /** Metres of uncertainty the device reported, where it did. */
            BigDecimal accuracy,

            /** The device's own clock, for a ping buffered while out of signal. */
            LocalDateTime capturedAt) {
    }

    // ── Assignments ──────────────────────────────────────────────────────────

    /** Turning a leg down, with the reason that moves the score. */
    public record Decline(
            @NotBlank(message = "Say why — it is what tells dispatch whether to re-offer nearby")
            @Size(max = 300) String reason) {
    }

    // ── Custody ──────────────────────────────────────────────────────────────

    /**
     * Proof that a handover happened.
     *
     * <p>Shared by collection, deposit, delivery and transfer, because they are
     * the same act with different parties. The code is what the receiving side
     * presented; the position is where the device says this took place.
     *
     * <p>The photograph is required on delivery and optional elsewhere, which the
     * service enforces rather than this record — the shape of the evidence is the
     * same, and only its sufficiency differs by link.
     */
    public record Handover(
            @NotBlank(message = "The code is required — it is the proof this happened")
            @Pattern(regexp = "\\d{4,8}", message = "A handover code is 4 to 8 digits")
            String code,

            /** The signed token from the parcel label, where the driver scanned it. */
            @Size(max = 400) String qrToken,

            @DecimalMin(value = "-90.0") @DecimalMax(value = "90.0") Double lat,
            @DecimalMin(value = "-180.0") @DecimalMax(value = "180.0") Double lng,
            BigDecimal accuracy,

            @Size(max = 500) String photoUrl,
            @Size(max = 500) String signatureUrl,

            @Size(max = 400) String note,

            /** The device's clock, for something captured with no signal. */
            LocalDateTime capturedAt,

            /**
             * The app's own id for this event.
             *
             * <p>What makes a retry safe. A phone that sends this, loses signal
             * before the reply and sends again cannot know whether the first
             * attempt landed — so it repeats the id and the server decides.
             */
            @Size(max = 64) String clientEventId) {

        public String cleanedCode() {
            return code == null ? null : code.trim();
        }
    }

    /** Arriving at the shop. No code: nothing has changed hands yet. */
    public record Arrived(
            @DecimalMin(value = "-90.0") @DecimalMax(value = "90.0") Double lat,
            @DecimalMin(value = "-180.0") @DecimalMax(value = "180.0") Double lng,
            BigDecimal accuracy,
            LocalDateTime capturedAt,
            @Size(max = 64) String clientEventId) {
    }

    /**
     * A delivery that did not happen.
     *
     * <p>A reason code rather than free text, because the reason decides what
     * happens next: nobody home is a retry, a refused parcel is a return, and a
     * wrong address is neither until somebody has looked at it.
     */
    public record DeliveryFailed(
            @NotNull(message = "A reason is required") FailureReason reason,

            @Size(max = 400) String note,

            /** A photograph of the closed door or the refused parcel. */
            @Size(max = 500) String photoUrl,

            @DecimalMin(value = "-90.0") @DecimalMax(value = "90.0") Double lat,
            @DecimalMin(value = "-180.0") @DecimalMax(value = "180.0") Double lng,
            BigDecimal accuracy,
            LocalDateTime capturedAt,
            @Size(max = 64) String clientEventId) {

        /** What went wrong, and what the platform does about it. */
        public enum FailureReason {
            /** Nobody at the address. Retried. */
            NOBODY_HOME,
            /** The address could not be found. Held for somebody to check. */
            ADDRESS_NOT_FOUND,
            /** The recipient could not produce the code. Retried. */
            NO_CODE,
            /** The recipient would not take it. Returned. */
            REFUSED,
            /** Unsafe to attempt — flooding, a crowd, after dark. Retried. */
            UNSAFE,
            /** The driver could not finish the round. Retried. */
            DRIVER_UNABLE;

            /** Whether another attempt is worth making. */
            public boolean retryable() {
                return this == NOBODY_HOME || this == NO_CODE || this == UNSAFE
                        || this == DRIVER_UNABLE;
            }

            /** Whether this ends the journey and sends the parcel back. */
            public boolean returnsParcel() {
                return this == REFUSED;
            }
        }
    }

    /**
     * Handing a parcel to another driver.
     *
     * <p>Both sides are named and both present something, because a transfer
     * recorded by one person is a link nobody can corroborate — and it is the
     * link at which a parcel would go missing if either half could be forged.
     */
    public record Transfer(
            @NotNull(message = "Say which driver is taking it") Long toDriverId,

            @NotBlank(message = "The receiving driver's code is required")
            @Pattern(regexp = "\\d{4,8}", message = "A handover code is 4 to 8 digits")
            String receivingDriverCode,

            @NotBlank(message = "Your own code is required — both sides attest to a transfer")
            @Pattern(regexp = "\\d{4,8}", message = "A handover code is 4 to 8 digits")
            String myCode,

            @DecimalMin(value = "-90.0") @DecimalMax(value = "90.0") Double lat,
            @DecimalMin(value = "-180.0") @DecimalMax(value = "180.0") Double lng,
            BigDecimal accuracy,
            @Size(max = 500) String photoUrl,
            @Size(max = 400) String note,
            LocalDateTime capturedAt,
            @Size(max = 64) String clientEventId) {
    }

    /**
     * A batch of events captured with no signal.
     *
     * <p>The case this platform actually runs in. A driver works a round through
     * an area with no coverage, and everything they recorded arrives at once when
     * they come back within range. Every entry carries the app's own id, so
     * sending the batch twice records it once.
     */
    public record SyncBatch(
            @NotNull @Size(min = 1, max = 100,
                    message = "Send between 1 and 100 events at a time")
            @Valid List<SyncEntry> events) {
    }

    /** One offline-captured event, named by the device that captured it. */
    public record SyncEntry(
            @NotNull(message = "Which shipment this was") Long shipmentId,

            @NotBlank(message = "Which kind of event") String type,

            @NotBlank(message = "An event captured offline must carry the app's own id for it")
            @Size(max = 64) String clientEventId,

            @NotNull(message = "When the device recorded it") LocalDateTime capturedAt,

            @Pattern(regexp = "\\d{4,8}", message = "A handover code is 4 to 8 digits")
            String code,

            Double lat,
            Double lng,
            BigDecimal accuracy,
            @Size(max = 500) String photoUrl,
            @Size(max = 40) String reasonCode,
            @Size(max = 400) String note) {
    }
}
