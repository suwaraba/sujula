package com.sujula.service;

import com.sujula.model.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface NotificationService {
    Page<Notification> findByUser(Long userId, Pageable pageable);
    Page<Notification> findUnreadByUser(Long userId, Pageable pageable);
    long countUnread(Long userId);
    Notification markRead(Long notificationId, Long userId);
    void markAllRead(Long userId);
    Notification send(Long userId, String title, String message, String type, String referenceId);
}
