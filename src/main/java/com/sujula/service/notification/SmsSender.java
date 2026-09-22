package com.sujula.service.notification;

/**
 * What actually puts a text message on somebody's phone.
 *
 * <p>The channel C5 is written around: the recipient in Serrekunda has a phone
 * number and nothing else, and an SMS she reads out to the driver is the one
 * thing that reaches her without the payer in the loop.
 *
 * <p>Same seam as {@link PushSender}: exactly one implementation is registered,
 * chosen by whether a provider is configured, and everything upstream — who is
 * sent what, and when — runs identically either way.
 *
 * <p>Deliberately not a {@code NotificationChannel}. What goes out here is a
 * code somebody needs in order to finish something — a phone verification, a
 * parcel release — not a preference-governed notice, so nobody can switch it off
 * and then stand at a door with nothing to read out.
 */
public interface SmsSender {

    /**
     * Sends one message to one number.
     *
     * <p>Must not throw. A text that could not be sent is not a reason for what
     * prompted it to fail; the caller reports the outcome and keeps its other
     * route.
     *
     * @param toPhone E.164, e.g. {@code +2207012345}
     * @return true when the provider accepted it
     */
    boolean send(String toPhone, String body);

    /** Whether a provider is actually configured. Reported, never guessed at. */
    boolean isConfigured();
}
