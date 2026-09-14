package com.sujula.service.impl;

import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.Notification;
import com.sujula.model.constant.NotificationChannel;
import com.sujula.model.constant.NotificationEvent;
import com.sujula.model.notification.PushDevice;
import com.sujula.model.user.User;
import com.sujula.repository.NotificationRepository;
import com.sujula.repository.notification.PushDeviceRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.EmailService;
import com.sujula.service.NotificationService;
import com.sujula.service.notification.NotificationPreferences;
import com.sujula.service.notification.PushSender;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * The in-app inbox: one row per thing a user is told about.
 *
 * <p>Notifications are a side channel, never the record itself — the order, the
 * payment and the delivery each keep their own state, and this only tells
 * someone that state moved. That is why {@link #send} runs in its own
 * transaction: a checkout that cannot write a notification is still a valid
 * checkout, and an inbox failure must not roll back the money or the stock
 * deduction that prompted it.
 *
 * <p>Only account holders have an inbox. Guests are reached by email instead,
 * which is why the order flows call this only when a customer is attached.
 */
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationServiceImpl.class);

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final NotificationPreferences preferences;
    private final PushDeviceRepository devices;
    private final PushSender push;
    private final EmailService email;

    @Override
    @Transactional(readOnly = true)
    public Page<Notification> findByUser(Long userId, Pageable pageable) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Notification> findUnreadByUser(Long userId, Pageable pageable) {
        return notificationRepository.findByUserIdAndReadFalseOrderByCreatedAtDesc(userId, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public long countUnread(Long userId) {
        return notificationRepository.countByUserIdAndReadFalse(userId);
    }

    @Override
    @Transactional
    public Notification markRead(Long notificationId, Long userId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification", notificationId));

        // Reading the id off the lazy user proxy does not load the row.
        if (notification.getUser() == null || !notification.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Notification", notificationId);
        }
        if (notification.isRead()) {
            return notification;   // idempotent — the inbox may retry
        }
        notification.setRead(true);
        return notificationRepository.save(notification);
    }

    @Override
    @Transactional
    public void markAllRead(Long userId) {
        notificationRepository.markAllReadByUserId(userId);
    }

    /**
     * Files a notification for a user.
     *
     * <p>REQUIRES_NEW on purpose: callers treat this as best-effort and catch
     * whatever it throws, but an exception inside their transaction would still
     * mark it rollback-only and take the order down with it. The cost is that a
     * notification survives a caller that later rolls back — a stray inbox line
     * is a far smaller problem than a lost order. It is safe here because
     * {@code referenceId} is a plain order number rather than a foreign key: the
     * separate transaction never has to see a row the caller has not committed.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Notification send(Long userId, String title, String message, NotificationEvent event,
                             String referenceId) {
        if (userId == null) {
            throw new BadRequestException("A notification needs a recipient");
        }
        if (title == null || title.isBlank()) {
            throw new BadRequestException("A notification needs a title");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        NotificationEvent kind = event != null ? event : NotificationEvent.GENERAL;
        String body = message != null ? message.trim() : "";

        List<String> sentOn = new ArrayList<>();
        Notification saved = null;

        // The inbox first, and on its own terms. It is the record rather than a
        // message: written whenever the user has not switched it off, and always
        // for an event that matters, so somebody who turned every other channel
        // off can still find out what happened.
        if (preferences.isEnabled(userId, kind, NotificationChannel.IN_APP)) {
            saved = notificationRepository.save(Notification.builder()
                    .user(user)
                    .title(title.trim())
                    .message(body)
                    .event(kind)
                    .referenceId(referenceId)
                    .read(false)
                    .build());
            sentOn.add(NotificationChannel.IN_APP.name());
        }

        if (preferences.isEnabled(userId, kind, NotificationChannel.EMAIL)
                && user.getEmail() != null) {
            try {
                email.sendNotificationEmail(user.getEmail(), user.getFirstName(),
                        title.trim(), body, referenceId);
                sentOn.add(NotificationChannel.EMAIL.name());
            } catch (RuntimeException failed) {
                // The inbox row is already the record. A send that failed is a
                // support problem, not a reason to lose the notification.
                log.warn("[Notification] Could not email user {} about {}: {}",
                        userId, kind, failed.toString());
            }
        }

        if (preferences.isEnabled(userId, kind, NotificationChannel.PUSH)) {
            List<PushDevice> handsets = devices.findLiveForUser(userId);
            int reached = push.send(handsets, title.trim(), body, referenceId);
            if (reached > 0) {
                sentOn.add(NotificationChannel.PUSH.name());
            }
        }

        if (saved != null) {
            // What actually went out, on the row. "Did he get the email" is the
            // first thing support asks, and answering it from today's
            // preferences would be answering a different question.
            saved.setSentOn(sentOn.isEmpty() ? null : String.join(",", sentOn));
            saved = notificationRepository.save(saved);
        }

        log.debug("[Notification] {} → user {} ({}) via {}", kind, userId, referenceId,
                sentOn.isEmpty() ? "nothing — switched off" : String.join(",", sentOn));
        return saved;
    }
}
