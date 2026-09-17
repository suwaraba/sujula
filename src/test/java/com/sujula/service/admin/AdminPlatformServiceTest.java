package com.sujula.service.admin;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.sujula.dto.request.admin.AdminPlatformRequests;
import com.sujula.dto.response.admin.AdminPlatformResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.model.aftersales.Dispute;
import com.sujula.model.constant.CallbackOutcome;
import com.sujula.model.constant.DisputeOutcome;
import com.sujula.model.constant.DisputeReason;
import com.sujula.model.constant.DisputeStatus;
import com.sujula.model.constant.LedgerEntryType;
import com.sujula.model.constant.NotificationEvent;
import com.sujula.model.constant.OrderStatus;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.constant.UserRole;
import com.sujula.model.constant.VendorOrderStatus;
import com.sujula.model.money.FxSnapshot;
import com.sujula.model.order.Order;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.shipment.Shipment;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.aftersales.DisputeMessageRepository;
import com.sujula.repository.aftersales.DisputeRepository;
import com.sujula.repository.money.VendorLedgerEntryRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.repository.order.VendorOrderRepository;
import com.sujula.repository.platform.FeatureFlagRepository;
import com.sujula.repository.shipment.ShipmentRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.AuditService;
import com.sujula.service.NotificationService;
import com.sujula.service.admin.impl.AdminPlatformServiceImpl;
import com.sujula.service.money.MoneyLedger;
import com.sujula.service.platform.FeatureFlags;
import com.sujula.service.platform.JobRegistry;
import com.sujula.service.reference.CurrencyCatalogue;
import com.sujula.service.reference.ReferenceDataProperties;
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

/**
 * Disputes, comms and the platform's own switches.
 *
 * <p>The claims that matter: a decision moves exactly one seller's money, the
 * hold and the refund are separate rows telling the true sequence, a call is
 * still owed until somebody says otherwise, and an announcement's reach is not
 * its segment size.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({AdminPlatformServiceImpl.class, MoneyLedger.class, FeatureFlags.class,
         JobRegistry.class, AdminPlatformServiceTest.Money.class})
class AdminPlatformServiceTest {

    @org.springframework.boot.test.context.TestConfiguration
    static class Money {
        @org.springframework.context.annotation.Bean
        CurrencyCatalogue currencyCatalogue() {
            return new CurrencyCatalogue(new ReferenceDataProperties());
        }

        /**
         * A job for the registry to find.
         *
         * <p>The real workers are not in a JPA slice, and the registry is built
         * from whatever ManagedJob beans exist — which is the behaviour being
         * tested. One stub proves the wiring; that the four real workers
         * implement the interface is what the boot test proves.
         */
        @org.springframework.context.annotation.Bean
        com.sujula.service.platform.ManagedJob testJob() {
            return new com.sujula.service.platform.ManagedJob() {
                @Override public String jobName() { return "test-drainer"; }
                @Override public String description() { return "Drains a queue in a test."; }
                @Override public int runOnce() { return 0; }
                @Override public long intervalMs() { return 30_000; }
            };
        }
    }

    @Autowired private AdminPlatformServiceImpl platform;
    @Autowired private DisputeRepository disputes;
    @Autowired private DisputeMessageRepository disputeMessages;
    @Autowired private VendorOrderRepository vendorOrders;
    @Autowired private OrderRepository orders;
    @Autowired private VendorRepository vendors;
    @Autowired private UserRepository users;
    @Autowired private ShipmentRepository shipments;
    @Autowired private VendorLedgerEntryRepository ledger;
    @Autowired private FeatureFlagRepository featureFlags;
    @Autowired private MoneyLedger money;
    @Autowired private EntityManager entityManager;

    @MockitoBean private AuditService audit;
    @MockitoBean private StepUpVerifier stepUp;
    @MockitoBean private NotificationService notifications;

    private User operator;
    private User agent;
    private User buyer;
    private Vendor kombo;
    private VendorOrder slice;
    private Dispute dispute;

    @BeforeEach
    void setUp() {
        operator = users.save(User.builder()
                .firstName("Fatou").lastName("Jallow").email("fatou.plat@sujula.gm")
                .password("x").role(UserRole.ADMIN).enabled(true).build());
        agent = users.save(User.builder()
                .firstName("Ousman").lastName("Bah").email("ousman.plat@sujula.gm")
                .password("x").role(UserRole.SUPPORT).enabled(true).build());
        buyer = users.save(User.builder()
                .firstName("Oliver").lastName("Bennett").email("oliver.plat@example.co.uk")
                .password("x").role(UserRole.CUSTOMER).enabled(true)
                .detectedCountryCode("GB").build());

        User seller = users.save(User.builder()
                .firstName("Lamin").lastName("Sanneh").email("lamin.plat@sujula.gm")
                .password("x").role(UserRole.VENDOR).enabled(true)
                .detectedCountryCode("GM").build());

        kombo = vendors.save(Vendor.builder()
                .user(seller).storeName("Kombo Electronics").storeSlug("kombo-plat")
                .status(PartnerStatus.APPROVED).settlementCurrency("GMD")
                .addressCountryCode("GM").build());

        Order order = orders.save(Order.builder()
                .orderNumber("SJL-PLAT-0001").customer(buyer)
                .status(OrderStatus.PROCESSING).paymentStatus(PaymentStatus.PAID)
                .currency("EUR").subtotal(new BigDecimal("108.64"))
                .total(new BigDecimal("108.64"))
                .billingCountry("ES").shippingCountry("GM")
                .shippingFullName("Aminata Ceesay").build());

        FxSnapshot fx = new FxSnapshot();
        fx.setNativeCurrency("GMD");
        fx.setDisplayCurrency("EUR");
        fx.setRate(new BigDecimal("0.01120000"));
        fx.setRateAt(LocalDateTime.now().minusDays(5));
        fx.setSource(com.sujula.model.constant.FxSource.PUBLISHED_RATE);

        slice = vendorOrders.save(VendorOrder.builder()
                .order(order).vendor(kombo).status(VendorOrderStatus.DELIVERED)
                .nativeCurrency("GMD")
                .subtotalNative(new BigDecimal("9700.00"))
                .totalNative(new BigDecimal("9700.00"))
                .commissionNative(new BigDecimal("970.00"))
                .subtotal(new BigDecimal("108.64")).total(new BigDecimal("108.64"))
                .fx(fx).build());

        money.postSale(slice);
        money.releaseEscrow(slice, LocalDateTime.now());

        dispute = disputes.save(Dispute.builder()
                .reference("DSP-PLAT0001").vendorOrder(slice).raisedBy(buyer)
                .status(DisputeStatus.OPEN).reason(DisputeReason.NOT_AS_DESCRIBED)
                .description("The screen is cracked.")
                .amountNative(new BigDecimal("9700.00"))
                .amount(new BigDecimal("108.64")).currency("EUR")
                .fx(fx)
                .dueBy(LocalDateTime.now().plusHours(72))
                .build());

        money.holdForDispute(slice, dispute.getReference(), "Buyer says it arrived cracked.");
        slice.setDisputeFrozenAt(LocalDateTime.now());
        vendorOrders.save(slice);
        entityManager.flush();
    }

    // ── The queue ────────────────────────────────────────────────────────────

    @Test
    void theQueueSortsByDeadlineRatherThanByAge() {
        Dispute older = disputes.save(Dispute.builder()
                .reference("DSP-PLAT0002").vendorOrder(slice).raisedBy(buyer)
                .status(DisputeStatus.OPEN).reason(DisputeReason.NOT_RECEIVED)
                .description("Never arrived.")
                .amountNative(BigDecimal.TEN).amount(BigDecimal.ONE).currency("EUR")
                .dueBy(LocalDateTime.now().plusHours(4))
                .build());
        entityManager.flush();

        var page = platform.queue(null, true, null, false, null, null, false,
                PageRequest.of(0, 20));

        // The four-hour promise comes first even though it was raised second:
        // both parties have money tied up behind the answer, so the sort is the
        // promise rather than the arrival time.
        assertEquals(older.getId(), page.getContent().get(0).disputeId());
        assertTrue(page.getContent().get(0).hoursRemaining()
                < page.getContent().get(1).hoursRemaining());
    }

    @Test
    void takingOneMovesItOutOfOpenSoTwoAgentsDoNotWorkIt() {
        AdminPlatformResponses.DisputeRow taken = platform.assign(agent, dispute.getId(),
                new AdminPlatformRequests.AssignDispute(null, "Mine."));

        assertEquals(agent.getId(), taken.assignedToUserId());
        assertEquals(DisputeStatus.UNDER_REVIEW, taken.status());
        assertNotNull(taken.assignedAt());
    }

    @Test
    void aDisputeCannotBeAssignedToSomebodyWhoCannotOpenIt() {
        assertTrue(assertThrows(BadRequestException.class,
                () -> platform.assign(operator, dispute.getId(),
                        new AdminPlatformRequests.AssignDispute(buyer.getId(), null)))
                .getMessage().contains("not support or an administrator"));
    }

    @Test
    void aNoteIsInternalAndNotSomethingTheCallerCanChange() {
        platform.addNote(agent, dispute.getId(),
                new AdminPlatformRequests.AddNote("Buyer has filed three of these this month."));
        entityManager.flush();

        // The parties' own view of the thread must not contain it. A switch that
        // could make this visible is one somebody eventually leaves in the wrong
        // position.
        assertTrue(disputeMessages.findVisibleToParties(dispute.getId()).isEmpty());
        assertEquals(1, disputeMessages.countByDisputeId(dispute.getId()));
    }

    // ── Deciding ─────────────────────────────────────────────────────────────

    @Test
    void decidingForTheBuyerLiftsTheHoldAndRefundsAsTwoSeparateRows() {
        AdminPlatformResponses.DisputeDecided decided = platform.resolve(operator, dispute.getId(),
                new AdminPlatformRequests.ResolveDispute(DisputeOutcome.FOR_BUYER, null, false,
                        "Photographs show the screen was cracked before it left the shop.",
                        "admin-password", "123456"));
        entityManager.flush();

        assertEquals(new BigDecimal("9700.00"), decided.awardedToBuyerNative());
        assertEquals(BigDecimal.ZERO.setScale(2), decided.keptByVendorNative());

        List<com.sujula.model.money.VendorLedgerEntry> rows =
                ledger.findByVendorOrderIdOrderByOccurredAtAsc(slice.getId());
        // Both, never netted. A seller who could not tell "the hold lifted and
        // then you were refunded" from "you were never held" has been told two
        // different stories about the same month.
        assertTrue(rows.stream().anyMatch(r -> r.getType() == LedgerEntryType.DISPUTE_HOLD_RELEASE));
        assertTrue(rows.stream().anyMatch(r -> r.getType() == LedgerEntryType.REFUND));
        assertTrue(rows.stream().anyMatch(r -> r.getType() == LedgerEntryType.COMMISSION_REVERSAL));
    }

    @Test
    void decidingForTheSellerLiftsTheHoldAndRefundsNothing() {
        platform.resolve(operator, dispute.getId(),
                new AdminPlatformRequests.ResolveDispute(DisputeOutcome.FOR_VENDOR, null, false,
                        "The photographs are of a different handset.",
                        "admin-password", "123456"));
        entityManager.flush();

        List<com.sujula.model.money.VendorLedgerEntry> rows =
                ledger.findByVendorOrderIdOrderByOccurredAtAsc(slice.getId());
        assertTrue(rows.stream().anyMatch(r -> r.getType() == LedgerEntryType.DISPUTE_HOLD_RELEASE));
        assertFalse(rows.stream().anyMatch(r -> r.getType() == LedgerEntryType.REFUND));
    }

    @Test
    void anAmountSentWithAnOutcomeThatAlreadySaysItIsRefused() {
        // FOR_BUYER already says what happens to the whole amount. A figure
        // beside it could disagree, and somebody would trust the wrong one.
        assertTrue(assertThrows(BadRequestException.class,
                () -> platform.resolve(operator, dispute.getId(),
                        new AdminPlatformRequests.ResolveDispute(DisputeOutcome.FOR_BUYER,
                                new BigDecimal("5000.00"), false, "Half, I think.",
                                "admin-password", "123456")))
                .getMessage().contains("Do not send an amount"));
    }

    @Test
    void aSplitOfTheWholeAmountIsRefusedBecauseThatIsNotASplit() {
        assertTrue(assertThrows(BadRequestException.class,
                () -> platform.resolve(operator, dispute.getId(),
                        new AdminPlatformRequests.ResolveDispute(DisputeOutcome.SPLIT,
                                new BigDecimal("9700.00"), false, "All of it.",
                                "admin-password", "123456")))
                .getMessage().contains("between nothing and the whole"));
    }

    @Test
    void aSplitMovesOnlyItsOwnShare() {
        AdminPlatformResponses.DisputeDecided decided = platform.resolve(operator, dispute.getId(),
                new AdminPlatformRequests.ResolveDispute(DisputeOutcome.SPLIT,
                        new BigDecimal("4000.00"), false,
                        "The case was damaged, the handset works.",
                        "admin-password", "123456"));

        assertEquals(new BigDecimal("4000.00"), decided.awardedToBuyerNative());
        assertEquals(new BigDecimal("5700.00"), decided.keptByVendorNative());
        assertEquals("GMD", decided.nativeCurrency());
    }

    @Test
    void decidingDemandsTheAdministratorsOwnPassword() {
        platform.resolve(operator, dispute.getId(),
                new AdminPlatformRequests.ResolveDispute(DisputeOutcome.FOR_VENDOR, null, false,
                        "No case to answer.", "admin-password", "123456"));

        verify(stepUp).verify(eq(operator), eq("admin-password"), eq("123456"), anyString());
    }

    @Test
    void aDisputeCannotBeDecidedTwice() {
        platform.resolve(operator, dispute.getId(),
                new AdminPlatformRequests.ResolveDispute(DisputeOutcome.FOR_VENDOR, null, false,
                        "No case to answer.", "admin-password", "123456"));
        entityManager.flush();

        assertTrue(assertThrows(BadRequestException.class,
                () -> platform.resolve(operator, dispute.getId(),
                        new AdminPlatformRequests.ResolveDispute(DisputeOutcome.FOR_BUYER, null,
                                false, "Changed my mind.", "admin-password", "123456")))
                .getMessage().contains("would move the money twice"));
    }

    @Test
    void decidingUnfreezesTheSliceSoEscrowIsNoLongerWaitingOnIt() {
        platform.resolve(operator, dispute.getId(),
                new AdminPlatformRequests.ResolveDispute(DisputeOutcome.FOR_VENDOR, null, false,
                        "No case to answer.", "admin-password", "123456"));
        entityManager.flush();

        assertNull(vendorOrders.findById(slice.getId()).orElseThrow().getDisputeFrozenAt());
    }

    @Test
    void theBuyersFigureComesFromTheRateTheOrderWasPlacedAtRatherThanTodays() {
        platform.resolve(operator, dispute.getId(),
                new AdminPlatformRequests.ResolveDispute(DisputeOutcome.FOR_BUYER, null, false,
                        "Cracked on arrival.", "admin-password", "123456"));
        entityManager.flush();

        Dispute reloaded = disputes.findById(dispute.getId()).orElseThrow();
        // 9700 GMD at the order's own 0.0112 is 108.64 EUR — the number the
        // buyer paid, not one derived from a rate read this morning.
        assertEquals(new BigDecimal("108.64"), reloaded.getAwardedToBuyer());
    }

    // ── Callbacks ────────────────────────────────────────────────────────────

    @Test
    void aCallbackTakesTheNumberOffTheParcelWhenNobodyGivesOne() {
        shipments.save(Shipment.builder()
                .reference("SHP-PLAT0001").vendorOrder(slice)
                .recipientName("Aminata Ceesay").recipientPhone("+2203100088")
                .destinationStreet("12 Kairaba Avenue").destinationCity("Serrekunda")
                .destinationCountry("GM").parcelCount(1).build());
        entityManager.flush();

        AdminPlatformResponses.CallbackRow call = platform.requestCallback(agent, dispute.getId(),
                new AdminPlatformRequests.RequestCallback(null, null, "Wolof",
                        "She needs to describe the packaging.", null));

        // The person who most needs the call is the recipient, who has no
        // account at all — her number lives on the shipment (C5).
        assertEquals("+2203100088", call.phone());
        assertEquals("Aminata Ceesay", call.contactName());
        assertTrue(call.outstanding());
    }

    @Test
    void aCallThatRangOutIsStillOwed() {
        shipmentForCallback();
        AdminPlatformResponses.CallbackRow call = platform.requestCallback(agent, dispute.getId(),
                new AdminPlatformRequests.RequestCallback(null, null, null, "Ask her.", null));
        entityManager.flush();

        AdminPlatformResponses.CallbackRow after = platform.recordCallback(agent, call.callbackId(),
                new AdminPlatformRequests.RecordCallback(CallbackOutcome.NO_ANSWER,
                        "Rang out twice.", null));

        assertTrue(after.outstanding(), "no answer is not a finished call");
        assertEquals(1, after.attempts());
    }

    @Test
    void aCallThatWasAnsweredIsFinished() {
        shipmentForCallback();
        AdminPlatformResponses.CallbackRow call = platform.requestCallback(agent, dispute.getId(),
                new AdminPlatformRequests.RequestCallback(null, null, null, "Ask her.", null));
        entityManager.flush();

        AdminPlatformResponses.CallbackRow after = platform.recordCallback(agent, call.callbackId(),
                new AdminPlatformRequests.RecordCallback(CallbackOutcome.SPOKE,
                        "She says the seal was already cut.", null));

        assertFalse(after.outstanding());
        assertTrue(after.notes().contains("seal was already cut"));
    }

    @Test
    void aRescheduleWithNoTimeOnItIsRefused() {
        shipmentForCallback();
        AdminPlatformResponses.CallbackRow call = platform.requestCallback(agent, dispute.getId(),
                new AdminPlatformRequests.RequestCallback(null, null, null, "Ask her.", null));
        entityManager.flush();

        // A call nobody will make.
        assertTrue(assertThrows(BadRequestException.class,
                () -> platform.recordCallback(agent, call.callbackId(),
                        new AdminPlatformRequests.RecordCallback(CallbackOutcome.RESCHEDULED,
                                "She is at the market.", null)))
                .getMessage().contains("say when"));
    }

    private void shipmentForCallback() {
        shipments.save(Shipment.builder()
                .reference("SHP-PLAT0002").vendorOrder(slice)
                .recipientName("Aminata Ceesay").recipientPhone("+2203100099")
                .destinationStreet("12 Kairaba Avenue").destinationCity("Serrekunda")
                .destinationCountry("GM").parcelCount(1).build());
        entityManager.flush();
    }

    // ── Comms ────────────────────────────────────────────────────────────────

    @Test
    void anAnnouncementsReachIsNotItsSegmentSize() {
        // Nobody is reachable: the notification service is a mock returning null,
        // which is exactly what a person with every channel switched off looks
        // like to this code.
        AdminPlatformResponses.AnnouncementSent sent = platform.announce(operator,
                new AdminPlatformRequests.Announce("Closed for Koriteh",
                        "No collections on Tuesday.", UserRole.VENDOR, "GM", null));

        assertEquals(1, sent.segmentSize());
        assertEquals(0, sent.recipients());
        assertTrue(sent.message().contains("segment size is not the reach"));
    }

    @Test
    void anAnnouncementDefaultsToAPlatformNoticeRatherThanAPromotion() {
        platform.announce(operator, new AdminPlatformRequests.Announce(
                "Closed for Koriteh", "No collections on Tuesday.", UserRole.VENDOR, "GM", null));

        // A seller who switched marketing off has not asked to be the last to
        // know the platform is closed.
        verify(notifications).send(any(), eq("Closed for Koriteh"), anyString(),
                eq(NotificationEvent.PLATFORM_NOTICE), anyString());
    }

    @Test
    void anEmptySegmentIsRefusedRatherThanReportedAsASuccessfulBroadcast() {
        assertTrue(assertThrows(BadRequestException.class,
                () -> platform.announce(operator, new AdminPlatformRequests.Announce(
                        "Hello", "Anybody there?", UserRole.PICKUP_OPERATOR, "SN", null)))
                .getMessage().contains("Nobody matches"));
    }

    // ── Platform ─────────────────────────────────────────────────────────────

    @Test
    void everyDeclaredFlagExistsWithSomebodyAbleToSayWhyItMoved() {
        List<AdminPlatformResponses.FlagRow> flags = platform.flags();

        assertFalse(flags.isEmpty(), "the declared flags are written at startup");
        assertTrue(flags.stream().allMatch(f -> f.description() != null && !f.description().isBlank()),
                "a flag whose description says nothing is one nobody dares move");
    }

    @Test
    void movingAFlagRecordsTheReasonOnTheFlagItself() {
        AdminPlatformResponses.FlagRow moved = platform.setFlag(operator, FeatureFlags.SAFE_DROP,
                new AdminPlatformRequests.SetFeatureFlag(false,
                        "Two parcels reported missing after a drop this week.", null));

        assertFalse(moved.enabled());
        // On the flag, because the person who finds it off in six months reads
        // the flag rather than the audit log.
        assertTrue(moved.lastChangeReason().contains("reported missing"));
        assertEquals(operator.getId(), moved.lastChangedByUserId());
    }

    @Test
    void aFlagNothingReadsCannotBeInvented() {
        assertThrows(com.sujula.exceptions.ResourceNotFoundException.class,
                () -> platform.setFlag(operator, "made.up.flag",
                        new AdminPlatformRequests.SetFeatureFlag(true, "Why not.", null)));
    }

    @Test
    void impersonationIsNotAFlagAClientIsToldAbout() {
        AdminPlatformResponses.FlagRow flag = platform.flags().stream()
                .filter(f -> FeatureFlags.IMPERSONATION.equals(f.key()))
                .findFirst().orElseThrow();
        // Telling a browser whether impersonation is available tells anybody
        // looking how the platform is defended.
        assertFalse(flag.clientVisible());
    }

    @Test
    void everyBackgroundJobIsListedWithItsOwnInterval() {
        List<AdminPlatformResponses.JobRow> jobs = platform.jobs();

        assertFalse(jobs.isEmpty());
        // Without the interval, "last ran an hour ago" is uninterpretable: fine
        // for a daily job, alarming for one that runs every thirty seconds.
        assertTrue(jobs.stream().allMatch(j -> j.intervalMs() > 0));
        assertTrue(jobs.stream().allMatch(j -> j.description() != null));
    }

    @Test
    void aJobThatHasNeverRunSaysSoRatherThanLookingHealthy() {
        AdminPlatformResponses.JobRow job = platform.jobs().get(0);

        assertNull(job.lastStartedAt());
        assertTrue(job.warnings().stream().anyMatch(w -> w.contains("never run")),
                "a job with no runs is not a job that is fine: " + job.warnings());
    }

    @Test
    void theDashboardReportsGmvPerCurrencyAndNeverATotal() {
        AdminPlatformResponses.Dashboard board = platform.dashboard();

        AdminPlatformResponses.GmvLine gmd = board.gmv().stream()
                .filter(l -> "GMD".equals(l.currency())).findFirst().orElseThrow();
        assertEquals(new BigDecimal("9700.00"), gmd.thisMonth());
        // One line per settlement currency. A figure that added dalasi to CFA is
        // one somebody would quote in a meeting.
        assertEquals(1, board.gmv().size());
    }

    @Test
    void theDashboardsAttentionListIsAboutPeopleRatherThanNumbers() {
        Dispute late = disputes.findById(dispute.getId()).orElseThrow();
        late.setDueBy(LocalDateTime.now().minusHours(6));
        disputes.save(late);
        entityManager.flush();

        AdminPlatformResponses.Dashboard board = platform.dashboard();

        assertEquals(1, board.disputesOverdue());
        assertTrue(board.attention().stream()
                        .anyMatch(a -> a.contains("past the deadline promised")),
                board.attention().toString());
    }
}
