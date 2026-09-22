package com.sujula.service.admin.impl;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.dto.request.admin.AdminUserRequests;
import com.sujula.dto.response.PagedResponse;
import com.sujula.dto.response.admin.AdminUserResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.admin.ModerationCase;
import com.sujula.model.admin.Sanction;
import com.sujula.model.auth.UserSession;
import com.sujula.model.constant.AuditAction;
import com.sujula.model.constant.ModerationReason;
import com.sujula.model.constant.SanctionType;
import com.sujula.model.constant.SessionRevocationReason;
import com.sujula.model.constant.UserRole;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.admin.AdminActivityRepository;
import com.sujula.repository.admin.ModerationCaseRepository;
import com.sujula.repository.admin.SanctionRepository;
import com.sujula.repository.auth.UserSessionRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.AuditService;
import com.sujula.service.EmailService;
import com.sujula.service.admin.AdminUserService;
import com.sujula.service.admin.SanctionRegistry;
import com.sujula.service.auth.TokenService;
import com.sujula.service.security.StepUpVerifier;

import lombok.extern.slf4j.Slf4j;

/**
 * The people half of the administrative surface.
 *
 * <p>Two rules run through all of it. Nothing sets {@code enabled} directly —
 * every lock and unlock goes through {@code SanctionRegistry}, so an account
 * that cannot sign in always has a row explaining itself. And every write
 * records an audit entry naming the administrator, the target and the reason
 * they gave, which is why each method takes the acting staff member as an
 * argument rather than reading one from a context: a method that cannot name who
 * is acting cannot be called.
 */
@Slf4j
@Service
public class AdminUserServiceImpl implements AdminUserService {

    /**
     * How long an impersonated session lasts before it stops working.
     *
     * <p>Short, and not extendable. An administrator who needs longer asks
     * again, which writes a second audit row — and two rows for an hour is a
     * better record than one row for a day.
     */
    private static final Duration MAX_IMPERSONATION = Duration.ofMinutes(60);
    private static final Duration DEFAULT_IMPERSONATION = Duration.ofMinutes(15);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final VendorRepository vendors;
    private final UserSessionRepository sessions;
    private final SanctionRepository sanctions;
    private final ModerationCaseRepository cases;
    private final AdminActivityRepository activity;
    private final SanctionRegistry registry;
    private final AuditService audit;
    private final EmailService email;
    private final PasswordEncoder passwords;
    private final StepUpVerifier stepUp;
    private final TokenService tokens;

    public AdminUserServiceImpl(UserRepository users, VendorRepository vendors,
                                UserSessionRepository sessions, SanctionRepository sanctions,
                                ModerationCaseRepository cases, AdminActivityRepository activity,
                                SanctionRegistry registry, AuditService audit, EmailService email,
                                PasswordEncoder passwords, StepUpVerifier stepUp,
                                TokenService tokens) {
        this.users = users;
        this.vendors = vendors;
        this.sessions = sessions;
        this.sanctions = sanctions;
        this.cases = cases;
        this.activity = activity;
        this.registry = registry;
        this.audit = audit;
        this.email = email;
        this.passwords = passwords;
        this.stepUp = stepUp;
        this.tokens = tokens;
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<AdminUserResponses.UserRow> search(
            User staff, String query, UserRole role, String country, Boolean blocked,
            Boolean lockedOut, Pageable pageable) {
        Page<User> page = users.search(
                blankToNull(query), role, upper(country), blocked, lockedOut,
                LocalDateTime.now(), pageable);
        return PagedResponse.of(page.map(this::rowOf));
    }

    @Override
    @Transactional(readOnly = true)
    public AdminUserResponses.UserDetail detail(User staff, Long userId) {
        User user = require(userId);

        List<AdminUserResponses.SanctionRow> sanctionRows = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (Sanction row : sanctions.findByUserIdOrderByCreatedAtDesc(userId)) {
            sanctionRows.add(sanctionRow(row, now));
        }

        List<AdminUserResponses.SessionRow> sessionRows = new ArrayList<>();
        for (UserSession session : sessions.findByUserIdOrderByLastSeenAtDesc(userId)) {
            sessionRows.add(sessionRow(session));
        }

        List<AdminUserResponses.CaseRow> caseRows = new ArrayList<>();
        for (ModerationCase row : cases.findOpenAgainst(userId)) {
            caseRows.add(new AdminUserResponses.CaseRow(
                    row.getId(), row.getReference(),
                    row.getReason() == null ? null : row.getReason().name(),
                    row.getStatus().name(), row.getSubjectLabel(), row.getDueBy(),
                    row.isOverdue(now)));
        }

        Vendor vendor = vendors.findByUserId(userId).orElse(null);

        return new AdminUserResponses.UserDetail(
                rowOf(user), activityOf(userId), sanctionRows, sessionRows, caseRows,
                vendor == null ? null : new AdminUserResponses.VendorSummary(
                        vendor.getId(), vendor.getStoreName(),
                        vendor.getStatus() == null ? null : vendor.getStatus().name(),
                        vendor.getSettlementCurrency(), vendor.getUpdatedAt()),
                caseRows.isEmpty() ? null
                        : caseRows.size() + " open case(s) against this account. Read them before "
                          + "deciding whether a new report is a pattern.");
    }

    /**
     * The shape of a history, gathered in one place.
     *
     * <p>Assembled here rather than left to the client to fetch from six
     * endpoints, because an agent who has to make six calls makes one and
     * decides on it.
     */
    private AdminUserResponses.Activity activityOf(Long userId) {
        List<AdminUserResponses.Spend> spend = new ArrayList<>();
        for (Object[] row : activity.spendByCurrency(userId)) {
            spend.add(new AdminUserResponses.Spend(
                    (String) row[0],
                    row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1],
                    ((Number) row[2]).intValue()));
        }

        LocalDateTime first = null;
        LocalDateTime last = null;
        List<Object[]> window = activity.orderWindow(userId);
        if (!window.isEmpty() && window.get(0) != null) {
            first = (LocalDateTime) window.get(0)[0];
            last = (LocalDateTime) window.get(0)[1];
        }

        return new AdminUserResponses.Activity(
                activity.countOrders(userId), activity.countCancelledOrders(userId),
                activity.countReturns(userId), activity.countDisputes(userId),
                spend,
                activity.countReviews(userId), activity.countReportedReviews(userId),
                activity.countProductsListed(userId),
                activity.countDeliveriesCompleted(userId),
                first, last);
    }

    // ── Creating and editing ─────────────────────────────────────────────────

    @Override
    @Transactional
    public AdminUserResponses.UserCreated create(User staff, AdminUserRequests.CreateUser request) {
        String address = request.email().trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmailIgnoreCase(address)) {
            throw new BadRequestException("There is already an account on that email address.");
        }
        requireCanGrant(staff, request.role());

        User created = new User();
        created.setEmail(address);
        // A random secret nobody has, replaced the moment they follow the link.
        // An administrator who chose the password would know it, and an account
        // whose password its creator knows is one they can act as without an
        // impersonation row.
        created.setPassword(passwords.encode(randomSecret()));
        created.setFirstName(request.firstName().trim());
        created.setLastName(request.lastName() == null ? null : request.lastName().trim());
        created.setPhone(request.phone());
        created.setRole(request.role());
        created.setDetectedCountryCode(upper(request.countryCode()));
        created.setEnabled(true);
        // Not verified: creating an account for somebody does not prove the
        // address reaches them, and the setup link is what will.
        created.setEmailVerified(false);
        User saved = users.save(created);

        boolean sent = false;
        try {
            // Not the password-reset email: that one prints its argument as a
            // temporary password, and there is no password here to print.
            email.sendAccountSetupEmail(saved.getEmail(), saved.getFirstName(),
                    saved.getRole().name(), staff.getEmail());
            sent = true;
        } catch (RuntimeException failed) {
            log.warn("[Admin] Created user {} but could not send the setup email: {}",
                    saved.getId(), failed.toString());
        }

        audit.record(AuditAction.USER_CREATED_BY_ADMIN, "USER", saved.getId(),
                saved.getEmail(),
                staff.getEmail() + " created a " + request.role() + " account",
                request.reason());

        return new AdminUserResponses.UserCreated(saved.getId(), saved.getEmail(),
                saved.getRole(), sent,
                sent ? "Created. They have been emailed a link to set their own password."
                     : "Created, but we could not send the setup email. They will need to use the "
                       + "forgotten-password link themselves.");
    }

    @Override
    @Transactional
    public AdminUserResponses.UserDetail patch(User staff, Long userId,
                                               AdminUserRequests.PatchUser request) {
        User user = require(userId);
        List<String> changed = new ArrayList<>();

        if (request.firstName() != null && !request.firstName().equals(user.getFirstName())) {
            changed.add("first name");
            user.setFirstName(request.firstName().trim());
        }
        if (request.lastName() != null && !request.lastName().equals(user.getLastName())) {
            changed.add("last name");
            user.setLastName(request.lastName().trim());
        }
        if (request.phone() != null && !request.phone().equals(user.getPhone())) {
            changed.add("phone");
            user.setPhone(request.phone());
            // A changed number is an unverified number. Carrying the old
            // verification across would let an administrator hand somebody a
            // verified phone they do not hold.
            user.setPhoneVerified(false);
        }
        if (request.countryCode() != null) {
            user.setDetectedCountryCode(upper(request.countryCode()));
            changed.add("country");
        }
        if (request.preferredCurrency() != null) {
            user.setPreferredCurrency(upper(request.preferredCurrency()));
            changed.add("currency");
        }
        if (request.preferredLanguage() != null) {
            user.setPreferredLanguage(request.preferredLanguage());
            changed.add("language");
        }

        if (changed.isEmpty()) {
            // Nothing to record. An audit row for a request that changed nothing
            // is noise in the place noise is least affordable.
            return detail(staff, userId);
        }
        users.save(user);

        audit.record(AuditAction.USER_PROFILE_EDITED_BY_ADMIN, "USER", userId, user.getEmail(),
                staff.getEmail() + " edited " + String.join(", ", changed),
                request.reason());
        return detail(staff, userId);
    }

    // ── Locking and unlocking ────────────────────────────────────────────────

    @Override
    @Transactional
    public AdminUserResponses.AccountChanged activate(User staff, Long userId,
                                                      AdminUserRequests.Activate request) {
        User user = require(userId);
        LocalDateTime now = LocalDateTime.now();

        List<Sanction> holding = sanctions.findActive(userId, now).stream()
                .filter(Sanction::locksTheAccount).toList();
        if (holding.isEmpty()) {
            // Idempotent rather than an error: an agent clicking twice, or two
            // agents answering the same complaint, should not see a failure.
            return new AdminUserResponses.AccountChanged(userId, user.isEnabled(), null, 0,
                    "That account was not locked out.");
        }
        for (Sanction row : holding) {
            registry.lift(row, staff, request.reason());
        }

        audit.record(AuditAction.SANCTION_LIFTED, "USER", userId, user.getEmail(),
                staff.getEmail() + " lifted " + holding.size() + " lock(s)", request.reason());

        User reloaded = require(userId);
        return new AdminUserResponses.AccountChanged(userId, reloaded.isEnabled(),
                sanctionRow(holding.get(0), LocalDateTime.now()), 0,
                "The account is back. They can sign in again — their sessions were ended when the "
                        + "lock went on, so they will have to log in.");
    }

    @Override
    @Transactional
    public AdminUserResponses.AccountChanged deactivate(User staff, Long userId,
                                                        AdminUserRequests.Deactivate request) {
        User user = require(userId);
        requireNotSelf(staff, userId, "deactivate");

        // A ban rather than a flag, and it is the same mechanism a suspension
        // uses with no end date. Calling them different things in the API and
        // the same thing in the data is what keeps one writer.
        Sanction issued = registry.issue(user, Sanction.builder()
                .type(SanctionType.BAN)
                .reason(request.category() == null ? ModerationReason.OTHER : request.category())
                .reasonText(request.reason())
                .issuedBy(staff)
                .build());

        audit.record(AuditAction.SANCTION_ISSUED, "USER", userId, user.getEmail(),
                staff.getEmail() + " deactivated the account", request.reason());

        return new AdminUserResponses.AccountChanged(userId, false,
                sanctionRow(issued, LocalDateTime.now()), countSessions(userId),
                "Deactivated, and every session ended. It stays off until somebody puts it back.");
    }

    @Override
    @Transactional
    public AdminUserResponses.AccountChanged suspend(User staff, Long userId,
                                                     AdminUserRequests.Suspend request) {
        User user = require(userId);
        requireNotSelf(staff, userId, "suspend");

        Sanction issued = registry.issue(user, Sanction.builder()
                .type(SanctionType.SUSPENSION)
                .reason(request.category() == null ? ModerationReason.OTHER : request.category())
                .reasonText(request.reason())
                // Computed from a number of days rather than taken as a date: an
                // administrator thinks in "a week", and a date sent from a client
                // is one that can be off by a timezone nobody converted.
                .expiresAt(LocalDateTime.now().plusDays(request.days()))
                .issuedBy(staff)
                .build());

        audit.record(AuditAction.SANCTION_ISSUED, "USER", userId, user.getEmail(),
                staff.getEmail() + " suspended the account for " + request.days() + " day(s)",
                request.reason());

        return new AdminUserResponses.AccountChanged(userId, false,
                sanctionRow(issued, LocalDateTime.now()), countSessions(userId),
                "Suspended until " + issued.getExpiresAt() + ". It comes back by itself — nothing "
                        + "has to be run for that to happen.");
    }

    // ── Roles and sessions ───────────────────────────────────────────────────

    @Override
    @Transactional
    public AdminUserResponses.RoleChanged changeRole(User staff, Long userId,
                                                     AdminUserRequests.ChangeRole request) {
        User user = require(userId);
        requireNotSelf(staff, userId, "change the role of");
        requireCanGrant(staff, request.role());

        UserRole from = user.getRole();
        if (from == request.role()) {
            return new AdminUserResponses.RoleChanged(userId, from, from, 0,
                    "They already have that role.");
        }
        if (from == UserRole.VENDOR && vendors.findByUserId(userId).isPresent()) {
            // A store with orders against it cannot simply stop having a seller.
            // Suspending the store is the operation that exists for this, and it
            // has a cascade this one does not.
            throw new BadRequestException(
                    "That account runs a store. Suspend or close the store first — taking the "
                            + "vendor role away would leave its orders with nobody accountable "
                            + "for them.");
        }

        user.setRole(request.role());
        users.save(user);

        // A role lives in the access token, so a live token still asserts the
        // old one. Ending the sessions is what makes a demotion take effect now
        // rather than whenever the token happens to expire — and the demotion
        // is the direction that matters.
        int ended = sessions.revokeAllForUser(userId, SessionRevocationReason.ADMIN,
                LocalDateTime.now(), null);

        audit.record(request.role().isStaff()
                        ? AuditAction.USER_ROLE_GRANTED : AuditAction.USER_ROLE_REVOKED,
                "USER", userId, user.getEmail(),
                staff.getEmail() + " changed the role from " + from + " to " + request.role()
                        + (request.scopeCountry() == null ? ""
                           : " scoped to " + upper(request.scopeCountry())),
                request.reason());

        return new AdminUserResponses.RoleChanged(userId, from, request.role(), ended,
                "Role changed, and their sessions were ended so the new one takes effect "
                        + "immediately rather than when their token expires.");
    }

    @Override
    @Transactional
    public AdminUserResponses.SessionsEnded forceLogout(User staff, Long userId,
                                                        AdminUserRequests.ForceLogout request) {
        User user = require(userId);
        int ended = sessions.revokeAllForUser(userId, SessionRevocationReason.ADMIN,
                LocalDateTime.now(), null);

        audit.record(AuditAction.USER_SESSIONS_ENDED_BY_ADMIN, "USER", userId, user.getEmail(),
                staff.getEmail() + " ended " + ended + " session(s)", request.reason());

        return new AdminUserResponses.SessionsEnded(userId, ended,
                ended == 0 ? "They had nothing open."
                        : ended + " session(s) ended. They will have to sign in again.");
    }

    @Override
    @Transactional
    public AdminUserResponses.MfaReset resetMfa(User staff, Long userId,
                                                AdminUserRequests.ResetMfa request) {
        // The administrator's own credentials first. This is the endpoint an
        // attacker who has reached an admin account wants most, because it turns
        // one takeover into a takeover of anybody.
        stepUp.verify(staff, request.password(), request.totpCode(),
                "resetting another account's second factor");

        User user = require(userId);
        boolean wasEnabled = user.isTotpEnabled();

        user.setTotpSecret(null);
        user.setTotpEnabled(false);
        user.setTotpVerified(false);
        users.save(user);

        // Their sessions go too. Leaving them signed in would mean the account
        // spends the next thirty days with no second factor and a live token,
        // which is the window somebody would use.
        int ended = sessions.revokeAllForUser(userId, SessionRevocationReason.MFA_CHANGED,
                LocalDateTime.now(), null);

        audit.record(AuditAction.USER_MFA_RESET, "USER", userId, user.getEmail(),
                staff.getEmail() + " cleared the second factor", request.reason());

        return new AdminUserResponses.MfaReset(userId, wasEnabled, ended,
                wasEnabled
                        ? "Cleared. They should set it up again at the first opportunity — the "
                          + "account has no second factor until they do."
                        : "They did not have one switched on. Nothing changed except their "
                          + "sessions, which were ended.");
    }

    // ── Impersonation ────────────────────────────────────────────────────────

    @Override
    @Transactional
    public AdminUserResponses.ImpersonationOpened impersonate(
            User staff, Long userId, AdminUserRequests.Impersonate request) {
        User user = require(userId);
        requireNotSelf(staff, userId, "impersonate");

        if (user.getRole() != null && user.getRole().isStaff()) {
            // An administrator acting as another administrator is a way to take
            // an action that reads as somebody else's. Refused outright: there
            // is no support case that needs it, and every case that looks like
            // one is better served by asking them.
            throw new BadRequestException(
                    "You cannot impersonate another member of staff. If you need something done "
                            + "on their account, ask them — anything else would put your actions "
                            + "under their name.");
        }

        Duration window = request.minutes() == null
                ? DEFAULT_IMPERSONATION
                : Duration.ofMinutes(Math.min(request.minutes(), MAX_IMPERSONATION.toMinutes()));
        LocalDateTime now = LocalDateTime.now();

        // Written here rather than through SessionFactory, and the differences
        // are the point: no refresh token, so it cannot be extended; a short
        // expiry; and the two impersonation columns, which is what makes this
        // session legible in the user's own device list rather than appearing as
        // a machine they do not recognise.
        UserSession session = sessions.save(UserSession.builder()
                .user(user)
                .refreshTokenHash("impersonation:" + java.util.UUID.randomUUID())
                .deviceLabel("Support session (" + staff.getEmail() + ")")
                .createdAt(now)
                .lastSeenAt(now)
                .expiresAt(now.plus(window))
                .impersonatedByUserId(staff.getId())
                .impersonationReason(request.reason())
                .build());

        String accessToken = tokens.mintAccessToken(user, session.getId(), staff.getId());

        audit.record(AuditAction.USER_IMPERSONATED, "USER", userId, user.getEmail(),
                staff.getEmail() + " opened a session as this user for " + window.toMinutes()
                        + " minutes",
                request.reason() + (request.reference() == null ? ""
                        : " (ref " + request.reference() + ")"));
        log.warn("[Admin] IMPERSONATION: {} is acting as user {} until {} — {}",
                staff.getEmail(), userId, session.getExpiresAt(), request.reason());

        return new AdminUserResponses.ImpersonationOpened(
                userId, displayName(user), accessToken, session.getExpiresAt(),
                session.getId(), request.reason(),
                "This session expires in " + window.toMinutes() + " minutes and cannot be "
                        + "extended. Everything you do on it is recorded against your name, and "
                        + "the user can see it in their own device list.");
    }

    // ── Guards ───────────────────────────────────────────────────────────────

    private User require(Long userId) {
        return users.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }

    /**
     * Stops an administrator acting on their own account.
     *
     * <p>Not paternalism: an administrator who suspends themselves locks
     * everybody out of the thing that could unsuspend them, and one who changes
     * their own role can promote themselves without a second person ever seeing
     * it. Both are done by asking somebody else, which is the control.
     */
    private static void requireNotSelf(User staff, Long userId, String verb) {
        if (staff.getId().equals(userId)) {
            throw new BadRequestException(
                    "You cannot " + verb + " your own account. Ask another administrator — that "
                            + "second person is the point.");
        }
    }

    /**
     * Refuses a grant of staff privilege by somebody who should not be making it.
     *
     * <p>Only an administrator creates administrators. Written as its own check
     * rather than relying on the endpoint's own gate, because this method is
     * also reached from account creation and the two would otherwise have to
     * remember the same rule separately.
     */
    private static void requireCanGrant(User staff, UserRole role) {
        if (role != null && role.isStaff() && !staff.getRole().canDecide()) {
            throw new BadRequestException("Only an administrator can create staff accounts.");
        }
    }

    // ── Views ────────────────────────────────────────────────────────────────

    private AdminUserResponses.UserRow rowOf(User user) {
        LocalDateTime now = LocalDateTime.now();
        List<Sanction> active = sanctions.findActive(user.getId(), now).stream()
                .filter(Sanction::locksTheAccount).toList();
        Sanction holding = active.isEmpty() ? null : active.get(0);

        return new AdminUserResponses.UserRow(
                user.getId(), user.getEmail(), displayName(user), user.getPhone(),
                user.getRole(), user.getDetectedCountryCode(),
                user.isEnabled(), user.isBlocked(), user.isEmailVerified(), user.isTotpEnabled(),
                // Read from the sanction rather than from the flag, so the list
                // and the gate cannot disagree about who is locked out.
                holding == null ? null
                        : holding.getType() + ": " + holding.getReasonText(),
                holding == null ? null : holding.getExpiresAt(),
                user.getCreatedAt(),
                sessions.findByUserIdOrderByLastSeenAtDesc(user.getId()).stream()
                        .map(UserSession::getLastSeenAt)
                        .filter(java.util.Objects::nonNull)
                        .findFirst().orElse(null));
    }

    private AdminUserResponses.SanctionRow sanctionRow(Sanction row, LocalDateTime now) {
        return new AdminUserResponses.SanctionRow(
                row.getId(), row.getType(),
                row.getReason() == null ? null : row.getReason().name(),
                row.getReasonText(), row.getRestrictedPermission(),
                row.getCreatedAt(),
                row.getIssuedBy() == null ? "the platform" : row.getIssuedBy().getEmail(),
                row.getExpiresAt(),
                row.getLiftedAt(),
                row.getLiftedBy() == null ? null : row.getLiftedBy().getEmail(),
                row.getLiftedReason(),
                row.isActiveAt(now));
    }

    private AdminUserResponses.SessionRow sessionRow(UserSession session) {
        return new AdminUserResponses.SessionRow(
                session.getId(), session.getDeviceLabel(), session.getIpAddress(),
                session.getCountryCode(), session.getCreatedAt(), session.getLastSeenAt(),
                session.getExpiresAt(), session.getRevokedAt(),
                session.getRevokedReason() == null ? null : session.getRevokedReason().name(),
                // Named, so an administrator's own session on somebody's account
                // reads as what it is rather than as a device nobody recognises.
                session.getImpersonatedByUserId() == null ? null
                        : users.findById(session.getImpersonatedByUserId())
                                .map(User::getEmail).orElse("a member of staff"));
    }

    private int countSessions(Long userId) {
        return activity.countLiveSessions(userId, LocalDateTime.now());
    }

    private static String displayName(User user) {
        String first = user.getFirstName() == null ? "" : user.getFirstName();
        String last = user.getLastName() == null ? "" : user.getLastName();
        String joined = (first + " " + last).trim();
        return joined.isEmpty() ? user.getEmail() : joined;
    }

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
