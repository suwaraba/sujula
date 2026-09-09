package com.sujula.controller;

import com.sujula.dto.response.PagedResponse;
import com.sujula.model.Notification;
import com.sujula.model.user.User;
import com.sujula.service.NotificationService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The signed-in user's own inbox.
 *
 * <p>Every endpoint is scoped to the caller — there is no path to another
 * user's notifications, and marking one read is checked against ownership in
 * the service rather than trusted from the URL.
 */
@RestController
@RequestMapping("/api/user/notifications")
@PreAuthorize("isAuthenticated()")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public ResponseEntity<PagedResponse<Notification>> inbox(
            Authentication authentication,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = currentUserId(authentication);
        PageRequest pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(PagedResponse.of(unreadOnly
                ? notificationService.findUnreadByUser(userId, pageable)
                : notificationService.findByUser(userId, pageable)));
    }

    /** Drives the badge in the header, so it stays a count rather than a page of rows. */
    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> unreadCount(Authentication authentication) {
        return ResponseEntity.ok(Map.of("unread",
                notificationService.countUnread(currentUserId(authentication))));
    }

    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<Notification> markRead(Authentication authentication,
                                                 @PathVariable Long notificationId) {
        return ResponseEntity.ok(
                notificationService.markRead(notificationId, currentUserId(authentication)));
    }

    @PatchMapping("/read")
    public ResponseEntity<Void> markAllRead(Authentication authentication) {
        notificationService.markAllRead(currentUserId(authentication));
        return ResponseEntity.noContent().build();
    }

    private Long currentUserId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof User user && user.getId() != null) {
            return user.getId();
        }
        throw new AccessDeniedException("Authentication is required");
    }
}
