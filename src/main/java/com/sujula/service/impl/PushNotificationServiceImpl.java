package com.sujula.service.impl;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Service
@RequiredArgsConstructor
@Slf4j
public class PushNotificationServiceImpl implements PushNotificationService {

    private static final String EXPO_PUSH_URL = "https://exp.host/--/api/v2/push/send";

    private final DeviceTokenRepository deviceTokenRepository;
    private final RestTemplate          restTemplate;

    // ── Public API ────────────────────────────────────────────────────────────

    @Override
    @Async
    @Transactional(readOnly = true)
    public void sendToUser(Long userId, String title, String body, Map<String, String> data) {
        List<DeviceToken> tokens = deviceTokenRepository.findByUserIdAndActive(userId, true);
        if (tokens.isEmpty()) {
            log.debug("[Push] No active tokens for userId={}", userId);
            return;
        }
        tokens.forEach(dt -> sendPushMessage(dt.getToken(), title, body, data));
    }

    @Override
    @Async
    public void sendToTopic(String topic, String title, String body) {
        try {
            if (!isFirebaseAvailable()) {
                log.debug("[Push] Firebase not configured — skipping topic push to '{}'", topic);
                return;
            }
            Message message = Message.builder()
                    .setTopic(topic)
                    .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                    .build();
            String response = FirebaseMessaging.getInstance().send(message);
            log.debug("[Push] Topic '{}' sent: {}", topic, response);
        } catch (Exception e) {
            log.warn("[Push] Topic push failed for '{}': {}", topic, e.getMessage());
        }
    }

    @Override
    @Transactional
    public void registerDevice(Long userId, String token, DevicePlatform platform) {
        deviceTokenRepository.findByToken(token).ifPresentOrElse(
                existing -> {
                    existing.setUserId(userId);
                    existing.setActive(true);
                    deviceTokenRepository.save(existing);
                    log.debug("[Push] Updated token for userId={}", userId);
                },
                () -> {
                    deviceTokenRepository.save(DeviceToken.builder()
                            .userId(userId)
                            .token(token)
                            .platform(platform)
                            .active(true)
                            .build());
                    log.debug("[Push] Registered new token for userId={}", userId);
                }
        );
    }

    @Override
    @Transactional
    public void deactivateDevice(Long userId, String token) {
        deviceTokenRepository.deactivateByToken(token);
        log.debug("[Push] Deactivated token for userId={}", userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeviceTokenResponse> getUserDevices(Long userId) {
        return deviceTokenRepository.findByUserIdAndActive(userId, true)
                .stream()
                .map(DeviceTokenResponse::from)
                .toList();
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    private void sendPushMessage(String token, String title, String body,
                                  Map<String, String> data) {
        if (isExpoToken(token)) {
            sendViaExpo(token, title, body, data);
        } else {
            sendViaFirebase(token, title, body, data);
        }
    }

    /** Expo push tokens always start with "ExponentPushToken[". */
    private boolean isExpoToken(String token) {
        return token != null && token.startsWith("ExponentPushToken[");
    }

    // ── Expo Push API ─────────────────────────────────────────────────────────

    /**
     * Calls the Expo Push API.
     * Docs: https://docs.expo.dev/push-notifications/sending-notifications/
     *
     * The mobile apps read {@code data.orderId} to navigate to the order screen.
     */
    private void sendViaExpo(String token, String title, String body, Map<String, String> data) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("to",    token);
            payload.put("title", title);
            payload.put("body",  body);
            payload.put("sound", "default");
            if (data != null && !data.isEmpty()) {
                payload.put("data", data);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Accept", "application/json");
            headers.set("Accept-Encoding", "gzip, deflate");

            restTemplate.postForObject(
                    EXPO_PUSH_URL,
                    new HttpEntity<>(payload, headers),
                    String.class);

            log.debug("[Push] Expo push sent to token …{}",
                    token.substring(Math.max(0, token.length() - 10)));
        } catch (Exception e) {
            log.warn("[Push] Expo push failed: {}", e.getMessage());
        }
    }

    // ── Firebase FCM ──────────────────────────────────────────────────────────

    private void sendViaFirebase(String token, String title, String body,
                                  Map<String, String> data) {
        try {
            if (!isFirebaseAvailable()) {
                log.debug("[Push] Firebase not configured — skipping FCM push");
                return;
            }
            Message.Builder builder = Message.builder()
                    .setToken(token)
                    .setNotification(Notification.builder().setTitle(title).setBody(body).build());
            if (data != null) builder.putAllData(data);

            String response = FirebaseMessaging.getInstance().send(builder.build());
            log.debug("[Push] FCM push sent: {}", response);
        } catch (Exception e) {
            log.warn("[Push] FCM push failed: {}", e.getMessage());
        }
    }

    private boolean isFirebaseAvailable() {
        try {
            return !com.google.firebase.FirebaseApp.getApps().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}
