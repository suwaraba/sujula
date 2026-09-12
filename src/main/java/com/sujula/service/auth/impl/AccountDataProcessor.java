package com.sujula.service.auth.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sujula.model.auth.AccountDataRequest;
import com.sujula.model.constant.DataRequestStatus;
import com.sujula.model.constant.DataRequestType;
import com.sujula.model.constant.SessionRevocationReason;
import com.sujula.model.user.User;
import com.sujula.repository.AddressRepository;
import com.sujula.repository.auth.AccountDataRequestRepository;
import com.sujula.repository.auth.MfaRecoveryCodeRepository;
import com.sujula.repository.auth.OAuthAccountRepository;
import com.sujula.repository.auth.UserSessionRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.repository.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Carries out one data-protection request, transactionally.
 *
 * <p>A bean of its own rather than a method on {@link AccountDataWorker}, for a
 * reason that is easy to get wrong: Spring's {@code @Transactional} works
 * through a proxy, and a call from one method of a bean to another on
 * {@code this} does not go through it. Had these methods stayed beside the
 * scheduler that calls them, {@code REQUIRES_NEW} would have been silently
 * inert — and since a scheduled method starts with no transaction at all, the
 * first lazy association touched would have thrown. Crossing a bean boundary is
 * what makes the annotations mean anything.
 *
 * <p><strong>Erasure is pseudonymisation, not deletion.</strong> Orders,
 * payments and payouts are financial records a marketplace is obliged to keep,
 * and deleting a buyer would tear the referential heart out of every order they
 * ever placed. What is erased is the personal data: name, email, phone, address
 * lines, profile picture, credentials and every device session. The rows remain,
 * attached to an account that no longer identifies anybody.
 */
@Slf4j
@Component
public class AccountDataProcessor {

    private final AccountDataRequestRepository requests;
    private final UserRepository users;
    private final UserSessionRepository sessions;
    private final MfaRecoveryCodeRepository recoveryCodes;
    private final OAuthAccountRepository oauthAccounts;
    private final AddressRepository addresses;
    private final OrderRepository orders;
    private final ObjectMapper objectMapper;

    public AccountDataProcessor(AccountDataRequestRepository requests, UserRepository users,
                                UserSessionRepository sessions, MfaRecoveryCodeRepository recoveryCodes,
                                OAuthAccountRepository oauthAccounts, AddressRepository addresses,
                                OrderRepository orders, ObjectMapper objectMapper) {
        this.requests = requests;
        this.users = users;
        this.sessions = sessions;
        this.recoveryCodes = recoveryCodes;
        this.oauthAccounts = oauthAccounts;
        this.addresses = addresses;
        this.orders = orders;
        this.objectMapper = objectMapper;
    }

    /**
     * One request, in its own transaction.
     *
     * <p>{@code REQUIRES_NEW} so a failure rolls back only this request. Sharing
     * the scheduler's transaction would mean one failure discarding the work of
     * every request processed alongside it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void runOne(Long requestId) {
        AccountDataRequest request = requests.findById(requestId).orElse(null);
        if (request == null || request.getStatus() != DataRequestStatus.PENDING) {
            return;
        }

        request.setStatus(DataRequestStatus.PROCESSING);
        request.setStartedAt(LocalDateTime.now());
        requests.save(request);

        if (request.getType() == DataRequestType.EXPORT) {
            export(request);
        } else {
            erase(request);
        }

        request.setStatus(DataRequestStatus.COMPLETED);
        request.setCompletedAt(LocalDateTime.now());
        requests.save(request);
        log.info("[Account] {} {} completed for user {}",
                request.getType(), request.getReference(), request.getUser().getId());
    }

    /**
     * Builds the copy of everything held about a user.
     *
     * <p>Stored as JSON on the request row rather than in object storage. That
     * keeps it inside the database's own access controls and its backup and
     * retention policy — an export is the single most concentrated piece of
     * personal data the platform ever produces, and a bucket is one
     * misconfiguration away from being public.
     */
    private void export(AccountDataRequest request) {
        User user = request.getUser();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("exportedAt", LocalDateTime.now().toString());
        payload.put("reference", request.getReference());

        Map<String, Object> account = new LinkedHashMap<>();
        account.put("id", user.getId());
        account.put("email", user.getEmail());
        account.put("firstName", user.getFirstName());
        account.put("lastName", user.getLastName());
        account.put("phone", user.getPhone());
        account.put("role", user.getRole());
        account.put("emailVerified", user.isEmailVerified());
        account.put("phoneVerified", user.isPhoneVerified());
        account.put("preferredCurrency", user.getPreferredCurrency());
        account.put("preferredLanguage", user.getPreferredLanguage());
        account.put("createdAt", String.valueOf(user.getCreatedAt()));
        payload.put("account", account);

        payload.put("addresses", addresses.findByUserId(user.getId()).stream()
                .map(a -> Map.of(
                        "label", String.valueOf(a.getLabel()),
                        "fullName", String.valueOf(a.getFullName()),
                        "phone", String.valueOf(a.getPhone()),
                        "street", String.valueOf(a.getStreet()),
                        "city", String.valueOf(a.getCity()),
                        "country", String.valueOf(a.getCountryCode())))
                .toList());

        payload.put("orders", orders.findExportRows(user.getId()).stream()
                .map(o -> Map.of(
                        "orderNumber", String.valueOf(o.getOrderNumber()),
                        "status", String.valueOf(o.getStatus()),
                        "total", String.valueOf(o.getTotal()),
                        "currency", String.valueOf(o.getCurrency()),
                        "placedAt", String.valueOf(o.getPlacedAt())))
                .toList());

        payload.put("devices", sessions.findByUserIdOrderByLastSeenAtDesc(user.getId()).stream()
                .map(s -> Map.of(
                        "device", String.valueOf(s.getDeviceLabel()),
                        "lastSeenAt", String.valueOf(s.getLastSeenAt()),
                        "createdAt", String.valueOf(s.getCreatedAt())))
                .toList());

        payload.put("linkedAccounts", oauthAccounts.findByUserId(user.getId()).stream()
                .map(a -> Map.of("provider", String.valueOf(a.getProvider()),
                                 "linkedAt", String.valueOf(a.getLinkedAt())))
                .toList());

        try {
            request.setDownloadUrl("data:application/json;base64,"
                    + java.util.Base64.getEncoder().encodeToString(
                            objectMapper.writeValueAsBytes(payload)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise the export", e);
        }

        // Time-limited by policy as well as by intent. A complete copy of
        // someone's life on the platform must not sit on a permanent link.
        request.setDownloadExpiresAt(LocalDateTime.now().plusDays(7));
    }

    /**
     * Overwrites everything that identifies a person, and keeps the rest.
     *
     * <p>The email becomes a unique unusable address because the column is unique
     * and not nullable — two erased accounts must not collide — and the password
     * becomes a value nobody holds rather than blank, so the account cannot be
     * signed into by anyone, including by accident.
     */
    private void erase(AccountDataRequest request) {
        User user = request.getUser();
        Long id = user.getId();

        user.setEmail("erased-" + id + "-" + UUID.randomUUID().toString().substring(0, 8)
                + "@erased.invalid");
        user.setFirstName("Erased");
        user.setLastName("Account");
        user.setPhone(null);
        user.setProfileImageUrl(null);
        user.setPassword("{erased}" + UUID.randomUUID());
        user.setEmailVerificationToken(null);
        user.setEmailVerificationTokenExpiry(null);
        user.setPasswordResetToken(null);
        user.setPasswordResetTokenExpiry(null);
        user.setTotpSecret(null);
        user.setTotpEnabled(false);
        user.setTotpVerified(false);
        user.setEmailVerified(false);
        user.setPhoneVerified(false);
        user.setEnabled(false);
        users.save(user);

        // The address book is personal data with no financial character: unlike
        // an order, nothing depends on it, so it is blanked rather than kept.
        // The address book is overwritten rather than nulled: fullName, phone and
        // street are NOT NULL columns, so erasure has to put something there. The
        // city and country stay — they carry no identity on their own and an order
        // shipped to Serrekunda remains a fact about the delivery, not the person.
        addresses.findByUserId(id).forEach(address -> {
            address.setFullName("Erased");
            address.setPhone("Erased");
            address.setStreet("Erased");
            address.setApartmentSuite(null);
            address.setPostalCode(null);
            address.setLatitude(null);
            address.setLongitude(null);
            addresses.save(address);
        });

        recoveryCodes.deleteAllForUser(id);
        oauthAccounts.deleteAll(oauthAccounts.findByUserId(id));
        sessions.revokeAllForUser(id, SessionRevocationReason.ACCOUNT_ERASED, LocalDateTime.now(), null);

        log.info("[Account] User {} pseudonymised under {}. Orders and payments retained as "
                + "financial records, with personal data removed.", id, request.getReference());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long requestId, String reason) {
        requests.findById(requestId).ifPresent(request -> {
            request.setStatus(DataRequestStatus.FAILED);
            request.setCompletedAt(LocalDateTime.now());
            request.setFailureReason(truncate(reason));
            requests.save(request);
        });
    }

    private static String truncate(String value) {
        if (value == null) {
            return "Unknown error";
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
