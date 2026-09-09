package com.sujula.service.impl;

import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.Notification;
import com.sujula.model.user.User;
import com.sujula.repository.NotificationRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    /** Bucket for a notification sent without one, so the inbox can always group by type. */
    private static final String DEFAULT_TYPE = "GENERAL";

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

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
    public Notification send(Long userId, String title, String message, String type, String referenceId) {
        if (userId == null) {
            throw new BadRequestException("A notification needs a recipient");
        }
        if (title == null || title.isBlank()) {
            throw new BadRequestException("A notification needs a title");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        Notification saved = notificationRepository.save(Notification.builder()
                .user(user)
                .title(title.trim())
                .message(message != null ? message.trim() : "")
                .type(type != null && !type.isBlank() ? type.trim().toUpperCase() : DEFAULT_TYPE)
                .referenceId(referenceId)
                .read(false)
                .build());

        log.debug("[Notification] {} → user {} ({})", saved.getType(), userId, referenceId);
        return saved;
    }
}
