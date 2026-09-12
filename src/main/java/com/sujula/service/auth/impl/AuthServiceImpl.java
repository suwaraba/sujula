package com.sujula.service.auth.impl;

import com.sujula.dto.request.auth.AuthRequests;
import com.sujula.dto.response.auth.AuthResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.auth.MfaRecoveryCode;
import com.sujula.model.auth.PhoneVerification;
import com.sujula.model.auth.UserSession;
import com.sujula.model.constant.SessionRevocationReason;
import com.sujula.model.constant.UserRole;
import com.sujula.model.user.User;
import com.sujula.repository.auth.MfaRecoveryCodeRepository;
import com.sujula.repository.auth.PhoneVerificationRepository;
import com.sujula.repository.auth.UserSessionRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.EmailService;
import com.sujula.service.auth.AuthProperties;
import com.sujula.service.auth.AuthService;
import com.sujula.service.auth.TokenService;
import com.sujula.service.auth.TotpService;
import com.sujula.service.security.LoginAttemptTracker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code /auth} layer.
 *
 * <p>Reuses the account rules already in the project rather than restating them:
 * the failed-sign-in escalation ladder, the lockout window, the verification and
 * reset token flows all live where they lived, and this class calls them. Two
 * implementations of "what happens on a wrong password" would drift apart within
 * a month, and one of them would be the one attackers found.
 */
@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    /**
     * Compared against when no account matched, so a request for an unknown
     * address costs the same as one for a known address with a wrong password.
     * Without it, response time alone enumerates the user table.
     */
    private static final String TIMING_EQUALISER =
            "$2a$10$ZZZZZZZZZZZZZZZZZZZZZeZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final UserRepository users;
    private final UserSessionRepository sessions;
    private final MfaRecoveryCodeRepository recoveryCodes;
    private final PhoneVerificationRepository phoneVerifications;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final TotpService totpService;
    private final SessionFactory sessionFactory;
    private final AuthMapper mapper;
    private final AuthProperties properties;
    private final LoginAttemptTracker loginAttempts;
    private final EmailService emailService;
    private final OAuthExchange oauthExchange;
    private final SessionReplayGuard replayGuard;
    private final Environment environment;

    public AuthServiceImpl(UserRepository users, UserSessionRepository sessions,
                           MfaRecoveryCodeRepository recoveryCodes,
                           PhoneVerificationRepository phoneVerifications,
                           PasswordEncoder passwordEncoder, TokenService tokenService,
                           TotpService totpService, SessionFactory sessionFactory,
                           AuthMapper mapper, AuthProperties properties,
                           LoginAttemptTracker loginAttempts, EmailService emailService,
                           OAuthExchange oauthExchange, SessionReplayGuard replayGuard,
                           Environment environment) {
        this.users = users;
        this.sessions = sessions;
        this.recoveryCodes = recoveryCodes;
        this.phoneVerifications = phoneVerifications;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.totpService = totpService;
        this.sessionFactory = sessionFactory;
        this.mapper = mapper;
        this.properties = properties;
        this.loginAttempts = loginAttempts;
        this.emailService = emailService;
        this.oauthExchange = oauthExchange;
        this.replayGuard = replayGuard;
        this.environment = environment;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Getting in
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public AuthResponses.Tokens register(AuthRequests.Register request) {
        String email = normaliseEmail(request.email());
        if (users.existsByEmailIgnoreCase(email)) {
            // Deliberately explicit. Hiding it does not work — the address is
            // already discoverable through password reset and through simply
            // trying to register — and an unexplained failure sends people to
            // support instead of to sign-in.
            throw new BadRequestException("An account already exists for " + email);
        }

        String verificationToken = UUID.randomUUID().toString();
        User user = User.builder()
                .email(email)
                .password(passwordEncoder.encode(request.password()))
                .firstName(request.firstName().trim())
                .lastName(request.lastName().trim())
                .phone(blankToNull(request.phone()))
                // Registration only ever creates a customer. Selling and staff
                // roles are granted by approval; accepting a role here would let
                // anyone appoint themselves.
                .role(UserRole.CUSTOMER)
                .enabled(true)
                .emailVerified(false)
                .phoneVerified(false)
                .preferredCurrency(defaulted(request.preferredCurrency(), "GMD").toUpperCase(Locale.ROOT))
                .preferredLanguage(defaulted(request.preferredLanguage(), "en"))
                .emailVerificationToken(verificationToken)
                .emailVerificationTokenExpiry(LocalDateTime.now().plusDays(2))
                .build();

        User saved = users.save(user);
        sendQuietly(() -> emailService.sendVerificationEmail(
                saved.getEmail(), saved.getFirstName(), verificationToken));

        log.info("[Auth] Registered {}", saved.getId());
        return sessionFactory.open(saved, request.deviceLabel(), mapper.toProfile(saved));
    }

    @Override
    @Transactional
    public AuthResponses.LoginResult login(AuthRequests.Login request) {
        String email = normaliseEmail(request.email());
        Optional<User> found = users.findByEmailIgnoreCase(email);

        if (found.isEmpty()) {
            // Burn the same time a real comparison would, then fail identically.
            passwordEncoder.matches(request.password(), TIMING_EQUALISER);
            throw new BadCredentialsException("Invalid email or password");
        }

        User user = found.get();
        requireNotLockedOut(user);

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            loginAttempts.recordFailure(user.getId());
            throw new BadCredentialsException("Invalid email or password");
        }

        // Only after the password is right. Telling someone their account is
        // blocked before they have proved they own it is an information leak.
        requireUsableAccount(user);

        if (user.isTotpEnabled()) {
            if (!passesSecondFactor(user, request)) {
                if (isBlank(request.totpCode()) && isBlank(request.recoveryCode())) {
                    // Not a failure: the password was right and one more thing is
                    // needed. A client that cannot tell this from a wrong password
                    // shows the wrong message.
                    return AuthResponses.LoginResult.mfaChallenge();
                }
                loginAttempts.recordFailure(user.getId());
                throw new BadCredentialsException("That authenticator code is not valid");
            }
        }

        loginAttempts.recordSuccess(user.getId());
        return AuthResponses.LoginResult.authenticated(
                sessionFactory.open(user, request.deviceLabel(), mapper.toProfile(user)));
    }

    /**
     * Refreshes a session, or ends it if the token was replayed.
     *
     * <p>The replay path is the subtle part: the revocation must survive the
     * exception that follows it. Written inside this transaction, the rollback
     * that carries the 401 back would also roll back the revocation — leaving
     * the stolen token's session alive, which is precisely the outcome the
     * detection exists to prevent. {@link SessionReplayGuard} is a separate bean
     * so its {@code REQUIRES_NEW} actually applies.
     */
    @Override
    @Transactional
    public AuthResponses.Tokens refresh(AuthRequests.Refresh request) {
        String hash = tokenService.sha256(request.refreshToken());

        Optional<UserSession> current = sessions.findByRefreshTokenHash(hash);
        if (current.isPresent()) {
            UserSession session = current.get();
            if (!session.isActive()) {
                throw new BadCredentialsException("This session has ended. Sign in again.");
            }
            requireUsableAccount(session.getUser());
            return sessionFactory.rotate(session, mapper.toProfile(session.getUser()));
        }

        sessions.findByPreviousTokenHash(hash).ifPresent(replayGuard::revokeOnReplay);
        throw new BadCredentialsException("That refresh token is not valid. Sign in again.");
    }

    @Override
    @Transactional
    public void logout(AuthRequests.Logout request) {
        sessions.findByRefreshTokenHash(tokenService.sha256(request.refreshToken()))
                .ifPresent(session -> {
                    session.revoke(SessionRevocationReason.LOGOUT);
                    sessions.save(session);
                });
        // Silent when nothing matched. Signing out is idempotent, and an error
        // here would tell a caller whether a token they hold is real.
    }

    @Override
    @Transactional
    public int logoutAll(Long userId) {
        int ended = sessions.revokeAllForUser(
                userId, SessionRevocationReason.LOGOUT_ALL, LocalDateTime.now(), null);
        log.info("[Auth] User {} signed out of {} session(s)", userId, ended);
        return ended;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Proving an address
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void verifyEmail(AuthRequests.VerifyEmail request) {
        User user = users.findByEmailVerificationToken(request.token())
                .orElseThrow(() -> new BadRequestException("That verification link is not valid"));

        if (user.getEmailVerificationTokenExpiry() == null
                || user.getEmailVerificationTokenExpiry().isBefore(LocalDateTime.now())) {
            throw new BadRequestException("That verification link has expired. Request a new one.");
        }

        user.setEmailVerified(true);
        user.setEmailVerificationToken(null);
        user.setEmailVerificationTokenExpiry(null);
        users.save(user);
    }

    @Override
    @Transactional
    public void resendVerificationEmail(AuthRequests.ResendVerification request) {
        Optional<User> found = users.findByEmailIgnoreCase(normaliseEmail(request.email()));

        // Answers the same whatever the outcome. This endpoint is unauthenticated,
        // so a different response for a known address turns it into a directory.
        found.filter(user -> !user.isEmailVerified()).ifPresent(user -> {
            String token = UUID.randomUUID().toString();
            user.setEmailVerificationToken(token);
            user.setEmailVerificationTokenExpiry(LocalDateTime.now().plusDays(2));
            users.save(user);
            sendQuietly(() -> emailService.sendVerificationEmail(
                    user.getEmail(), user.getFirstName(), token));
        });
    }

    @Override
    @Transactional
    public AuthResponses.PhoneChallenge requestPhoneVerification(
            Long userId, AuthRequests.PhoneVerificationRequest request) {

        User user = requireUser(userId);
        AuthProperties.PhoneVerification config = properties.getPhone();

        long recent = phoneVerifications.countRequestedSince(userId, LocalDateTime.now().minusHours(1));
        if (recent >= config.getMaxPerHour()) {
            throw new BadRequestException(
                    "Too many codes requested. Try again in an hour, or contact support.");
        }

        String phone = request.phone().replaceAll("[^+0-9]", "");
        String code = numericCode(6);

        PhoneVerification challenge = PhoneVerification.builder()
                .user(user)
                .phone(phone)
                .codeHash(passwordEncoder.encode(code))
                .expiresAt(LocalDateTime.now().plus(config.getCodeTtl()))
                .attempts(0)
                .build();
        phoneVerifications.save(challenge);

        boolean expose = config.isExposeCodeInResponse() && !isProductionProfile();
        if (!expose) {
            // No SMS provider is integrated. Logged at info so a developer can
            // complete the flow; when a provider exists this is where it is sent.
            log.info("[Auth] Phone verification code for user {} ({}): {}", userId, phone, code);
        }

        return new AuthResponses.PhoneChallenge(
                maskPhone(phone), challenge.getExpiresAt(),
                PhoneVerification.MAX_ATTEMPTS, expose ? code : null);
    }

    @Override
    @Transactional
    public AuthResponses.Profile confirmPhoneVerification(
            Long userId, AuthRequests.PhoneVerificationConfirm request) {

        PhoneVerification challenge = phoneVerifications.findLatestOutstanding(userId)
                .orElseThrow(() -> new BadRequestException(
                        "No verification is outstanding. Request a code first."));

        if (!challenge.isUsable()) {
            throw new BadRequestException(
                    "That code has expired or been tried too many times. Request a new one.");
        }

        if (!passwordEncoder.matches(request.code(), challenge.getCodeHash())) {
            // Counted and saved even though the request fails, so a guesser
            // exhausts the allowance rather than retrying indefinitely.
            challenge.setAttempts(challenge.getAttempts() + 1);
            phoneVerifications.save(challenge);
            throw new BadRequestException("That code is not correct");
        }

        challenge.setConfirmedAt(LocalDateTime.now());
        phoneVerifications.save(challenge);

        // The number is taken from the challenge, not from the profile. A user
        // who requested a code for one number and then edited their profile to
        // another must not be able to confirm the first against the second.
        User user = requireUser(userId);
        user.setPhone(challenge.getPhone());
        user.setPhoneVerified(true);
        return mapper.toProfile(users.save(user));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Passwords
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void forgotPassword(AuthRequests.ForgotPassword request) {
        users.findByEmailIgnoreCase(normaliseEmail(request.email())).ifPresent(user -> {
            String token = UUID.randomUUID().toString();
            user.setPasswordResetToken(token);
            user.setPasswordResetTokenExpiry(LocalDateTime.now().plusHours(1));
            users.save(user);
            sendQuietly(() -> emailService.sendPasswordResetEmail(
                    user.getEmail(), user.getFirstName(), token));
        });
        // Returns the same either way: an unauthenticated endpoint that
        // distinguishes known from unknown addresses is an account directory.
    }

    @Override
    @Transactional
    public void resetPassword(AuthRequests.ResetPassword request) {
        User user = users.findByPasswordResetToken(request.token())
                .orElseThrow(() -> new BadRequestException("That reset link is not valid"));

        if (user.getPasswordResetTokenExpiry() == null
                || user.getPasswordResetTokenExpiry().isBefore(LocalDateTime.now())) {
            throw new BadRequestException("That reset link has expired. Request a new one.");
        }

        user.setPassword(passwordEncoder.encode(request.newPassword()));
        user.setPasswordResetToken(null);
        user.setPasswordResetTokenExpiry(null);
        users.save(user);
        loginAttempts.clear(user);

        // Every session ends. A reset is what someone does when they believe the
        // account is compromised, and leaving the intruder's device signed in
        // would make the reset pointless.
        int ended = sessions.revokeAllForUser(
                user.getId(), SessionRevocationReason.CREDENTIALS_CHANGED, LocalDateTime.now(), null);
        log.info("[Auth] Password reset for user {} ended {} session(s)", user.getId(), ended);
    }

    @Override
    @Transactional
    public void changePassword(Long userId, Long currentSessionId, AuthRequests.ChangePassword request) {
        User user = requireUser(userId);

        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new BadCredentialsException("Your current password is not correct");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPassword())) {
            throw new BadRequestException("The new password must be different from the current one");
        }

        user.setPassword(passwordEncoder.encode(request.newPassword()));
        users.save(user);

        if (!request.keepOtherSessions()) {
            // Every other device, but not this one: the person who just proved
            // the old password stays where they are.
            int ended = sessions.revokeAllForUser(user.getId(),
                    SessionRevocationReason.CREDENTIALS_CHANGED, LocalDateTime.now(), currentSessionId);
            log.info("[Auth] Password change for user {} ended {} other session(s)", userId, ended);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Multi-factor
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public AuthResponses.MfaSetup beginMfaSetup(Long userId) {
        User user = requireUser(userId);
        if (user.isTotpEnabled()) {
            throw new BadRequestException(
                    "Multi-factor authentication is already on. Turn it off before setting it up again.");
        }

        String secret = totpService.generateSecret();
        // Stored but not trusted: enabled stays false until a code proves the
        // authenticator actually holds it, so an abandoned setup cannot lock
        // anyone out of their own account.
        user.setTotpSecret(secret);
        user.setTotpVerified(false);
        users.save(user);

        String issuer = properties.getMfa().getIssuerLabel();
        return new AuthResponses.MfaSetup(
                secret, totpService.provisioningUri(secret, user.getEmail(), issuer), issuer);
    }

    @Override
    @Transactional
    public AuthResponses.MfaActivation activateMfa(Long userId, Long currentSessionId,
                                                   AuthRequests.MfaActivate request) {
        User user = requireUser(userId);
        if (user.getTotpSecret() == null || user.getTotpSecret().isBlank()) {
            throw new BadRequestException("Start multi-factor setup before activating it");
        }
        if (!totpService.verify(user.getTotpSecret(), request.code(),
                properties.getMfa().getAllowedDriftSteps())) {
            throw new BadRequestException(
                    "That code is not valid. Check your authenticator and try the current code.");
        }

        user.setTotpEnabled(true);
        user.setTotpVerified(true);
        users.save(user);

        List<String> plain = issueRecoveryCodes(user);

        // Other devices signed in before the second factor existed did not pass
        // it. Turning it on and leaving them be would protect nothing.
        sessions.revokeAllForUser(userId, SessionRevocationReason.MFA_CHANGED,
                LocalDateTime.now(), currentSessionId);

        log.info("[Auth] Multi-factor enabled for user {}", userId);
        return new AuthResponses.MfaActivation(plain, plain.size());
    }

    @Override
    @Transactional
    public void disableMfa(Long userId, AuthRequests.MfaConfirm request) {
        User user = requireUser(userId);
        // Re-checking the password matters here: without it a borrowed unlocked
        // phone is enough to strip the second factor off the account.
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BadCredentialsException("Your password is not correct");
        }

        user.setTotpEnabled(false);
        user.setTotpVerified(false);
        user.setTotpSecret(null);
        users.save(user);
        recoveryCodes.deleteAllForUser(userId);

        sessions.revokeAllForUser(userId, SessionRevocationReason.MFA_CHANGED, LocalDateTime.now(), null);
        log.info("[Auth] Multi-factor disabled for user {}", userId);
    }

    @Override
    @Transactional
    public AuthResponses.MfaActivation regenerateRecoveryCodes(Long userId, AuthRequests.MfaConfirm request) {
        User user = requireUser(userId);
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BadCredentialsException("Your password is not correct");
        }
        if (!user.isTotpEnabled()) {
            throw new BadRequestException("Multi-factor authentication is not on for this account");
        }

        List<String> plain = issueRecoveryCodes(user);
        return new AuthResponses.MfaActivation(plain, plain.size());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  External identity
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public AuthResponses.Tokens completeOAuthLogin(String provider, AuthRequests.OAuthCallback request) {
        User user = oauthExchange.resolveUser(provider, request);
        requireUsableAccount(user);
        return sessionFactory.open(user, request.deviceLabel(), mapper.toProfile(user));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Shared
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Checks the authenticator code, or spends a recovery code.
     *
     * <p>Recovery codes are BCrypt hashed with a per-code salt, so there is
     * nothing to look up by: every unused code has to be compared. There are at
     * most ten.
     */
    private boolean passesSecondFactor(User user, AuthRequests.Login request) {
        if (!isBlank(request.totpCode())
                && totpService.verify(user.getTotpSecret(), request.totpCode(),
                        properties.getMfa().getAllowedDriftSteps())) {
            return true;
        }
        if (isBlank(request.recoveryCode())) {
            return false;
        }
        String presented = request.recoveryCode().replace("-", "").trim().toUpperCase(Locale.ROOT);
        for (MfaRecoveryCode candidate : recoveryCodes.findUnusedByUserId(user.getId())) {
            if (passwordEncoder.matches(presented, candidate.getCodeHash())) {
                candidate.setUsedAt(LocalDateTime.now());
                recoveryCodes.save(candidate);
                log.warn("[Auth] User {} signed in with a recovery code; {} remain",
                        user.getId(), recoveryCodes.countByUserIdAndUsedAtIsNull(user.getId()));
                return true;
            }
        }
        return false;
    }

    /** Replaces the whole set. A half-rotated batch is worse than none. */
    private List<String> issueRecoveryCodes(User user) {
        recoveryCodes.deleteAllForUser(user.getId());

        int count = properties.getMfa().getRecoveryCodeCount();
        List<String> plain = new ArrayList<>(count);
        List<MfaRecoveryCode> rows = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            String raw = randomRecoveryCode();
            plain.add(raw.substring(0, 5) + "-" + raw.substring(5));   // grouped, for reading aloud
            rows.add(MfaRecoveryCode.builder()
                    .user(user)
                    .codeHash(passwordEncoder.encode(raw))
                    .build());
        }
        recoveryCodes.saveAll(rows);
        return plain;
    }

    /** Excludes I, O, 0 and 1: these get read off paper and typed by hand. */
    private static String randomRecoveryCode() {
        StringBuilder out = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            out.append(RECOVERY_ALPHABET.charAt(RANDOM.nextInt(RECOVERY_ALPHABET.length())));
        }
        return out.toString();
    }

    private static String numericCode(int digits) {
        StringBuilder out = new StringBuilder(digits);
        for (int i = 0; i < digits; i++) {
            out.append(RANDOM.nextInt(10));
        }
        return out.toString();
    }

    private User requireUser(Long userId) {
        return users.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }

    private void requireUsableAccount(User user) {
        if (!user.isEnabled()) {
            throw new DisabledException("This account is disabled. Contact support.");
        }
        if (user.isBlocked()) {
            throw new LockedException("This account has been blocked. Contact support.");
        }
    }

    private void requireNotLockedOut(User user) {
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now())) {
            throw new LockedException(
                    "Too many failed sign-in attempts. Try again later or reset your password.");
        }
    }

    /**
     * Shows enough of a number to recognise, not enough to dial.
     *
     * <p>Returned after a code is sent so the user can tell whether it went to
     * the number they expected — which only helps if the number is not simply
     * repeated back.
     */
    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) {
            return "•••";
        }
        return "•••" + phone.substring(phone.length() - 3);
    }

    /**
     * Email must never stop a flow that has already changed the database.
     *
     * <p>An unreachable SMTP host would otherwise roll back a completed
     * registration, so the account the user just created would not exist while
     * their password manager believes it does.
     */
    private void sendQuietly(Runnable send) {
        try {
            send.run();
        } catch (Exception e) {
            log.warn("[Auth] Could not send a message: {}", e.getMessage());
        }
    }

    private boolean isProductionProfile() {
        for (String profile : environment.getActiveProfiles()) {
            if (profile.equalsIgnoreCase("prod") || profile.equalsIgnoreCase("production")) {
                return true;
            }
        }
        return false;
    }

    private static String normaliseEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static String defaulted(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }
}
