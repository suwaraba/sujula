package com.sujula.dto.request.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** What an administrator sends when moving parcels and orders by hand. */
public final class AdminDispatchRequests {

    private AdminDispatchRequests() {}

    // ── Orders ───────────────────────────────────────────────────────────────

    /**
     * Cancelling an order over the top of whatever it was doing.
     *
     * <p>Per vendor order where one is named, whole order otherwise. C3 is in
     * the shape: cancelling one seller's slice must not touch another's, and an
     * administrator who meant one and got all three would have refunded two
     * sellers who did nothing wrong.
     */
    public record ForceCancelOrder(
            /** One seller's part, or null for the whole payment. */
            Long vendorOrderId,

            @NotBlank(message = "Say why — the buyer and the sellers are both told")
            @Size(max = 1000) String reason,

            /** Whether to raise the refund as well. Almost always yes. */
            Boolean refund) {}

    /**
     * Setting a status by hand.
     *
     * <p>Break-glass, and it reads as one. Every other status on this platform
     * is a consequence of something that happened; this is the escape hatch for
     * when the thing that happened cannot be recorded — a driver's phone in the
     * river, a counter that burned down.
     */
    public record ForceStatus(
            @NotNull(message = "Which status") com.sujula.model.constant.VendorOrderStatus status,

            @NotBlank(message = "Say what actually happened. This is the only record there will be")
            @Size(min = 20, max = 2000,
                  message = "Say what happened in more than a few words — this note is the only "
                          + "evidence this status was ever justified")
            String note) {}

    /** Placing an order for somebody who telephoned. */
    public record PlaceOrderOnBehalf(
            @NotNull(message = "Which buyer") Long customerId,

            @NotBlank(message = "Say why you are placing it for them")
            @Size(max = 500) String reason,

            @NotNull(message = "What they want")
            @Size(min = 1, max = 50) java.util.List<@jakarta.validation.Valid Line> lines,

            @NotNull(message = "Where it goes") Long deliveryAddressId,

            @Size(max = 500) String notes) {}

    public record Line(
            @NotNull Long productId,
            Long variantId,
            @NotNull @Min(value = 1, message = "At least one") Integer quantity) {}

    // ── Shipments ────────────────────────────────────────────────────────────

    /**
     * Offering a parcel to a named driver.
     *
     * <p>An offer rather than an assignment, and the word matters: the driver
     * accepts or it lapses. A platform that could put a job on somebody's screen
     * and call it theirs would be one where a driver who was asleep is
     * accountable for a parcel.
     */
    public record AssignShipment(
            @NotNull(message = "Which driver") Long driverId,

            /** How long they have to answer before it goes back to the queue. */
            @Min(value = 5, message = "Give them at least five minutes")
            @Max(value = 240, message = "Four hours is the most — after that it is holding the "
                    + "parcel out of circulation")
            Integer acceptanceMinutes,

            @Size(max = 500) String note) {}

    public record UnassignShipment(
            @NotBlank(message = "Say why — the driver is told, and their score is not touched "
                    + "for a job taken off them")
            @Size(max = 500) String reason) {}

    public record ReassignShipment(
            @NotNull(message = "Which driver instead") Long driverId,

            @NotBlank(message = "Say why") @Size(max = 500) String reason,

            @Min(5) @Max(240) Integer acceptanceMinutes) {}

    /**
     * Recording a handover that could not be proven the ordinary way.
     *
     * <p>The C4 escape hatch and the most sensitive thing on this surface: it is
     * the one way a parcel reaches DELIVERED without anybody having presented a
     * code. Step-up, a mandatory note, and its own audit action — so "how often
     * does this happen, and to whose parcels" is answerable.
     */
    public record OverrideHandoff(
            @NotNull(message = "Which link of the chain")
            com.sujula.model.constant.CustodyEventType eventType,

            @NotBlank(message = "Confirm your own password") String password,

            @Pattern(regexp = "^[0-9]{6}$", message = "Your authenticator code is six digits")
            String totpCode,

            @NotBlank(message = "Say exactly what happened and how you know it")
            @Size(min = 30, max = 2000,
                  message = "This note replaces the code somebody would have read out. Say what "
                          + "happened and how you know it, in more than a line")
            String note,

            /** Who says so: a driver, an operator, the recipient on the telephone. */
            @NotBlank(message = "Say who told you") @Size(max = 200) String attestedBy,

            Double lat,
            Double lng) {}

    public record CancelShipment(
            @NotBlank(message = "Say why") @Size(max = 500) String reason) {}
}
