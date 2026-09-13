package com.sujula.dto.response.recipient;

import java.time.LocalDateTime;
import java.util.List;

/**
 * What the parcel page tells somebody who has nothing but a link.
 *
 * <p>The shapes here are bounded by who might be holding the code rather than by
 * who it was meant for. It travelled through a message that was forwarded, read
 * aloud, screenshotted and left open on a shared phone — so this page carries a
 * first name and a town, and no surname, no street, no phone number, no order
 * number, no prices and no seller. Somebody who finds the link learns that a
 * parcel is on its way to a woman called Fatou in Serrekunda, which is roughly
 * what they would learn by seeing the box.
 *
 * <p>The one deliberate exception is the counter: once a parcel is going to a
 * pickup point, its name, street and opening hours are on the page, because the
 * recipient cannot collect from an address she has not been given — and that
 * address is a shop on a main road that already advertises itself.
 */
public final class RecipientResponses {

    private RecipientResponses() {}

    /**
     * One parcel, as the person waiting for it sees it.
     *
     * <p>{@code youCan} is computed rather than assumed by the client: whether a
     * parcel can still be redirected depends on where it is, and a page that
     * offered the button anyway would be a page that takes a decision and then
     * refuses it.
     */
    public record Parcel(
            String trackingCode,
            String stage,
            String description,
            String forName,
            String destinationCity,
            String destinationCountry,
            int itemCount,
            LocalDateTime expectedFrom,
            LocalDateTime expectedUntil,
            LocalDateTime lastUpdatedAt,
            Counter waitingAt,
            Standing standing,
            Actions youCan,
            List<Step> history) {}

    /** A step in the journey, with nothing in it that names anybody. */
    public record Step(String stage, String description, LocalDateTime at) {}

    /**
     * The counter holding it, or the one it is headed for.
     *
     * <p>No shelf code. That is an operator's filing system, and a recipient who
     * quoted one at the counter would be telling them something they are supposed
     * to be telling her.
     */
    public record Counter(Long id, String name, String addressStreet, String city,
                          String openingHours, String contactPhone,
                          Double lat, Double lng,
                          LocalDateTime holdingUntil) {}

    /**
     * What she has already asked for, and is still in force.
     *
     * <p>Read back to her in her own words so she can see what the driver will
     * see. A safe-drop authorisation that says something other than what she
     * meant is worth catching before the parcel is left there.
     */
    public record Standing(
            Long chosenPickupPointId,
            String chosenPickupPointName,
            LocalDateTime windowFrom,
            LocalDateTime windowUntil,
            boolean safeDropAuthorised,
            String safeDropLocation,
            String safeDropPerson,
            LocalDateTime lastChangedAt) {}

    /**
     * Which of the three instructions this parcel can still take.
     *
     * <p>Each false one comes with the reason, because "you cannot do this" with
     * no explanation is what makes somebody telephone support.
     */
    public record Actions(boolean choosePickupPoint, boolean reschedule,
                          boolean authoriseSafeDrop, String why) {}

    /**
     * The answer to asking for a code.
     *
     * <p>Never contains the code, and never contains the address in full. It says
     * that something was sent and roughly where, which is enough for the person
     * waiting to know whether to go and look — and useless to somebody who found
     * the tracking link and is fishing for the contact behind it.
     */
    public record CodeSent(
            boolean sent,
            String sentTo,
            LocalDateTime expiresAt,
            int requestsLeftThisHour,
            String message) {}

    /** What changed, and what the driver will now do. */
    public record InstructionRecorded(
            String trackingCode,
            String instruction,
            LocalDateTime recordedAt,
            Standing standing,
            String message) {}
}
