package com.sujula.service.admin;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.exceptions.BadRequestException;
import com.sujula.model.admin.ModerationCase;
import com.sujula.model.admin.Sanction;
import com.sujula.model.constant.SanctionType;
import com.sujula.model.constant.SessionRevocationReason;
import com.sujula.model.user.User;
import com.sujula.repository.admin.SanctionRepository;
import com.sujula.repository.auth.UserSessionRepository;
import com.sujula.repository.user.UserRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * The only thing that locks somebody out, and the only thing that lets them
 * back in.
 *
 * <p>{@code CustodyChain}'s shape again, applied to people rather than parcels.
 * The sanctions are the record; {@code users.enabled} is the consequence,
 * recomputed from the whole history every time one is issued or lifted. There is
 * no method anywhere that disables an account, so no account can be locked
 * without a row saying who decided it, on what grounds and until when — and the
 * first person to need that row is the administrator being asked about it by
 * somebody who has lost their livelihood.
 *
 * <p><strong>Expiry needs nothing to run.</strong> A suspension is over the
 * moment its date passes, because every gate resolves against the sanctions
 * rather than against the flag. {@link #rederive} exists so the cached flag
 * catches up, and a job that fails to call it leaves people able to sign in,
 * which is the safe direction for a failure to fall.
 */
@Slf4j
@Component
public class SanctionRegistry {

    private final SanctionRepository sanctions;
    private final UserRepository users;
    private final UserSessionRepository sessions;

    public SanctionRegistry(SanctionRepository sanctions, UserRepository users,
                            UserSessionRepository sessions) {
        this.sanctions = sanctions;
        this.users = users;
        this.sessions = sessions;
    }

    /**
     * Records a sanction and applies what follows from it.
     *
     * <p>The single door. The sanction is checked, saved, and then the account's
     * state is recomputed from the whole history — not patched — so the flag is
     * a function of the rows and cannot drift from them.
     *
     * <p>A lock also ends every session the person has open. Leaving them signed
     * in on the device in their hand would make a suspension something that only
     * takes effect the next time they are asked to log in, which for a phone
     * app is never.
     */
    @Transactional
    public Sanction issue(User subject, Sanction sanction) {
        requireSane(sanction);
        sanction.setUser(subject);
        Sanction saved = sanctions.save(sanction);

        rederive(subject);

        if (saved.locksTheAccount() && saved.isActiveAt(LocalDateTime.now())) {
            int ended = sessions.revokeAllForUser(subject.getId(),
                    SessionRevocationReason.ADMIN, LocalDateTime.now(), null);
            log.info("[Sanctions] {} on user {} ended {} session(s)",
                    saved.getType(), subject.getId(), ended);
        }

        log.info("[Sanctions] {} issued on user {} by {} — {}", saved.getType(), subject.getId(),
                saved.getIssuedBy() == null ? "the platform" : saved.getIssuedBy().getId(),
                saved.getReasonText());
        return saved;
    }

    /**
     * Takes a sanction back, without pretending it never happened.
     *
     * <p>Stamped rather than deleted. "We suspended you for a week and then
     * agreed we should not have" is a different history from "nothing happened",
     * and only one of them is true — the person it was applied to knows which.
     */
    @Transactional
    public Sanction lift(Sanction sanction, User by, String why) {
        if (sanction.getLiftedAt() != null) {
            throw new BadRequestException("That sanction has already been lifted.");
        }
        if (why == null || why.isBlank()) {
            // Lifting is a decision like issuing, and it is the one somebody
            // will be asked about if the same account offends again.
            throw new BadRequestException(
                    "Say why it is being lifted. A sanction that was removed with no reason is one "
                            + "nobody can defend either way.");
        }
        sanction.setLiftedAt(LocalDateTime.now());
        sanction.setLiftedBy(by);
        sanction.setLiftedReason(why);
        Sanction saved = sanctions.save(sanction);

        rederive(sanction.getUser());
        log.info("[Sanctions] {} on user {} lifted by {} — {}", saved.getType(),
                sanction.getUser().getId(), by == null ? "the platform" : by.getId(), why);
        return saved;
    }

    /**
     * Recomputes an account's state from its whole sanction history.
     *
     * <p>Public so a repair job or a test can run it over existing rows and
     * assert it changes nothing. If it ever does, the flag had drifted and the
     * sanctions were right.
     */
    @Transactional
    public boolean rederive(User user) {
        LocalDateTime now = LocalDateTime.now();
        boolean locked = sanctions.findActive(user.getId(), now).stream()
                .anyMatch(Sanction::locksTheAccount);

        boolean shouldBeEnabled = !locked;
        if (user.isEnabled() == shouldBeEnabled) {
            return false;
        }
        user.setEnabled(shouldBeEnabled);
        users.save(user);
        log.info("[Sanctions] user {} is now {}", user.getId(),
                shouldBeEnabled ? "enabled" : "locked out");
        return true;
    }

    /** Whether this account is locked out right now, read from the rows. */
    @Transactional(readOnly = true)
    public boolean isLockedOut(Long userId) {
        return sanctions.findActive(userId, LocalDateTime.now()).stream()
                .anyMatch(Sanction::locksTheAccount);
    }

    /** Whether one named capability is restricted right now. */
    @Transactional(readOnly = true)
    public boolean isRestricted(Long userId, String permission) {
        return sanctions.findActive(userId, LocalDateTime.now()).stream()
                .anyMatch(row -> row.getType() == SanctionType.FEATURE_RESTRICTION
                        && permission.equals(row.getRestrictedPermission()));
    }

    /** What is biting on this account now, for the profile and the queue. */
    @Transactional(readOnly = true)
    public List<Sanction> activeOn(Long userId) {
        return sanctions.findActive(userId, LocalDateTime.now());
    }

    /**
     * Puts the cached flag back for accounts whose lock has lapsed.
     *
     * <p>Called on a schedule, and nothing depends on it having run: the gates
     * resolve against the sanctions themselves, so a missed sweep leaves people
     * able to sign in rather than locked out. That is the direction a failure
     * here should fall.
     *
     * @return how many accounts were put back
     */
    @Transactional
    public int sweepLapsedLocks() {
        LocalDateTime now = LocalDateTime.now();
        int restored = 0;
        for (Long userId : sanctions.findUsersWithLapsedLocks(now)) {
            User user = users.findById(userId).orElse(null);
            if (user != null && rederive(user)) {
                restored++;
            }
        }
        if (restored > 0) {
            log.info("[Sanctions] {} account(s) came back after their suspensions lapsed", restored);
        }
        return restored;
    }

    // ── Guards ───────────────────────────────────────────────────────────────

    private static void requireSane(Sanction sanction) {
        if (sanction.getReasonText() == null || sanction.getReasonText().isBlank()) {
            throw new BadRequestException(
                    "A sanction needs a reason the person can read. One without is one nobody can "
                            + "defend.");
        }
        SanctionType type = sanction.getType();
        if (type == null) {
            throw new BadRequestException("Say what kind of sanction this is.");
        }
        if (type.expires() && sanction.getExpiresAt() == null) {
            // A suspension with no end date is a ban, and calling it a
            // suspension would tell the person something untrue about when they
            // are getting their account back.
            throw new BadRequestException(
                    "A " + type.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
                            + " needs an end date. If it is meant to be permanent, that is a ban.");
        }
        if (!type.expires() && sanction.getExpiresAt() != null) {
            throw new BadRequestException(
                    "A " + type.name().toLowerCase(java.util.Locale.ROOT) + " does not end by "
                            + "itself, so it cannot carry an end date.");
        }
        if (sanction.getExpiresAt() != null
                && sanction.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BadRequestException("That end date has already passed.");
        }
        if (type == SanctionType.FEATURE_RESTRICTION
                && (sanction.getRestrictedPermission() == null
                    || sanction.getRestrictedPermission().isBlank())) {
            throw new BadRequestException("Say which capability is being restricted.");
        }
    }

    /** Attaches a case to a sanction after both exist, which is the usual order. */
    @Transactional
    public Sanction linkToCase(Sanction sanction, ModerationCase moderationCase) {
        sanction.setModerationCase(moderationCase);
        return sanctions.save(sanction);
    }
}
