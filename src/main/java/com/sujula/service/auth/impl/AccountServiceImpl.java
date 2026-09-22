package com.sujula.service.auth.impl;

import com.sujula.dto.request.auth.MeRequests;
import com.sujula.dto.response.auth.AuthResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.auth.AccountDataRequest;
import com.sujula.model.auth.UserSession;
import com.sujula.model.constant.DataRequestStatus;
import com.sujula.model.constant.DataRequestType;
import com.sujula.model.constant.Permission;
import com.sujula.model.constant.SessionRevocationReason;
import com.sujula.model.constant.UserRole;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.auth.AccountDataRequestRepository;
import com.sujula.repository.auth.OAuthAccountRepository;
import com.sujula.repository.auth.UserSessionRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.auth.AccountService;
import com.sujula.service.auth.PermissionResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The account, as its owner manages it.
 *
 * <p>Two things are worth reading closely here. The first is that no method
 * takes an account id from a caller — {@code userId} always arrives from the
 * authenticated principal, so there is no parameter to tamper with. The second
 * is {@link #revokeSession}, the only method that accepts an id at all: it
 * resolves the session by id <em>and</em> owner in one query, so another user's
 * session comes back empty rather than being loaded and then refused. A probe
 * cannot learn that the id was real.
 */
@Slf4j
@Service
public class AccountServiceImpl implements AccountService {

    private static final List<DataRequestStatus> OPEN =
            List.of(DataRequestStatus.PENDING, DataRequestStatus.PROCESSING);

    private final UserRepository users;
    private final UserSessionRepository sessions;
    private final OAuthAccountRepository oauthAccounts;
    private final AccountDataRequestRepository dataRequests;
    private final VendorRepository vendors;
    private final PermissionResolver permissions;
    private final AuthMapper mapper;

    public AccountServiceImpl(UserRepository users, UserSessionRepository sessions,
                              OAuthAccountRepository oauthAccounts,
                              AccountDataRequestRepository dataRequests,
                              VendorRepository vendors, PermissionResolver permissions,
                              AuthMapper mapper) {
        this.users = users;
        this.sessions = sessions;
        this.oauthAccounts = oauthAccounts;
        this.dataRequests = dataRequests;
        this.vendors = vendors;
        this.permissions = permissions;
        this.mapper = mapper;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Profile
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public AuthResponses.Me getMe(Long userId) {
        return assemble(requireUser(userId));
    }

    @Override
    @Transactional
    public AuthResponses.Profile updateMe(Long userId, MeRequests.UpdateProfile request) {
        User user = requireUser(userId);

        // Only what was sent. A PATCH that null-blanked every omitted field would
        // wipe a profile any time a client posted a partial form.
        if (present(request.firstName())) {
            user.setFirstName(request.firstName().trim());
        }
        if (present(request.lastName())) {
            user.setLastName(request.lastName().trim());
        }
        if (present(request.preferredCurrency())) {
            user.setPreferredCurrency(request.preferredCurrency().trim().toUpperCase(Locale.ROOT));
        }
        if (present(request.preferredLanguage())) {
            user.setPreferredLanguage(request.preferredLanguage().trim());
        }
        if (request.profileImageUrl() != null) {
            // Explicitly nullable: clearing a picture is a thing people do, and
            // an empty string is how a form says so.
            user.setProfileImageUrl(blankToNull(request.profileImageUrl()));
        }

        return mapper.toProfile(users.save(user));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Data protection
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Recorded rather than executed. Erasure on this platform means
     * pseudonymisation: orders, payments and payouts are financial records that
     * must be kept, so the rows stay and the personal data in them is overwritten
     * by the worker. Doing that inline would also mean deleting the account
     * inside the request the account is making.
     */
    @Override
    @Transactional
    public AuthResponses.DataRequest requestErasure(Long userId) {
        return mapper.toDataRequest(openOrCreate(requireUser(userId), DataRequestType.ERASURE));
    }

    @Override
    @Transactional
    public AuthResponses.DataRequest requestExport(Long userId) {
        return mapper.toDataRequest(openOrCreate(requireUser(userId), DataRequestType.EXPORT));
    }

    @Override
    @Transactional(readOnly = true)
    public AuthResponses.DataRequest latestExport(Long userId) {
        return dataRequests.findLatest(userId, DataRequestType.EXPORT)
                .map(mapper::toDataRequest)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No export has been requested for this account"));
    }

    /**
     * The idempotency the API promises.
     *
     * <p>An open request of the same kind is returned as-is. Without this a
     * client retrying a timed-out call would queue a second erasure, and two
     * erasure jobs racing over the same account is not a situation worth having.
     */
    private AccountDataRequest openOrCreate(User user, DataRequestType type) {
        Optional<AccountDataRequest> open = dataRequests.findOpen(user.getId(), type, OPEN);
        if (open.isPresent()) {
            return open.get();
        }

        AccountDataRequest request = AccountDataRequest.builder()
                .user(user)
                .type(type)
                .status(DataRequestStatus.PENDING)
                .reference(reference(type))
                .build();

        AccountDataRequest saved = dataRequests.save(request);
        log.info("[Account] {} requested for user {} ({})", type, user.getId(), saved.getReference());
        return saved;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Devices
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<AuthResponses.Session> listSessions(Long userId, Long currentSessionId) {
        return sessions.findByUserIdOrderByLastSeenAtDesc(userId).stream()
                .map(session -> mapper.toSession(session, currentSessionId))
                .toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The ownership check is the query. Fetching the session by id and then
     * comparing its user is the version of this that eventually ships with the
     * comparison missing; asking the database for "this id, belonging to this
     * user" cannot.
     */
    @Override
    @Transactional
    public void revokeSession(Long userId, Long sessionId) {
        UserSession session = sessions.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Session", sessionId));

        if (session.getRevokedAt() != null) {
            return;   // already ended; revoking twice is not an error
        }

        session.revoke(SessionRevocationReason.LOGOUT);
        sessions.save(session);
        log.info("[Account] User {} revoked session {}", userId, sessionId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Two queries, not the four {@code /me} costs: the account and — only for
     * a vendor — the vendor record. The linked accounts and the session count
     * that {@code Me} carries are not part of an answer about capability, and a
     * client polling this after an approval should not pay for them.
     */
    @Override
    @Transactional(readOnly = true)
    public AuthResponses.Permissions permissions(Long userId) {
        User user = requireUser(userId);
        Optional<Vendor> vendor = vendorOf(user);

        return new AuthResponses.Permissions(
                user.getRole(),
                permissions.resolve(user, vendor),
                vendor.map(Vendor::getId).orElse(null),
                vendor.map(v -> v.getStatus() == null ? null : v.getStatus().name()).orElse(null),
                vendor.map(v -> v.getStatus() != null && v.getStatus().canTrade()).orElse(false));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Assembly
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the composite {@code /me} response.
     *
     * <p>Four queries at most, and each is needed: the vendor record decides
     * whether selling permissions apply, the linked accounts tell a client which
     * sign-in buttons to show as connected, and the session count is what makes
     * "you are signed in on 4 devices" possible without fetching all of them.
     */
    private AuthResponses.Me assemble(User user) {
        // Loaded once and handed to the resolver. Letting it look the vendor up
        // itself would mean the same row fetched twice inside one response.
        Optional<Vendor> vendor = vendorOf(user);
        Set<Permission> granted = permissions.resolve(user, vendor);

        List<AuthResponses.LinkedAccount> linked = oauthAccounts.findByUserId(user.getId()).stream()
                .map(mapper::toLinkedAccount)
                .toList();

        return new AuthResponses.Me(
                mapper.toProfile(user),
                granted,
                resolvedCurrency(user),
                resolvedLanguage(user),
                vendor.map(Vendor::getId).orElse(null),
                vendor.map(v -> v.getStatus() == null ? null : v.getStatus().name()).orElse(null),
                linked,
                (int) sessions.countByUserIdAndRevokedAtIsNull(user.getId()));
    }

    /**
     * What money will actually be shown in.
     *
     * <p>A stated preference wins. Failing that, the country the account was last
     * seen from decides, because a buyer in London should not be quoted dalasi
     * merely because the platform's home currency is dalasi. Failing both, GMD:
     * this is a Gambia-first marketplace and most accounts are local.
     */
    private static String resolvedCurrency(User user) {
        if (present(user.getPreferredCurrency())) {
            return user.getPreferredCurrency().toUpperCase(Locale.ROOT);
        }
        String country = user.getDetectedCountryCode();
        if (country == null) {
            return "GMD";
        }
        return switch (country.toUpperCase(Locale.ROOT)) {
            case "GM" -> "GMD";
            case "SN", "CI", "ML", "BF", "NE", "TG", "BJ", "GW" -> "XOF";
            case "GB" -> "GBP";
            case "US" -> "USD";
            case "NG" -> "NGN";
            default -> "GMD";
        };
    }

    private static String resolvedLanguage(User user) {
        if (present(user.getPreferredLanguage())) {
            return user.getPreferredLanguage();
        }
        // Senegal and the other CFA neighbours are francophone; the rest of the
        // catalogue's reach is not.
        String country = user.getDetectedCountryCode();
        return country != null && Set.of("SN", "ML", "BF", "NE", "TG", "BJ", "CI")
                .contains(country.toUpperCase(Locale.ROOT)) ? "fr" : "en";
    }

    private static String reference(DataRequestType type) {
        String prefix = type == DataRequestType.EXPORT ? "EXP" : "ERA";
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    /** The vendor record, for the accounts that have one. Empty for everyone else. */
    private Optional<Vendor> vendorOf(User user) {
        return user.getRole() == UserRole.VENDOR
                ? vendors.findByUserId(user.getId())
                : Optional.empty();
    }

    private User requireUser(Long userId) {
        return users.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String blankToNull(String value) {
        return present(value) ? value.trim() : null;
    }
}
