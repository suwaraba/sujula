package com.sujula.service.notification;

import java.util.List;

import com.sujula.model.notification.PushDevice;

/**
 * What actually sends a push notification to a handset.
 *
 * <p>An interface with one implementation, and the interface earns its place:
 * sending push requires a provider and credentials this deployment may not have,
 * and the thing that must not happen is the rest of the system behaving
 * differently depending on whether it does. Registering a device, resolving who
 * would be sent to, and honouring the preference all work identically either
 * way; only the last hop changes.
 *
 * <p>Same shape as the geocoder, which degrades without an API key rather than
 * failing or pretending.
 */
public interface PushSender {

    /**
     * Sends one notification to every device given.
     *
     * <p>Must not throw. A push that cannot be delivered is not a reason for the
     * thing that prompted it to fail — the inbox row is already written and is
     * the record.
     *
     * @return how many devices it reached
     */
    int send(List<PushDevice> devices, String title, String body, String referenceId);

    /** Whether a provider is actually configured. Reported, never guessed at. */
    boolean isConfigured();
}
