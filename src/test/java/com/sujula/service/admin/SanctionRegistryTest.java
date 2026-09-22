package com.sujula.service.admin;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.sujula.exceptions.BadRequestException;
import com.sujula.model.admin.Sanction;
import com.sujula.model.auth.UserSession;
import com.sujula.model.constant.ModerationReason;
import com.sujula.model.constant.SanctionType;
import com.sujula.model.constant.UserRole;
import com.sujula.model.user.User;
import com.sujula.repository.admin.SanctionRepository;
import com.sujula.repository.auth.UserSessionRepository;
import com.sujula.repository.user.UserRepository;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locking somebody out, and letting them back in.
 *
 * <p>The sharpest claims: no account is locked without a row saying who decided
 * it and why; a suspension ends on its date whether or not any job runs; and
 * lifting one leaves the history intact, because "we suspended you and then
 * agreed we should not have" is a different thing from "nothing happened".
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(SanctionRegistry.class)
class SanctionRegistryTest {

    @Autowired private SanctionRegistry registry;
    @Autowired private SanctionRepository sanctions;
    @Autowired private UserSessionRepository sessions;
    @Autowired private UserRepository users;
    @Autowired private EntityManager entityManager;

    private User seller;
    private User admin;

    @BeforeEach
    void setUp() {
        seller = user("lamin@sujula.gm", UserRole.VENDOR);
        admin = user("ops@sujula.gm", UserRole.ADMIN);
        entityManager.flush();
    }

    /**
     * Re-reads the account.
     *
     * <p>Not {@code refresh}: issuing a lock revokes every session through a
     * bulk update that clears the persistence context, so the instance this test
     * is holding is detached by then. A caller would re-read too.
     */
    private User reload(User user) {
        return users.findById(user.getId()).orElseThrow();
    }

    private User user(String email, UserRole role) {
        User person = new User();
        person.setEmail(email);
        person.setPassword("x");
        person.setFirstName("A");
        person.setLastName("Person");
        person.setRole(role);
        return users.save(person);
    }

    private Sanction suspension(LocalDateTime until) {
        return Sanction.builder()
                .type(SanctionType.SUSPENSION)
                .reason(ModerationReason.OFF_PLATFORM_PAYMENT)
                .reasonText("Asking buyers to pay by mobile money outside the platform.")
                .expiresAt(until)
                .issuedBy(admin)
                .build();
    }

    // ── The record is the reason ─────────────────────────────────────────────

    @Test
    void aSuspensionLocksTheAccountAndTheRowSaysWhoDecidedItAndWhy() {
        registry.issue(seller, suspension(LocalDateTime.now().plusDays(7)));
        entityManager.flush();

        assertFalse(reload(seller).isEnabled());
        Sanction row = sanctions.findByUserIdOrderByCreatedAtDesc(seller.getId()).get(0);
        assertEquals(admin.getId(), row.getIssuedBy().getId());
        assertTrue(row.getReasonText().contains("outside the platform"));
        assertNotNull(row.getExpiresAt());
    }

    @Test
    void aSanctionWithNoReasonIsRefused() {
        Sanction noReason = Sanction.builder()
                .type(SanctionType.SUSPENSION)
                .expiresAt(LocalDateTime.now().plusDays(1))
                .issuedBy(admin).build();

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> registry.issue(seller, noReason));
        // The first person to need it is the administrator being asked about it
        // by somebody who has lost their livelihood.
        assertTrue(refused.getMessage().contains("nobody can defend"));
    }

    @Test
    void aSuspensionWithNoEndDateIsRefusedBecauseThatIsABan() {
        Sanction forever = Sanction.builder()
                .type(SanctionType.SUSPENSION)
                .reasonText("Repeated non-delivery.")
                .issuedBy(admin).build();

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> registry.issue(seller, forever));
        assertTrue(refused.getMessage().contains("that is a ban"));
    }

    @Test
    void aBanCannotCarryAnEndDate() {
        Sanction confused = Sanction.builder()
                .type(SanctionType.BAN)
                .reasonText("Identity fraud.")
                .expiresAt(LocalDateTime.now().plusYears(1))
                .issuedBy(admin).build();

        assertThrows(BadRequestException.class, () -> registry.issue(seller, confused));
    }

    @Test
    void aFeatureRestrictionHasToSayWhichFeature() {
        Sanction vague = Sanction.builder()
                .type(SanctionType.FEATURE_RESTRICTION)
                .reasonText("Too many reported listings.")
                .expiresAt(LocalDateTime.now().plusDays(30))
                .issuedBy(admin).build();

        assertThrows(BadRequestException.class, () -> registry.issue(seller, vague));
    }

    @Test
    void aWarningIsRecordedWithoutLockingAnything() {
        registry.issue(seller, Sanction.builder()
                .type(SanctionType.WARNING)
                .reasonText("First listing removed for a prohibited item.")
                .issuedBy(admin).build());
        entityManager.flush();

        // Worth a row rather than a note: three warnings is a pattern, and a
        // pattern is what justifies the next step being heavier.
        assertTrue(reload(seller).isEnabled());
        assertEquals(1, sanctions.countHistory(seller.getId()));
    }

    // ── The lock takes effect where the person actually is ───────────────────

    @Test
    void lockingAnAccountEndsTheSessionsOnTheDeviceInTheirHand() {
        UserSession phone = sessions.save(UserSession.builder()
                .user(seller).refreshTokenHash("hash-phone").deviceLabel("Infinix")
                .createdAt(LocalDateTime.now()).lastSeenAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusDays(30))
                .build());
        entityManager.flush();

        registry.issue(seller, suspension(LocalDateTime.now().plusDays(7)));
        entityManager.flush();

        // Otherwise a suspension only takes effect the next time they are asked
        // to log in, which for a phone app is never.
        assertNotNull(sessions.findById(phone.getId()).orElseThrow().getRevokedAt());
    }

    // ── Expiry needs nothing to run ──────────────────────────────────────────

    @Test
    void aSuspensionIsOverOnItsDateWhetherOrNotAnythingSweptIt() {
        Sanction lapsed = sanctions.save(Sanction.builder()
                .user(seller).type(SanctionType.SUSPENSION)
                .reasonText("A week for off-platform payment.")
                .expiresAt(LocalDateTime.now().minusHours(1))
                .issuedBy(admin).build());
        seller.setEnabled(false);
        users.save(seller);
        entityManager.flush();

        // Read from the rows rather than from the flag, so an outage cannot
        // leave somebody locked out for an extra week.
        assertFalse(registry.isLockedOut(seller.getId()));
        assertFalse(lapsed.isActiveAt(LocalDateTime.now()));
    }

    @Test
    void theSweepPutsTheCachedFlagBackAndIsIdempotent() {
        sanctions.save(Sanction.builder()
                .user(seller).type(SanctionType.SUSPENSION)
                .reasonText("A week.").expiresAt(LocalDateTime.now().minusHours(1))
                .issuedBy(admin).build());
        seller.setEnabled(false);
        users.save(seller);
        entityManager.flush();

        assertEquals(1, registry.sweepLapsedLocks());
        entityManager.flush();
        assertTrue(reload(seller).isEnabled());

        // Running it again changes nothing, which is what makes it safe on a
        // schedule.
        assertEquals(0, registry.sweepLapsedLocks());
    }

    // ── Lifting keeps the history ────────────────────────────────────────────

    @Test
    void liftingASanctionLeavesTheRowSayingItHappened() {
        Sanction issued = registry.issue(seller, suspension(LocalDateTime.now().plusDays(7)));
        entityManager.flush();

        registry.lift(issued, admin, "The buyer withdrew the complaint.");
        entityManager.flush();

        assertTrue(reload(seller).isEnabled());
        Sanction row = sanctions.findById(issued.getId()).orElseThrow();
        // Stamped, not deleted. "We suspended you and then agreed we should not
        // have" is a different history from "nothing happened".
        assertNotNull(row.getLiftedAt());
        assertEquals(admin.getId(), row.getLiftedBy().getId());
        assertTrue(row.getLiftedReason().contains("withdrew"));
        assertEquals(1, sanctions.countHistory(seller.getId()));
    }

    @Test
    void liftingWithNoReasonIsRefused() {
        Sanction issued = registry.issue(seller, suspension(LocalDateTime.now().plusDays(7)));

        assertThrows(BadRequestException.class, () -> registry.lift(issued, admin, "  "));
    }

    @Test
    void liftingTwiceIsRefused() {
        Sanction issued = registry.issue(seller, suspension(LocalDateTime.now().plusDays(7)));
        registry.lift(issued, admin, "Mistake.");

        assertThrows(BadRequestException.class, () -> registry.lift(issued, admin, "Again."));
    }

    @Test
    void oneLapsedSuspensionDoesNotUnlockAnAccountAnotherStillHolds() {
        registry.issue(seller, suspension(LocalDateTime.now().plusDays(7)));
        sanctions.save(Sanction.builder()
                .user(seller).type(SanctionType.BAN)
                .reasonText("Identity fraud.").issuedBy(admin).build());
        entityManager.flush();

        registry.rederive(reload(seller));
        entityManager.flush();

        assertFalse(reload(seller).isEnabled());
    }

    // ── Restrictions are narrower than locks ─────────────────────────────────

    @Test
    void aFeatureRestrictionStopsOneThingRatherThanTheAccount() {
        registry.issue(seller, Sanction.builder()
                .type(SanctionType.FEATURE_RESTRICTION)
                .reasonText("Listings pending review after three removals.")
                .restrictedPermission("CATALOGUE_WRITE")
                .expiresAt(LocalDateTime.now().plusDays(30))
                .issuedBy(admin).build());
        entityManager.flush();

        assertTrue(reload(seller).isEnabled(), "they can still sign in and answer their buyers");
        assertTrue(registry.isRestricted(seller.getId(), "CATALOGUE_WRITE"));
        assertFalse(registry.isRestricted(seller.getId(), "VENDOR_ORDER_FULFIL"));
    }

    // ── Re-deriving changes nothing ──────────────────────────────────────────

    @Test
    void rederivingOverExistingRowsChangesNothing() {
        registry.issue(seller, suspension(LocalDateTime.now().plusDays(7)));
        entityManager.flush();
        boolean before = reload(seller).isEnabled();

        // If this ever changes something, the flag had drifted and the sanctions
        // were right.
        assertFalse(registry.rederive(reload(seller)));
        assertEquals(before, reload(seller).isEnabled());
    }

    @Test
    void activeSanctionsAreWhatIsBitingRatherThanEverythingEverIssued() {
        registry.issue(seller, Sanction.builder()
                .type(SanctionType.WARNING).reasonText("First warning.").issuedBy(admin).build());
        Sanction lifted = registry.issue(seller, Sanction.builder()
                .type(SanctionType.WARNING).reasonText("Second warning.").issuedBy(admin).build());
        registry.lift(lifted, admin, "Wrong account.");
        entityManager.flush();

        List<Sanction> active = registry.activeOn(seller.getId());
        assertEquals(1, active.size(), "the lifted one is no longer biting");
        assertEquals(2, sanctions.countHistory(seller.getId()),
                "but the history keeps both, because a lifted sanction still happened");
    }
}
