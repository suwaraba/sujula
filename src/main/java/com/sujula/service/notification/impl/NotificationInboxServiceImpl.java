package com.sujula.service.notification.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.dto.request.notification.NotificationRequests;
import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.notification.NotificationResponses;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.Notification;
import com.sujula.model.constant.NotificationChannel;
import com.sujula.model.constant.NotificationEvent;
import com.sujula.model.notification.PushDevice;
import com.sujula.model.user.User;
import com.sujula.repository.NotificationRepository;
import com.sujula.repository.notification.PushDeviceRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.NotificationService;
import com.sujula.service.notification.NotificationInboxService;
import com.sujula.service.notification.NotificationPreferences;
import com.sujula.service.notification.PushSender;

import lombok.extern.slf4j.Slf4j;

/**
 * The inbox, the settings and the phones.
 *
 * <p>Nothing here takes a user id from the request. Every method resolves the
 * caller from the session, so there is no parameter to tamper with — which is a
 * stronger guarantee than a check, because a check can be missing from the one
 * endpoint that matters and a parameter that does not exist cannot be changed on
 * any of them.
 */
@Slf4j
@Service
public class NotificationInboxServiceImpl implements NotificationInboxService {

    private final NotificationService notifications;
    private final NotificationRepository rows;
    private final NotificationPreferences preferences;
    private final PushDeviceRepository devices;
    private final PushSender push;
    private final UserRepository users;

    public NotificationInboxServiceImpl(NotificationService notifications,
                                        NotificationRepository rows,
                                        NotificationPreferences preferences,
                                        PushDeviceRepository devices, PushSender push,
                                        UserRepository users) {
        this.notifications = notifications;
        this.rows = rows;
        this.preferences = preferences;
        this.devices = devices;
        this.push = push;
        this.users = users;
    }

    // ── The inbox ────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public PagedResponse<NotificationResponses.Item> inbox(Long userId, boolean unreadOnly,
                                                           Pageable pageable) {
        Page<Notification> page = unreadOnly
                ? notifications.findUnreadByUser(userId, pageable)
                : notifications.findByUser(userId, pageable);
        return PagedResponse.of(page.map(NotificationInboxServiceImpl::itemOf));
    }

    @Override
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public NotificationResponses.Item markRead(Long userId, Long notificationId) {
        return itemOf(notifications.markRead(notificationId, userId));
    }

    @Override
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public NotificationResponses.AllRead markAllRead(Long userId) {
        // Counted before, because the update returns rows touched and the user
        // is being told how many things they just dismissed unread.
        int unread = (int) notifications.countUnread(userId);
        notifications.markAllRead(userId);
        return new NotificationResponses.AllRead(unread,
                unread == 0 ? "Nothing was unread."
                        : unread + (unread == 1 ? " notification" : " notifications")
                          + " marked as read.");
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public NotificationResponses.Preferences preferences(Long userId) {
        return matrixFor(userId, 0);
    }

    @Override
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public NotificationResponses.Preferences updatePreferences(
            Long userId, NotificationRequests.UpdatePreferences request) {
        User user = users.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        int changed = 0;
        for (NotificationRequests.Switch change : request.changes()) {
            // A locked switch is ignored rather than refused: a client sending
            // back a screenful should not fail wholesale because one row in it
            // cannot move. The response says how many actually did.
            if (preferences.set(user, change.event(), change.channel(), change.enabled())) {
                changed++;
            }
        }
        log.info("[Notifications] user {} changed {} of {} settings",
                userId, changed, request.changes().size());
        return matrixFor(userId, changed);
    }

    /**
     * The settings page, grouped the way it is read.
     *
     * <p>Built here rather than by the client so the groups, the wording and the
     * locks come from the enums — which is where the rules are. A client that
     * decided its own grouping would drift the moment an event was added.
     */
    private NotificationResponses.Preferences matrixFor(Long userId, int changed) {
        Map<NotificationEvent, Map<NotificationChannel, Boolean>> resolved =
                preferences.resolveAll(userId);

        Map<String, List<NotificationResponses.Row>> grouped = new LinkedHashMap<>();
        for (NotificationEvent event : NotificationEvent.values()) {
            List<NotificationResponses.Setting> settings = new ArrayList<>();
            for (NotificationChannel channel : NotificationChannel.values()) {
                boolean locked = event.isMandatory();
                settings.add(new NotificationResponses.Setting(
                        channel,
                        Boolean.TRUE.equals(resolved.get(event).get(channel)),
                        locked,
                        locked ? lockedReason(event) : null));
            }
            grouped.computeIfAbsent(event.group(), key -> new ArrayList<>())
                    .add(new NotificationResponses.Row(event, label(event), describe(event),
                            settings));
        }

        List<NotificationResponses.Group> groups = new ArrayList<>();
        grouped.forEach((name, events) ->
                groups.add(new NotificationResponses.Group(name, events)));

        return new NotificationResponses.Preferences(
                List.of(NotificationChannel.values()), groups, changed,
                push.isConfigured() ? null
                        // Said rather than left to be discovered. A switch that
                        // is on and reaches nothing is worse than one that is
                        // off, because the user believes they are covered.
                        : "Push notifications are not switched on for this installation yet, so "
                          + "those settings will not reach your phone until they are. Everything "
                          + "still arrives in your inbox.");
    }

    /**
     * Why a switch will not move, said in terms of what it would cost.
     *
     * <p>"This setting is locked" tells somebody nothing. What they need is the
     * reason, because the reason is usually one they agree with.
     */
    private static String lockedReason(NotificationEvent event) {
        return switch (event) {
            case PARCEL_CODE -> "This is the code that opens your parcel. Without it nobody can "
                    + "collect it.";
            case DISPUTE_UPDATE -> "Money is held still while a dispute is open. You would find "
                    + "out from your balance instead.";
            case PAYOUT_FAILED -> "A payout that bounced needs you to do something, and this is "
                    + "the only place you would learn to.";
            case SECURITY_ALERT -> "The message warning you that somebody else is in your account "
                    + "is not one they should be able to switch off.";
            case REFUND_ISSUED -> "Money moving back to you is always confirmed in writing.";
            default -> "Always sent.";
        };
    }

    // ── Devices ──────────────────────────────────────────────────────────────

    @Override
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public NotificationResponses.Device registerDevice(
            Long userId, NotificationRequests.RegisterDevice request) {
        User user = users.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        LocalDateTime now = LocalDateTime.now();
        String token = request.token().trim();

        PushDevice mine = null;
        for (PushDevice existing : devices.findLiveByToken(token)) {
            boolean theirs = existing.getUser() != null
                    && userId.equals(existing.getUser().getId());
            if (theirs) {
                mine = existing;
                continue;
            }
            // The same handset under somebody else's account. Operating systems
            // reassign tokens, so this means the phone changed hands or the app
            // was reinstalled — and leaving the old registration live would send
            // the previous owner's parcel codes to whoever has it now.
            existing.setRevokedAt(now);
            existing.setRevokedReason("Token re-registered by another account");
            devices.save(existing);
            log.info("[Push] device {} revoked — its token was re-registered by user {}",
                    existing.getId(), userId);
        }

        if (mine == null) {
            mine = PushDevice.builder()
                    .user(user).token(token)
                    .platform(request.platform().toUpperCase(Locale.ROOT))
                    .label(request.label())
                    .lastSeenAt(now)
                    .build();
        } else {
            // The ordinary case: the app re-registers on every start. Refreshed
            // rather than duplicated, or a user ends up with forty of one phone.
            mine.setPlatform(request.platform().toUpperCase(Locale.ROOT));
            if (request.label() != null && !request.label().isBlank()) {
                mine.setLabel(request.label());
            }
            mine.setLastSeenAt(now);
        }
        return deviceOf(devices.save(mine));
    }

    @Override
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public NotificationResponses.DeviceRemoved removeDevice(Long userId, Long deviceId) {
        PushDevice device = devices.findByIdAndUserId(deviceId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Device", deviceId));

        if (device.getRevokedAt() == null) {
            // Soft, so "this phone was removed on the 4th" survives. A stolen
            // handset is the case that makes that worth keeping.
            device.setRevokedAt(LocalDateTime.now());
            device.setRevokedReason("Removed by the user");
            devices.save(device);
        }
        return new NotificationResponses.DeviceRemoved(deviceId,
                "That device will not be sent notifications any more. If it was lost or stolen, "
                        + "change your password as well — removing it here does not sign it out.");
    }

    // ── Views ────────────────────────────────────────────────────────────────

    private static NotificationResponses.Item itemOf(Notification row) {
        NotificationEvent event = row.getEvent() == null
                ? NotificationEvent.GENERAL : row.getEvent();
        return new NotificationResponses.Item(
                row.getId(), event, event.group(),
                row.getTitle(), row.getMessage(), row.getReferenceId(),
                row.isRead(),
                row.getSentOn() == null || row.getSentOn().isBlank()
                        ? List.of() : Arrays.asList(row.getSentOn().split(",")),
                row.getCreatedAt());
    }

    private NotificationResponses.Device deviceOf(PushDevice device) {
        boolean deliverable = device.isLive() && push.isConfigured();
        return new NotificationResponses.Device(
                device.getId(), device.getPlatform(), device.getLabel(),
                device.getLastSeenAt(), device.getCreatedAt(),
                deliverable,
                deliverable ? null
                        : device.isLive()
                          ? "Registered. Push is not switched on for this installation yet, so "
                            + "nothing will reach it until it is."
                          : "Removed.");
    }

    // ── Wording ──────────────────────────────────────────────────────────────

    private static String label(NotificationEvent event) {
        return switch (event) {
            case ORDER_PLACED -> "Order placed";
            case ORDER_UPDATE -> "Order updates";
            case ORDER_CANCELLED -> "An order was cancelled";
            case REFUND_ISSUED -> "Refunds";
            case PARCEL_COLLECTED -> "Collected from the shop";
            case PARCEL_OUT_FOR_DELIVERY -> "Out for delivery";
            case PARCEL_ATTEMPT_FAILED -> "A delivery attempt failed";
            case PARCEL_AT_PICKUP_POINT -> "Ready to collect";
            case PARCEL_CODE -> "Delivery and collection codes";
            case PARCEL_DELIVERED -> "Delivered";
            case RETURN_UPDATE -> "Returns";
            case DISPUTE_UPDATE -> "Disputes";
            case MESSAGE_RECEIVED -> "Messages";
            case REVIEW_REPLY -> "Replies to your reviews";
            case SALE_MADE -> "You made a sale";
            case ORDER_TO_FULFIL -> "Something to pack";
            case PAYOUT_SENT -> "Payouts";
            case PAYOUT_FAILED -> "A payout failed";
            case LOW_STOCK -> "Running low on stock";
            case DELIVERY_OFFERED -> "A delivery job is offered";
            case PICKUP_PARCEL_ARRIVED -> "A parcel arrived at your counter";
            case PICKUP_PARCEL_OVERDUE -> "A parcel is overdue on your shelf";
            case SECURITY_ALERT -> "Security warnings";
            case ACCOUNT_UPDATE -> "Your account";
            case PROMOTION -> "Offers";
            case GENERAL -> "Everything else";
        };
    }

    private static String describe(NotificationEvent event) {
        return switch (event) {
            case PARCEL_CODE -> "The six digits somebody reads out to collect a parcel. Always "
                    + "sent — a parcel with no code is one nobody can hand over.";
            case DISPUTE_UPDATE -> "Anything that moves on a dispute, including money being held.";
            case PAYOUT_FAILED -> "A transfer your bank or mobile money provider sent back.";
            case SECURITY_ALERT -> "A sign-in from somewhere new, or a password change.";
            case REFUND_ISSUED -> "Money on its way back to you.";
            case PROMOTION -> "Offers and campaigns. Off unless you switch it on.";
            case MESSAGE_RECEIVED -> "When a buyer or a seller writes to you about an order.";
            case PARCEL_ATTEMPT_FAILED -> "When nobody was there and the driver will try again.";
            default -> null;
        };
    }
}
