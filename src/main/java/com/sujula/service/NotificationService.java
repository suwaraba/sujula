package com.sujula.service;

import com.sujula.model.Notification;
import com.sujula.model.constant.NotificationEvent;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Telling somebody that something happened.
 *
 * <p><strong>One writer.</strong> {@link #send} is the only thing in the system
 * that tells a user anything, and it is where the preference matrix is
 * consulted and where the channels fan out from. That is deliberate: a second
 * path that wrote an inbox row directly would be a path that ignored somebody's
 * settings, and the settings page would be decorative for exactly the events
 * that path handled.
 *
 * <p>Notifications are a side channel and never the record itself — the order,
 * the payment and the custody chain each keep their own state, and this only
 * says that state moved. Which is why {@code send} runs in its own transaction:
 * a checkout that cannot write a notification is still a valid checkout.
 */
public interface NotificationService {

    Page<Notification> findByUser(Long userId, Pageable pageable);

    Page<Notification> findUnreadByUser(Long userId, Pageable pageable);

    long countUnread(Long userId);

    Notification markRead(Long notificationId, Long userId);

    void markAllRead(Long userId);

    /**
     * Tells a user something, on whichever channels they have left switched on.
     *
     * <p>Returns the inbox row where one was written and null where it was not —
     * which happens for an optional event the user has switched off everywhere.
     * Callers treat it as best-effort and none of them read the return value for
     * anything but logging.
     */
    Notification send(Long userId, String title, String message, NotificationEvent event,
                      String referenceId);
}
