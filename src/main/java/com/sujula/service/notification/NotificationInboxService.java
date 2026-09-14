package com.sujula.service.notification;

import org.springframework.data.domain.Pageable;

import com.sujula.dto.request.notification.NotificationRequests;
import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.notification.NotificationResponses;

/**
 * The signed-in user's own inbox, settings and phones.
 *
 * <p>Every method takes the caller's id from the session and nothing from the
 * request. There is no user id in any path here, so there is nothing to change
 * to reach somebody else's inbox.
 *
 * <p>The conversion happens here rather than in the controller: no entity
 * crosses the boundary, which matters more on this surface than most. A
 * {@code PushDevice} serialised directly would publish the token that lets
 * anybody send that handset a message.
 */
public interface NotificationInboxService {

    /** What the user has been told, newest first. */
    PagedResponse<NotificationResponses.Item> inbox(Long userId, boolean unreadOnly,
                                                    Pageable pageable);

    /** Marks one read. Already-read is not an error — the inbox retries. */
    NotificationResponses.Item markRead(Long userId, Long notificationId);

    /** Marks everything read, and says how many that was. */
    NotificationResponses.AllRead markAllRead(Long userId);

    /**
     * The whole matrix, resolved, with the locked switches marked.
     *
     * <p>Every event on every channel rather than the stored overrides: a user
     * who has never opened this page has no rows at all, and a settings screen
     * showing nothing would be a settings screen that looks broken.
     */
    NotificationResponses.Preferences preferences(Long userId);

    /** Records the switches that were changed, and says which actually moved. */
    NotificationResponses.Preferences updatePreferences(
            Long userId, NotificationRequests.UpdatePreferences request);

    /**
     * Registers a handset, or refreshes one already registered.
     *
     * <p>Re-registering the same token is the ordinary case — the app does it on
     * every start — so this updates rather than duplicating. A token that was
     * last under another account is revoked there first: operating systems
     * reassign tokens, and the next owner of a phone must not be sent the
     * previous owner's delivery codes.
     */
    NotificationResponses.Device registerDevice(Long userId,
                                                NotificationRequests.RegisterDevice request);

    /** Stops sending to a handset. Soft, so "removed on the 4th" survives. */
    NotificationResponses.DeviceRemoved removeDevice(Long userId, Long deviceId);
}
