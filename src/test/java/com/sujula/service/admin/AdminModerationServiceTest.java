package com.sujula.service.admin;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.sujula.dto.request.admin.AdminModerationRequests;
import com.sujula.dto.response.admin.AdminModerationResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.model.Review;
import com.sujula.model.admin.ModerationCase;
import com.sujula.model.constant.KycDocumentStatus;
import com.sujula.model.constant.KycDocumentType;
import com.sujula.model.constant.ModerationReason;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.PayoutStatus;
import com.sujula.model.constant.ProductStatus;
import com.sujula.model.constant.SanctionType;
import com.sujula.model.constant.UserRole;
import com.sujula.model.products.Product;
import com.sujula.model.store.KycDocument;
import com.sujula.model.user.Payout;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.admin.CommissionRateRepository;
import com.sujula.repository.admin.ModerationCaseRepository;
import com.sujula.repository.admin.SanctionRepository;
import com.sujula.repository.money.PayoutRepository;
import com.sujula.repository.product.ProductRepository;
import com.sujula.repository.product.ReviewRepository;
import com.sujula.repository.store.KycDocumentRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.AuditService;
import com.sujula.service.EmailService;
import com.sujula.service.admin.impl.AdminModerationServiceImpl;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who may trade, and what they may list.
 *
 * <p>The two claims worth the most here are the cascades. Suspending a store
 * takes its listings down and holds its money in one operation, because a
 * suspension applied in three places is one that gets applied in two. And a
 * commission change is effective-dated and never backwards, because an order
 * priced under one rate is an order settled under it.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({AdminModerationServiceImpl.class, SanctionRegistry.class})
class AdminModerationServiceTest {

    @Autowired private AdminModerationServiceImpl moderation;
    @Autowired private VendorRepository vendors;
    @Autowired private ProductRepository products;
    @Autowired private ReviewRepository reviews;
    @Autowired private KycDocumentRepository kyc;
    @Autowired private PayoutRepository payouts;
    @Autowired private CommissionRateRepository commissions;
    @Autowired private ModerationCaseRepository cases;
    @Autowired private SanctionRepository sanctions;
    @Autowired private UserRepository users;
    @Autowired private EntityManager entityManager;

    @MockitoBean private AuditService audit;
    @MockitoBean private EmailService email;

    private User operator;
    private User sellerUser;
    private Vendor kombo;
    private Product phone;

    @BeforeEach
    void setUp() {
        operator = user("ops@sujula.gm", UserRole.ADMIN);
        sellerUser = user("lamin@sujula.gm", UserRole.VENDOR);

        kombo = vendors.save(Vendor.builder()
                .user(sellerUser).storeName("Kombo Electronics").storeSlug("kombo-mod")
                .status(PartnerStatus.APPROVED).settlementCurrency("GMD").pickupCountryCode("GM")
                .defaultCommissionRate(new BigDecimal("10.00"))
                .build());

        phone = products.save(Product.builder()
                .name("Galaxy A16").slug("galaxy-a16-mod").vendor(kombo)
                .price(new BigDecimal("9700.00")).priceCurrency("GMD")
                .stock(3).active(true).country("GM")
                .status(ProductStatus.PUBLISHED)
                .build());

        entityManager.flush();
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

    private Vendor reloadStore() {
        return vendors.findById(kombo.getId()).orElseThrow();
    }

    // ── The suspension cascade ───────────────────────────────────────────────

    @Test
    void suspendingAStoreTakesItsListingsDownAndHoldsItsMoney() {
        Payout pending = payouts.save(Payout.builder()
                .user(sellerUser).vendor(kombo).amount(new BigDecimal("8000.00"))
                .currency("GMD").status(PayoutStatus.REQUESTED).reference("PO-1")
                .build());
        entityManager.flush();

        AdminModerationResponses.StoreDecision decided = moderation.suspendStore(
                operator, kombo.getId(), new AdminModerationRequests.SuspendStore(
                        "Buyers report goods never arriving.",
                        ModerationReason.NON_DELIVERY, true));
        entityManager.flush();

        assertEquals(PartnerStatus.SUSPENDED, reloadStore().getStatus());
        // One operation, three consequences. Applied in three places it gets
        // applied in two.
        assertEquals(1, decided.productsSuspended());
        assertEquals(ProductStatus.SUSPENDED,
                products.findById(phone.getId()).orElseThrow().getStatus());
        assertTrue(decided.payoutsHeld());
        assertEquals(PayoutStatus.ON_HOLD,
                payouts.findById(pending.getId()).orElseThrow().getStatus());
        assertTrue(reloadStore().arePayoutsHeld());
        // And a case, so there is a record of why.
        assertNotNull(decided.caseReference());
    }

    @Test
    void heldMoneyIsHeldRatherThanCancelledAndTheMessageSaysSo() {
        payouts.save(Payout.builder()
                .user(sellerUser).vendor(kombo).amount(new BigDecimal("8000.00"))
                .currency("GMD").status(PayoutStatus.REQUESTED).reference("PO-2").build());
        entityManager.flush();

        AdminModerationResponses.StoreDecision decided = moderation.suspendStore(
                operator, kombo.getId(), new AdminModerationRequests.SuspendStore(
                        "Under investigation.", null, true));

        // Telling the seller the wrong one of those is how a suspension becomes
        // a complaint about theft.
        assertTrue(decided.message().contains("waiting, not cancelled"));
        payouts.findAll().forEach(row ->
                assertFalse(row.getStatus() == PayoutStatus.CANCELLED, "nothing was cancelled"));
    }

    @Test
    void aSuspensionCanLeaveThePayoutsRunningWhenThatIsTheJudgement() {
        payouts.save(Payout.builder()
                .user(sellerUser).vendor(kombo).amount(new BigDecimal("8000.00"))
                .currency("GMD").status(PayoutStatus.REQUESTED).reference("PO-3").build());
        entityManager.flush();

        // A store stopped for an expired licence should still be paid for what
        // it already delivered. The two are different judgements, so it is asked
        // rather than assumed.
        AdminModerationResponses.StoreDecision decided = moderation.suspendStore(
                operator, kombo.getId(), new AdminModerationRequests.SuspendStore(
                        "Trading licence expired.", null, false));

        assertFalse(decided.payoutsHeld());
        assertFalse(reloadStore().arePayoutsHeld());
        assertEquals(PayoutStatus.REQUESTED, payouts.findAll().get(0).getStatus());
    }

    // ── Commission is effective-dated ────────────────────────────────────────

    @Test
    void aCommissionChangeIsDatedForwardAndTouchesNothingAlreadyPlaced() {
        LocalDateTime from = LocalDateTime.now().plusDays(14);

        AdminModerationResponses.CommissionChanged changed = moderation.changeCommission(
                operator, kombo.getId(), new AdminModerationRequests.ChangeCommission(
                        new BigDecimal("8.00"), from, "Volume agreed for the next quarter."));
        entityManager.flush();

        assertEquals(new BigDecimal("8.00"), changed.newRate());
        assertEquals(from, changed.effectiveFrom());
        // The question a seller asks, answered rather than left silent.
        assertEquals(0, changed.ordersAffected());
        assertTrue(changed.message().contains("changes nothing that has happened"));

        // The old rate is still what applies today.
        assertEquals(new BigDecimal("10.00"), reloadStore().getDefaultCommissionRate());
    }

    @Test
    void backdatingACommissionIsRefused() {
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> moderation.changeCommission(operator, kombo.getId(),
                        new AdminModerationRequests.ChangeCommission(
                                new BigDecimal("15.00"), LocalDateTime.now().minusDays(30),
                                "Correcting an error.")));
        // The money to claw back would have to come from somewhere.
        assertTrue(refused.getMessage().contains("already gone"));
    }

    @Test
    void anEarlierRateIsClosedRatherThanEditedSoOldStatementsStillExplain() {
        moderation.changeCommission(operator, kombo.getId(),
                new AdminModerationRequests.ChangeCommission(
                        new BigDecimal("12.00"), LocalDateTime.now().plusSeconds(1), "First."));
        entityManager.flush();
        moderation.changeCommission(operator, kombo.getId(),
                new AdminModerationRequests.ChangeCommission(
                        new BigDecimal("8.00"), LocalDateTime.now().plusDays(30), "Second."));
        entityManager.flush();

        assertEquals(2, commissions.findHistory(kombo.getId()).size());
        // "What was I paying in September" still has an answer.
        assertEquals(1, commissions.findHistory(kombo.getId()).stream()
                .filter(r -> r.getEffectiveUntil() != null).count());
    }

    // ── KYC gates approval ───────────────────────────────────────────────────

    @Test
    void aStoreWithDocumentsStillInTheQueueCannotBeApproved() {
        kombo.setStatus(PartnerStatus.PENDING);
        vendors.save(kombo);
        kyc.save(KycDocument.builder()
                .vendor(kombo).type(KycDocumentType.NATIONAL_ID)
                .status(KycDocumentStatus.SUBMITTED).fileUrl("https://media.invalid/id.jpg")
                .submittedAt(LocalDateTime.now()).build());
        entityManager.flush();

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> moderation.approveStore(operator, kombo.getId(), null));
        // Approving a seller nobody has identified is approving whoever is
        // behind the account — and the money then goes to them.
        assertTrue(refused.getMessage().contains("whoever is behind the account"));
    }

    @Test
    void acceptingTheLastDocumentMovesTheStoreOnByItself() {
        kombo.setStatus(PartnerStatus.PENDING_KYC);
        vendors.save(kombo);
        KycDocument document = kyc.save(KycDocument.builder()
                .vendor(kombo).type(KycDocumentType.NATIONAL_ID)
                .status(KycDocumentStatus.SUBMITTED).fileUrl("https://media.invalid/id.jpg")
                .submittedAt(LocalDateTime.now()).build());
        entityManager.flush();

        AdminModerationResponses.KycDecision decided =
                moderation.approveKyc(operator, document.getId(), null);
        entityManager.flush();

        // Otherwise a seller whose papers are all in order waits for somebody to
        // notice.
        assertEquals(PartnerStatus.PENDING, decided.storeStatus());
        assertEquals(PartnerStatus.PENDING, reloadStore().getStatus());
    }

    @Test
    void aSupersededDocumentIsNotDecidedBecauseTheSellerAlreadyImprovedOnIt() {
        KycDocument old = kyc.save(KycDocument.builder()
                .vendor(kombo).type(KycDocumentType.NATIONAL_ID)
                .status(KycDocumentStatus.SUBMITTED).fileUrl("https://media.invalid/blurry.jpg")
                .submittedAt(LocalDateTime.now().minusDays(1))
                .supersededAt(LocalDateTime.now()).build());
        entityManager.flush();

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> moderation.rejectKyc(operator, old.getId(),
                        new AdminModerationRequests.RejectKyc("Too blurry to read.")));
        assertTrue(refused.getMessage().contains("replaced that document"));
    }

    @Test
    void aDecidedDocumentIsNotDecidedAgain() {
        KycDocument done = kyc.save(KycDocument.builder()
                .vendor(kombo).type(KycDocumentType.NATIONAL_ID)
                .status(KycDocumentStatus.ACCEPTED).fileUrl("https://media.invalid/id.jpg")
                .reviewedAt(LocalDateTime.now()).submittedAt(LocalDateTime.now()).build());
        entityManager.flush();

        assertThrows(BadRequestException.class,
                () -> moderation.approveKyc(operator, done.getId(), null));
    }

    // ── Listings ─────────────────────────────────────────────────────────────

    @Test
    void suspendingAListingRaisesACaseWhetherOrNotAnybodyAskedForOne() {
        AdminModerationResponses.ProductDecision decided = moderation.suspendProduct(
                operator, phone.getId(), new AdminModerationRequests.SuspendProduct(
                        ModerationReason.INTELLECTUAL_PROPERTY,
                        "Photographs taken from the manufacturer's site.", false));
        entityManager.flush();

        assertEquals(ProductStatus.SUSPENDED, decided.status());
        // Without it there is no record to answer "why has this seller had four
        // listings taken down".
        assertNotNull(decided.caseReference());
        assertTrue(cases.findOpenAbout("PRODUCT", phone.getId()).isPresent());
    }

    @Test
    void aSecondReportAboutOneListingJoinsTheCaseRatherThanRaisingASecond() {
        moderation.suspendProduct(operator, phone.getId(),
                new AdminModerationRequests.SuspendProduct(
                        ModerationReason.MISLEADING_LISTING, "Wrong storage size.", false));
        entityManager.flush();
        long after = cases.count();

        moderation.suspendProduct(operator, phone.getId(),
                new AdminModerationRequests.SuspendProduct(
                        ModerationReason.MISLEADING_LISTING, "Still wrong.", false));
        entityManager.flush();

        // Three people reporting one listing is one thing to decide, not three.
        assertEquals(after, cases.count());
    }

    @Test
    void aListingCannotBeAddedToAStoreThatIsNotApproved() {
        kombo.setStatus(PartnerStatus.SUSPENDED);
        vendors.save(kombo);
        entityManager.flush();

        assertThrows(BadRequestException.class, () -> moderation.createProduct(operator,
                new AdminModerationRequests.CreateProduct(kombo.getId(), "Nokia 105", null,
                        new BigDecimal("1200.00"), "GMD", 5, null, "Phone order.")));
    }

    @Test
    void aListingCreatedByAnAdministratorIsPublishedRatherThanQueued() {
        AdminModerationResponses.ProductDecision created = moderation.createProduct(operator,
                new AdminModerationRequests.CreateProduct(kombo.getId(), "Nokia 105",
                        "Torch and long standby.", new BigDecimal("1200.00"), "GMD", 5, null,
                        "Shop has no internet."));
        entityManager.flush();

        // They have already made the decision the queue exists to make; leaving
        // it IN_REVIEW would mean waiting for themselves.
        assertEquals(ProductStatus.PUBLISHED, created.status());
        assertEquals(ProductStatus.PUBLISHED,
                products.findById(created.productId()).orElseThrow().getStatus());
    }

    // ── Reviews ──────────────────────────────────────────────────────────────

    private Review reportedReview(int reports) {
        Review review = reviews.save(Review.builder()
                .user(sellerUser).product(phone).rating(1).title("Never arrived")
                .comment("Paid and nothing came.").verified(true).reportCount(reports)
                .build());
        entityManager.flush();
        return review;
    }

    @Test
    void takingAReviewDownNeedsARuleItBrokeRatherThanASellerWhoDislikesIt() {
        Review review = reportedReview(1);

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> moderation.rejectReview(operator, review.getId(),
                        new AdminModerationRequests.ModerateReview(null,
                                "The seller says it is unfair.")));
        // A marketplace that removed reviews on request would have none worth
        // reading, and the honest sellers lose most.
        assertTrue(refused.getMessage().contains("that is what the reply is for"));
    }

    @Test
    void leavingAReviewUpIsTheAnswerMostReportsGet() {
        Review review = reportedReview(2);

        AdminModerationResponses.ReviewDecision decided = moderation.publishReview(
                operator, review.getId(), new AdminModerationRequests.ModerateReview(
                        null, "Read the order — the parcel genuinely did not arrive."));
        entityManager.flush();

        assertTrue(decided.visible());
        assertNull(reviews.findById(review.getId()).orElseThrow().getHiddenAt());
        assertTrue(decided.message().contains("break a rule"));
    }

    @Test
    void aReviewTakenDownKeepsItsRowSoTheDecisionCanBeExplained() {
        Review review = reportedReview(3);

        moderation.rejectReview(operator, review.getId(),
                new AdminModerationRequests.ModerateReview(ModerationReason.ABUSIVE_CONDUCT,
                        "Slur in the comment."));
        entityManager.flush();

        Review stored = reviews.findById(review.getId()).orElseThrow();
        assertNotNull(stored.getHiddenAt());
        assertFalse(stored.isVisible());
        assertNotNull(stored.getComment(), "the row survives, so the decision can be explained");
    }

    // ── Cases ────────────────────────────────────────────────────────────────

    private ModerationCase openCase() {
        moderation.suspendProduct(operator, phone.getId(),
                new AdminModerationRequests.SuspendProduct(
                        ModerationReason.PROHIBITED_ITEM, "Not sellable here.", false));
        entityManager.flush();
        return cases.findOpenAbout("PRODUCT", phone.getId()).orElseThrow();
    }

    @Test
    void mostCasesResolveWithNoSanctionAtAll() {
        ModerationCase row = openCase();

        AdminModerationResponses.CaseResolved resolved = moderation.resolveCase(
                operator, row.getId(), new AdminModerationRequests.ResolveCase(
                        false, "Looked at it — the listing was fine.", null, null, null));
        entityManager.flush();

        // Making no-sanction the default is what stops a queue producing
        // punishments because it exists.
        assertNull(resolved.sanctionIssued());
        assertFalse(resolved.accountLocked());
        assertEquals("DISMISSED", resolved.outcome());
        assertEquals(0, sanctions.countHistory(sellerUser.getId()));
    }

    @Test
    void anUpheldCaseCanIssueASanctionThatLocksTheAccount() {
        ModerationCase row = openCase();

        AdminModerationResponses.CaseResolved resolved = moderation.resolveCase(
                operator, row.getId(), new AdminModerationRequests.ResolveCase(
                        true, "Third prohibited listing this month.",
                        SanctionType.SUSPENSION, 30, null));
        entityManager.flush();

        assertEquals(SanctionType.SUSPENSION, resolved.sanctionIssued());
        assertTrue(resolved.accountLocked());
        assertNotNull(resolved.sanctionExpiresAt());
        assertFalse(users.findById(sellerUser.getId()).orElseThrow().isEnabled());
        // And the sanction points back at the case that produced it.
        assertNotNull(sanctions.findByUserIdOrderByCreatedAtDesc(sellerUser.getId())
                .get(0).getModerationCase());
    }

    @Test
    void aSuspensionFromACaseNeedsANumberOfDays() {
        ModerationCase row = openCase();

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> moderation.resolveCase(operator, row.getId(),
                        new AdminModerationRequests.ResolveCase(true, "Upheld.",
                                SanctionType.SUSPENSION, null, null)));
        assertTrue(refused.getMessage().contains("when they get their account back"));
    }

    @Test
    void aCaseIsNotDecidedTwice() {
        ModerationCase row = openCase();
        moderation.resolveCase(operator, row.getId(),
                new AdminModerationRequests.ResolveCase(false, "Fine.", null, null, null));
        entityManager.flush();

        assertThrows(BadRequestException.class, () -> moderation.resolveCase(operator, row.getId(),
                new AdminModerationRequests.ResolveCase(true, "Changed my mind.", null, null, null)));
    }

    @Test
    void aSevereCaseGetsAShorterDeadlineThanAnOrdinaryOne() {
        ModerationCase severe = openCase();

        // PROHIBITED_ITEM is severe; the queue is sorted by deadline, so the
        // difference is what puts it first.
        assertTrue(severe.getDueBy().isBefore(LocalDateTime.now().plusHours(9)));
    }

    @Test
    void theQueueCarriesHowManyTimesTheAccountHasBeenHereBefore() {
        ModerationCase row = openCase();
        moderation.resolveCase(operator, row.getId(),
                new AdminModerationRequests.ResolveCase(true, "First offence.",
                        SanctionType.WARNING, null, null));
        entityManager.flush();

        moderation.suspendProduct(operator, phone.getId(),
                new AdminModerationRequests.SuspendProduct(
                        ModerationReason.PROHIBITED_ITEM, "And again.", false));
        entityManager.flush();

        AdminModerationResponses.CaseRow queued = moderation
                .cases(operator, null, null, null, PageRequest.of(0, 10))
                .getContent().stream()
                .filter(c -> c.status().isOpen()).findFirst().orElseThrow();

        // A fourth report is a different thing from a first, and an
        // administrator should not have to go and look.
        assertEquals(1, queued.priorCases());
    }
}
