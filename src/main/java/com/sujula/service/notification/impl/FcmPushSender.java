package com.sujula.service.notification.impl;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.sujula.model.notification.PushDevice;
import com.sujula.service.notification.PushSender;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Sends push notifications through Firebase Cloud Messaging (HTTP v1).
 *
 * <p>FCM is free with no message cap, and it reaches Android and — through the
 * APNs key uploaded to the Firebase console — iOS, from one call.
 *
 * <p>No Firebase SDK. The v1 API needs an OAuth access token minted from the
 * project's service account, which is one RS256-signed JWT exchanged at
 * Google's token endpoint; the JDK signs that without help, and the token is
 * cached until shortly before it expires.
 *
 * <p>{@code sujula.push.fcm.credentials} takes the service-account JSON as a
 * file path, as the JSON itself, or as base64 of the JSON — the last because
 * most hosts only accept single-line environment variables.
 */
@Slf4j
@Component
@ConditionalOnExpression("'${sujula.push.fcm.credentials:}'.length() > 0")
public class FcmPushSender implements PushSender {

    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final ObjectMapper mapper;
    private final RestClient http;
    private final String projectId;
    private final String clientEmail;
    private final String tokenUri;
    private final PrivateKey privateKey;

    private String accessToken;
    private Instant accessTokenExpires = Instant.EPOCH;

    public FcmPushSender(@Value("${sujula.push.fcm.credentials}") String credentials,
                         ObjectMapper mapper) {
        this.mapper = mapper;
        JsonNode account = mapper.readTree(readCredentials(credentials));
        this.projectId = account.path("project_id").asString();
        this.clientEmail = account.path("client_email").asString();
        this.tokenUri = account.path("token_uri").asString("https://oauth2.googleapis.com/token");
        this.privateKey = parseKey(account.path("private_key").asString());
        if (projectId.isBlank() || clientEmail.isBlank()) {
            throw new IllegalStateException("sujula.push.fcm.credentials is not a Firebase "
                    + "service-account key: project_id or client_email is missing.");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT);
        factory.setReadTimeout(TIMEOUT);
        this.http = RestClient.builder().requestFactory(factory).build();
        log.info("[Push] Firebase Cloud Messaging configured for project {}", projectId);
    }

    @Override
    public int send(List<PushDevice> devices, String title, String body, String referenceId) {
        if (devices == null || devices.isEmpty()) {
            return 0;
        }
        String bearer;
        try {
            bearer = accessToken();
        } catch (RuntimeException failed) {
            log.warn("[Push] Could not obtain an FCM access token: {}", failed.getMessage());
            return 0;
        }
        int reached = 0;
        for (PushDevice device : devices) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("token", device.getToken());
            message.put("notification", Map.of("title", title == null ? "" : title,
                    "body", body == null ? "" : body));
            if (referenceId != null) {
                message.put("data", Map.of("referenceId", referenceId));
            }
            try {
                http.post()
                        .uri("https://fcm.googleapis.com/v1/projects/{project}/messages:send", projectId)
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(mapper.writeValueAsString(Map.of("message", message)))
                        .retrieve()
                        .toBodilessEntity();
                reached++;
            } catch (RuntimeException failed) {
                // The device, never the token: a token is an address anybody
                // holding the log could message.
                log.warn("[Push] FCM refused device {} ({}): {}", device.getId(),
                        device.getPlatform(), failed.getMessage());
            }
        }
        return reached;
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    private synchronized String accessToken() {
        Instant now = Instant.now();
        if (accessToken != null && now.isBefore(accessTokenExpires.minusSeconds(120))) {
            return accessToken;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
        form.add("assertion", signedAssertion(now));
        String response = http.post().uri(tokenUri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
        JsonNode token = mapper.readTree(response);
        accessToken = token.path("access_token").asString();
        accessTokenExpires = now.plusSeconds(token.path("expires_in").asLong(3600));
        return accessToken;
    }

    private String signedAssertion(Instant now) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", clientEmail);
        claims.put("scope", SCOPE);
        claims.put("aud", tokenUri);
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plusSeconds(3600).getEpochSecond());
        String unsigned = b64.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}"
                .getBytes(StandardCharsets.UTF_8)) + "."
                + b64.encodeToString(mapper.writeValueAsBytes(claims));
        try {
            Signature rsa = Signature.getInstance("SHA256withRSA");
            rsa.initSign(privateKey);
            rsa.update(unsigned.getBytes(StandardCharsets.UTF_8));
            return unsigned + "." + b64.encodeToString(rsa.sign());
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign the FCM token request", e);
        }
    }

    static String readCredentials(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("{")) {
            return trimmed;
        }
        Path path = Path.of(trimmed);
        try {
            if (Files.isRegularFile(path)) {
                return Files.readString(path);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("sujula.push.fcm.credentials names " + path
                    + " but it could not be read", e);
        } catch (RuntimeException ignored) {
            // Not a path; fall through to base64.
        }
        try {
            return new String(Base64.getDecoder().decode(trimmed), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("sujula.push.fcm.credentials is neither a readable file, "
                    + "JSON, nor base64 of JSON.");
        }
    }

    private static PrivateKey parseKey(String pem) {
        String der = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(der)));
        } catch (Exception e) {
            throw new IllegalStateException("The Firebase service-account private_key could not be read", e);
        }
    }
}
