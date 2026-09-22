package com.sujula.service.admin;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.sujula.dto.request.admin.AdminUserRequests;
import com.sujula.dto.response.admin.AdminUserResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.model.auth.UserSession;
import com.sujula.model.constant.ModerationReason;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.UserRole;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.admin.SanctionRepository;
import com.sujula.repository.auth.UserSessionRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.AuditService;
import com.sujula.service.EmailService;
import com.sujula.service.admin.impl.AdminUserServiceImpl;
import com.sujula.service.auth.TokenService;
import com.sujula.service.security.StepUpVerifier;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What an administrator may do to somebody else's account.
 *
 * <p>The sharpest claims: no account is locked except through a sanction, an
 * administrator cannot act on their own account, impersonation leaves a row and
 * cannot reach another member of staff, and no credential ever appears on the
 * screen.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({AdminUserServiceImpl.class, SanctionRegistry.class,
         AdminUserServiceTest.Encoders.class})
class AdminUserServiceTest {

    @org.springframework.boot.test.context.TestConfiguration
    static class Encoders {
        @org.springframework.context.annotation.Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(4);
        }
    }

    @Autowired private AdminUserServiceImpl admin;
    @Autowired private SanctionRepository sanctions;
    @Autowired private UserSessionRepository sessions;
    @Autowired private UserRepository users;
    @Autowired private VendorRepository vendors;
    @Autowired private EntityManager entityManager;

    @MockitoBean private AuditService audit;
    @MockitoBean private EmailService email;
    @MockitoBean private StepUpVerifier stepUp;
    @MockitoBean private TokenService tokens;

    private User operator;
    private User support;
    private User buyer;

    @BeforeEach
    void setUp() {
        operator = user("ops@sujula.gm", "Ops", UserRole.ADMIN);
        support = user("desk@sujula.gm", "Desk", UserRole.SUPPORT);
        buyer = user("fatou@example.gm", "Fatou", UserRole.CUSTOMER);
        when(tokens.mintAccessToken(any(), any(), any())).thenReturn("minted.access.token");
        entityManager.flush();
    }

    private User user(String email, String firstName, UserRole role) {
        User person = new User();
        person.setEmail(email);
        person.setPassword("$2a$04$abcdefghijklmnopqrstuv");
        person.setFirstName(firstName);
        person.setLastName("Person");
        person.setRole(role);
        return users.save(person);
    }

    private User reload(User user) {
        return users.findById(user.getId()).orElseThrow();
    }

    // ── Locking goes through the registry ────────────────────────────────────

    @Test
    void suspendingIssuesASanctionRatherThanSettingAFlag() {
        AdminUserResponses.AccountChanged changed = admin.suspend(operator, buyer.getId(),
                new AdminUserRequests.Suspend(7, "Repeated chargebacks.",
                        ModerationReason.IDENTITY_FRAUD));
        entityManager.flush();

        assertFalse(changed.enabled());
        assertFalse(reload(buyer).isEnabled());
        // The row is the reason. A flag set directly would be a punishment
        // nobody can explain when the person asks.
        assertEquals(1, sanctions.countHistory(buyer.getId()));
        assertNotNull(changed.sanction().expiresAt());
        assertTrue(changed.message().contains("comes back by itself"));
    }

    @Test
    void deactivatingIsTheSameMechanismWithNoEndDate() {
        admin.deactivate(operator, buyer.getId(),
                new AdminUserRequests.Deactivate("Account closed at their request.", null));
        entityManager.flush();

        assertFalse(reload(buyer).isEnabled());
        // One writer, two names in the API. Calling them different things in
        // the data would be a second path to enabled=false.
        assertNull(sanctions.findByUserIdOrderByCreatedAtDesc(buyer.getId()).get(0).getExpiresAt());
    }

    @Test
    void activatingLiftsWhatIsHoldingThemAndKeepsTheHistory() {
        admin.suspend(operator, buyer.getId(),
                new AdminUserRequests.Suspend(7, "A mistake.", null));
        entityManager.flush();

        admin.activate(operator, buyer.getId(),
                new AdminUserRequests.Activate("The complaint was withdrawn."));
        entityManager.flush();

        assertTrue(reload(buyer).isEnabled());
        assertNotNull(sanctions.findByUserIdOrderByCreatedAtDesc(buyer.getId())
                .get(0).getLiftedAt());
        assertEquals(1, sanctions.countHistory(buyer.getId()), "the row survives being lifted");
    }

    @Test
    void activatingAnAccountThatWasNotLockedAnswersPlainlyRatherThanFailing() {
        AdminUserResponses.AccountChanged changed = admin.activate(operator, buyer.getId(),
                new AdminUserRequests.Activate("Checking."));

        // Two agents answering the same complaint should not see an error.
        assertTrue(changed.enabled());
        assertTrue(changed.message().contains("was not locked out"));
    }

    @Test
    void lockingEndsTheSessionsOnTheDeviceInTheirHand() {
        UserSession phone = sessions.save(UserSession.builder()
                .user(buyer).refreshTokenHash("h1").deviceLabel("Infinix")
                .createdAt(LocalDateTime.now()).lastSeenAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusDays(30)).build());
        entityManager.flush();

        admin.deactivate(operator, buyer.getId(),
                new AdminUserRequests.Deactivate("Fraud.", ModerationReason.IDENTITY_FRAUD));
        entityManager.flush();

        assertNotNull(sessions.findById(phone.getId()).orElseThrow().getRevokedAt());
    }

    // ── An administrator cannot act on themselves ────────────────────────────

    @Test
    void anAdministratorCannotSuspendTheirOwnAccount() {
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> admin.suspend(operator, operator.getId(),
                        new AdminUserRequests.Suspend(1, "Testing.", null)));
        // Otherwise they lock everybody out of the thing that could unlock them.
        assertTrue(refused.getMessage().contains("second person is the point"));
    }

    @Test
    void norChangeTheirOwnRole() {
        assertThrows(BadRequestException.class,
                () -> admin.changeRole(operator, operator.getId(),
                        new AdminUserRequests.ChangeRole(UserRole.ADMIN, "Promoting myself.", null)));
    }

    @Test
    void norImpersonateThemselves() {
        assertThrows(BadRequestException.class,
                () -> admin.impersonate(operator, operator.getId(),
                        new AdminUserRequests.Impersonate("Testing.", null, 15)));
    }

    // ── Roles ────────────────────────────────────────────────────────────────

    @Test
    void changingARoleEndsEverySessionBecauseTheRoleLivesInTheToken() {
        sessions.save(UserSession.builder()
                .user(buyer).refreshTokenHash("h2").deviceLabel("Laptop")
                .createdAt(LocalDateTime.now()).lastSeenAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusDays(30)).build());
        entityManager.flush();

        AdminUserResponses.RoleChanged changed = admin.changeRole(operator, buyer.getId(),
                new AdminUserRequests.ChangeRole(UserRole.DELIVERY, "Signed up as a driver.", null));
        entityManager.flush();

        assertEquals(UserRole.CUSTOMER, changed.from());
        assertEquals(UserRole.DELIVERY, changed.to());
        // A demotion with a live token is the direction that matters.
        assertEquals(1, changed.sessionsEnded());
        assertEquals(UserRole.DELIVERY, reload(buyer).getRole());
    }

    @Test
    void anAccountThatRunsAStoreCannotSimplyStopBeingAVendor() {
        User sellerUser = user("lamin@sujula.gm", "Lamin", UserRole.VENDOR);
        vendors.save(Vendor.builder()
                .user(sellerUser).storeName("Kombo Electronics").storeSlug("kombo-admin")
                .status(PartnerStatus.APPROVED).settlementCurrency("GMD").pickupCountryCode("GM")
                .build());
        entityManager.flush();

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> admin.changeRole(operator, sellerUser.getId(),
                        new AdminUserRequests.ChangeRole(UserRole.CUSTOMER, "They quit.", null)));
        // Its orders would be left with nobody accountable for them.
        assertTrue(refused.getMessage().contains("Suspend or close the store first"));
    }

    @Test
    void supportCannotCreateAStaffAccount() {
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> admin.create(support, new AdminUserRequests.CreateUser(
                        "new.admin@sujula.gm", "New", "Admin", null, UserRole.ADMIN, "GM",
                        "Because I want to.")));
        assertTrue(refused.getMessage().contains("Only an administrator"));
    }

    // ── Creating ─────────────────────────────────────────────────────────────

    @Test
    void anAccountCreatedByHandGetsAPasswordNobodyKnows() {
        AdminUserResponses.UserCreated created = admin.create(operator,
                new AdminUserRequests.CreateUser("ebrima@sujula.gm", "Ebrima", "Sanneh",
                        "+2203100099", UserRole.DELIVERY, "GM", "Signed up at the desk."));
        entityManager.flush();

        User made = users.findById(created.id()).orElseThrow();
        assertEquals(UserRole.DELIVERY, made.getRole());
        // Not verified: creating an account for somebody does not prove the
        // address reaches them.
        assertFalse(made.isEmailVerified());
        // The stored secret is not anything that was sent in or handed back —
        // the request carries no password field and the response has none, so
        // there is nothing an administrator could sign in with.
        assertNotNull(made.getPassword());
        assertTrue(created.setupEmailSent());

        // And the setup email is the one that contains no password, rather than
        // the reset email, which prints its argument as a temporary one.
        verify(email).sendAccountSetupEmail(eq("ebrima@sujula.gm"), eq("Ebrima"),
                eq("DELIVERY"), eq("ops@sujula.gm"));
        verify(email, org.mockito.Mockito.never())
                .sendAdminPasswordResetEmail(anyString(), anyString(), anyString());
    }

    @Test
    void aDuplicateEmailIsRefused() {
        assertThrows(BadRequestException.class, () -> admin.create(operator,
                new AdminUserRequests.CreateUser("fatou@example.gm", "Fatou", null, null,
                        UserRole.CUSTOMER, "GM", "Duplicate.")));
    }

    // ── Editing ──────────────────────────────────────────────────────────────

    @Test
    void changingAPhoneNumberClearsItsVerification() {
        buyer.setPhone("+2203100001");
        buyer.setPhoneVerified(true);
        users.save(buyer);
        entityManager.flush();

        admin.patch(operator, buyer.getId(), new AdminUserRequests.PatchUser(
                null, null, "+2203100077", null, null, null, "They rang to correct it."));
        entityManager.flush();

        // Carrying it across would let an administrator hand somebody a verified
        // number they do not hold.
        assertFalse(reload(buyer).isPhoneVerified());
    }

    @Test
    void anEditThatChangesNothingWritesNoAuditRow() {
        admin.patch(operator, buyer.getId(), new AdminUserRequests.PatchUser(
                "Fatou", null, null, null, null, null, "No change."));

        // An audit row for a request that changed nothing is noise in the place
        // noise is least affordable.
        verify(audit, org.mockito.Mockito.never())
                .record(any(), anyString(), any(), anyString(), anyString(), anyString());
    }

    // ── MFA ──────────────────────────────────────────────────────────────────

    @Test
    void resettingSomebodyElsesSecondFactorRequiresYourOwnCredentialsFirst() {
        buyer.setTotpEnabled(true);
        buyer.setTotpSecret("SECRET");
        users.save(buyer);
        entityManager.flush();

        AdminUserResponses.MfaReset reset = admin.resetMfa(operator, buyer.getId(),
                new AdminUserRequests.ResetMfa("admin-password", "123456", "They lost the phone."));
        entityManager.flush();

        // The step-up runs before anything is touched: this endpoint turns one
        // account takeover into a takeover of anybody.
        verify(stepUp).verify(eq(operator), eq("admin-password"), eq("123456"), anyString());
        assertTrue(reset.wasEnabled());
        assertNull(reload(buyer).getTotpSecret());
        assertFalse(reload(buyer).isTotpEnabled());
    }

    // ── Impersonation ────────────────────────────────────────────────────────

    @Test
    void impersonationIsShortFlaggedAndVisibleInTheUsersOwnDeviceList() {
        AdminUserResponses.ImpersonationOpened opened = admin.impersonate(operator, buyer.getId(),
                new AdminUserRequests.Impersonate("Buyer cannot see their order.", "SJL-1", 15));
        entityManager.flush();

        assertNotNull(opened.accessToken());
        assertTrue(opened.expiresAt().isBefore(LocalDateTime.now().plusMinutes(16)));
        assertTrue(opened.warning().contains("cannot be extended"));

        UserSession session = sessions.findById(opened.sessionId()).orElseThrow();
        assertEquals(operator.getId(), session.getImpersonatedByUserId());
        assertTrue(session.isImpersonated());
        // Named, so it reads as a support session rather than a device nobody
        // recognises.
        assertTrue(session.getDeviceLabel().contains("ops@sujula.gm"));
        assertEquals("Buyer cannot see their order.", session.getImpersonationReason());

        // The administrator's id goes into the token, so every client can show
        // the banner without a second request.
        verify(tokens).mintAccessToken(any(), eq(opened.sessionId()), eq(operator.getId()));
    }

    @Test
    void anHourIsTheMostAndLongerIsClampedRatherThanRefused() {
        AdminUserResponses.ImpersonationOpened opened = admin.impersonate(operator, buyer.getId(),
                new AdminUserRequests.Impersonate("Long investigation.", null, 600));

        assertTrue(opened.expiresAt().isBefore(LocalDateTime.now().plusMinutes(61)));
    }

    @Test
    void oneMemberOfStaffCannotImpersonateAnother() {
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> admin.impersonate(operator, support.getId(),
                        new AdminUserRequests.Impersonate("Checking their queue.", null, 15)));
        // Anything else would put one person's actions under another's name.
        assertTrue(refused.getMessage().contains("under their name"));
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    @Test
    void theProfileCarriesNoCredentialOfAnyKind() {
        buyer.setTotpSecret("JBSWY3DPEHPK3PXP");
        buyer.setPasswordResetToken("reset-token-value");
        users.save(buyer);
        sessions.save(UserSession.builder()
                .user(buyer).refreshTokenHash("very-secret-hash").deviceLabel("Infinix")
                .createdAt(LocalDateTime.now()).lastSeenAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusDays(30)).build());
        entityManager.flush();

        String rendered = admin.detail(operator, buyer.getId()).toString();

        assertFalse(rendered.contains("JBSWY3DPEHPK3PXP"), "no TOTP secret");
        assertFalse(rendered.contains("reset-token-value"), "no reset token");
        assertFalse(rendered.contains("very-secret-hash"), "no session token hash");
        assertFalse(rendered.contains("$2a$"), "no password hash");
    }

    @Test
    void theListSaysWhoIsLockedOutFromTheSanctionsRatherThanTheFlag() {
        admin.suspend(operator, buyer.getId(),
                new AdminUserRequests.Suspend(7, "Off-platform payment.",
                        ModerationReason.OFF_PLATFORM_PAYMENT));
        entityManager.flush();

        AdminUserResponses.UserRow row = admin
                .search(operator, "fatou", null, null, null, null, PageRequest.of(0, 10))
                .getContent().get(0);

        assertNotNull(row.lockedBy());
        assertTrue(row.lockedBy().contains("Off-platform payment"));
        assertNotNull(row.lockedUntil());
    }

    @Test
    void theSearchMatchesNameEmailAndPhoneAndNotEverythingElse() {
        assertEquals(1, admin.search(operator, "fatou@example.gm", null, null, null, null,
                PageRequest.of(0, 10)).getTotalElements());
        assertEquals(1, admin.search(operator, "Fatou", null, null, null, null,
                PageRequest.of(0, 10)).getTotalElements());
        assertEquals(0, admin.search(operator, "no-such-person", null, null, null, null,
                PageRequest.of(0, 10)).getTotalElements());
        assertEquals(1, admin.search(operator, null, UserRole.SUPPORT, null, null, null,
                PageRequest.of(0, 10)).getTotalElements());
    }
}
