package com.sujula.dto.request.admin;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.sujula.model.constant.CallbackOutcome;
import com.sujula.model.constant.DisputeOutcome;
import com.sujula.model.constant.NotificationEvent;
import com.sujula.model.constant.UserRole;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Deciding disputes, telling people things, and turning switches.
 *
 * <p>Two rules show up repeatedly. A dispute resolution names one seller's
 * sub-order, because a dispute is about one seller's goods and a payment shared
 * between several has no proportion that belongs to anybody (C3). And a
 * callback carries a telephone number rather than a user id, because the person
 * who most needs a call may have a phone and nothing else (C5).
 */
public final class AdminPlatformRequests {

    private AdminPlatformRequests() {}

    // ── Disputes ─────────────────────────────────────────────────────────────

    public record AssignDispute(
            /** Null takes it yourself, which is the ordinary case. */
            Long assigneeUserId,

            @Size(max = 500) String note) {}

    public record AddNote(
            @NotBlank(message = "Say something") @Size(max = 4000) String body) {}

    /**
     * Deciding a dispute, and moving the money that follows.
     *
     * <p>The amount is in the seller's own currency, not the buyer's. That is
     * the side the ledger is denominated in, and asking for it in the buyer's
     * would mean converting here at today's rate — the dispute carries the rate
     * the order was placed at, and that is the one that applies (C2).
     */
    public record ResolveDispute(
            @NotNull DisputeOutcome outcome,

            /**
             * Required for SPLIT, refused otherwise.
             *
             * <p>FOR_BUYER and FOR_VENDOR already say what happens to the whole
             * amount; accepting a figure alongside them would let the two
             * disagree, and somebody would eventually trust the wrong one.
             */
            @DecimalMin(value = "0.00") BigDecimal awardedToBuyerNative,

            /**
             * Whether the buyer has to send the goods back before the money moves.
             *
             * <p>A separate flag rather than a fifth outcome, because it is a
             * condition on an outcome rather than an outcome: "for the buyer,
             * once she returns it" and "for the buyer" are the same decision with
             * different timing.
             */
            boolean requireReturn,

            @NotBlank(message = "Say why — both parties are shown this")
            @Size(max = 2000) String resolutionNote,

            @NotBlank(message = "Your password — this moves money")
            String password,
            String totpCode) {}

    public record RequestCallback(
            /**
             * The number to ring.
             *
             * <p>Null takes the one on the shipment, which is usually right: the
             * person who needs the call is often the recipient, who has no
             * account at all.
             */
            @Size(max = 30) String phone,

            @Size(max = 120) String contactName,

            /** Wolof, Mandinka, French, English. A caller who opens wrong loses the call. */
            @Size(max = 40) String preferredLanguage,

            @NotBlank(message = "Say what the call is for") @Size(max = 500) String reason,

            /** When it should happen by. Null uses the platform's own window. */
            LocalDateTime callBy) {}

    public record RecordCallback(
            @NotNull CallbackOutcome outcome,

            @Size(max = 2000) String notes,

            /** When they asked to be rung back, for a RESCHEDULED outcome. */
            LocalDateTime callBy) {}

    // ── Comms ────────────────────────────────────────────────────────────────

    public record Announce(
            @NotBlank @Size(max = 200) String title,

            @NotBlank @Size(max = 4000) String body,

            /** Null reaches every role. */
            UserRole audienceRole,

            /**
             * The recipients' OWN country — where a seller trades, where a driver
             * carries. Null reaches everywhere.
             */
            @Pattern(regexp = "^[A-Za-z]{2}$") String countryCode,

            /**
             * Which event this counts as, so people's own settings are honoured.
             *
             * <p>Defaults to PLATFORM_NOTICE. Sending an announcement as a
             * PROMOTION is how a seller who switched marketing off becomes the
             * last to know the platform is closed on Koriteh.
             */
            NotificationEvent event) {}

    public record SendNotification(
            @NotNull(message = "Who") Long userId,

            @NotBlank @Size(max = 200) String title,

            @NotBlank @Size(max = 2000) String message,

            @NotNull NotificationEvent event,

            /** What it is about — an order number, a dispute reference. */
            @Size(max = 100) String referenceId) {}

    // ── Platform ─────────────────────────────────────────────────────────────

    public record SetFeatureFlag(
            @NotNull Boolean enabled,

            @NotBlank(message = "Say why — a flag with no reason is one nobody dares turn back on")
            @Size(max = 500) String reason,

            /** Leave null to keep whatever it is. Most flags are not client-visible. */
            Boolean clientVisible) {}

    public record TriggerJob(
            @Size(max = 300) String reason) {}
}
