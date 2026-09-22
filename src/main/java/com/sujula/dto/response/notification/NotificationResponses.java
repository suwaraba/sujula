package com.sujula.dto.response.notification;

import java.time.LocalDateTime;
import java.util.List;

import com.sujula.model.constant.NotificationChannel;
import com.sujula.model.constant.NotificationEvent;

/**
 * What the user is shown about their own notifications.
 *
 * <p>No entity appears here, and on this surface that is not only convention: a
 * {@code PushDevice} serialised directly would publish the token that lets
 * anybody send that handset a message dressed as ours.
 */
public final class NotificationResponses {

    private NotificationResponses() {}

    // ── The inbox ────────────────────────────────────────────────────────────

    public record Item(Long id, NotificationEvent event, String group,
                       String title, String message, String referenceId,
                       boolean read,
                       /** Which channels this actually went out on when it was sent. */
                       List<String> sentOn,
                       LocalDateTime at) {}

    public record AllRead(int marked, String message) {}

    // ── The settings page ────────────────────────────────────────────────────

    /**
     * Every event on every channel, resolved.
     *
     * <p>{@code channels} is sent alongside so a client renders the columns it
     * is actually given rather than a hard-coded three — the day an SMS sender
     * exists, the page grows a column without being rebuilt.
     */
    public record Preferences(List<NotificationChannel> channels,
                              List<Group> groups,
                              int changed,
                              String note) {}

    /** One heading on the settings page, with its rows. */
    public record Group(String name, List<Row> events) {}

    public record Row(NotificationEvent event, String label, String description,
                      List<Setting> settings) {}

    /**
     * One switch.
     *
     * <p>{@code locked} carries the reason with it. A switch that silently does
     * nothing is worse than one that will not move and says why.
     */
    public record Setting(NotificationChannel channel, boolean enabled, boolean locked,
                          String lockedReason) {}

    // ── Devices ──────────────────────────────────────────────────────────────

    /**
     * A registered handset, without its token.
     *
     * <p>What is here is what somebody needs to recognise the old phone they want
     * to remove: what kind it is, what they called it, and when it was last used.
     */
    public record Device(Long id, String platform, String label,
                         LocalDateTime lastSeenAt, LocalDateTime registeredAt,
                         /** Whether push would actually reach it right now. */
                         boolean deliverable, String deliveryNote) {}

    public record DeviceRemoved(Long deviceId, String message) {}
}
