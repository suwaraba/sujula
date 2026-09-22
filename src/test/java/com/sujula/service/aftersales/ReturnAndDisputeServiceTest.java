package com.sujula.service.aftersales;

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

import com.sujula.dto.request.aftersales.AfterSalesRequests;
import com.sujula.dto.response.aftersales.AfterSalesResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.aftersales.ReturnRequest;
import com.sujula.model.constant.CustodyEventType;
import com.sujula.model.constant.DisputeStatus;
import com.sujula.model.constant.FxSource;
import com.sujula.model.constant.LedgerEntryType;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.ReturnReason;
import com.sujula.model.constant.ReturnStatus;
import com.sujula.model.constant.UserRole;
import com.sujula.model.constant.VendorOrderStatus;
import com.sujula.model.money.FxSnapshot;
import com.sujula.model.money.VendorLedgerEntry;
import com.sujula.model.order.Order;
import com.sujula.model.order.OrderItem;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.shipment.CustodyEvent;
import com.sujula.model.shipment.Shipment;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.aftersales.DisputeRepository;
import com.sujula.repository.aftersales.ReturnRequestRepository;
import com.sujula.repository.money.VendorLedgerEntryRepository;
import com.sujula.repository.order.OrderItemRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.repository.order.VendorOrderRepository;
import com.sujula.repository.shipment.CustodyEventRepository;
import com.sujula.repository.shipment.ShipmentRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.aftersales.impl.DisputeServiceImpl;
import com.sujula.service.aftersales.impl.ReturnServiceImpl;
import com.sujula.service.money.MoneyLedger;
import com.sujula.service.reference.CurrencyCatalogue;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Returns, disputes, and the money that stops moving when one is raised.
 *
 * <p>The sharpest claims here are that a dispute freezes one seller's slice and
 * only that one (C3), that the freeze survives a delivery recorded afterwards,
 * and that every figure on both sides carries the rate from the day of the order
 * rather than today's (C2).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({ReturnServiceImpl.class, DisputeServiceImpl.class, MoneyLedger.class,
         CurrencyCatalogue.class, com.sujula.service.reference.ReferenceDataProperties.class})
class ReturnAndDisputeServiceTest {

    @Autowired private ReturnServiceImpl returns;
    @Autowired private DisputeServiceImpl disputes;
    @Autowired private MoneyLedger ledger;
    @Autowired private ReturnRequestRepository returnRows;
    @Autowired private DisputeRepository disputeRows;
    @Autowired private VendorLedgerEntryRepository entries;
    @Autowired private VendorOrderRepository vendorOrders;
    @Autowired private OrderRepository orders;
    @Autowired private OrderItemRepository orderItems;
    @Autowired private ShipmentRepository shipments;
    @Autowired private CustodyEventRepository events;
    @Autowired private VendorRepository vendors;
    @Autowired private com.sujula.repository.product.ProductRepository productRows;
    @Autowired private UserRepository users;
    @Autowired private EntityManager entityManager;

    private User buyer;
    private User komboUser;
    private User terangaUser;
    private Vendor kombo;
    private Vendor teranga;
    private Order order;
    private VendorOrder komboSlice;
    private VendorOrder terangaSlice;
    private OrderItem phone;

    /** 1 EUR = 67.50 GMD on the day of the order. Nothing re-reads it. */
    private static final BigDecimal RATE = new BigDecimal("0.01481481");

    @BeforeEach
    void setUp() {
        buyer = user("ousman.jallow@example.es", UserRole.CUSTOMER);
        komboUser = user("lamin@sujula.gm", UserRole.VENDOR);
        terangaUser = user("teranga@sujula.sn", UserRole.VENDOR);

        kombo = vendors.save(Vendor.builder()
                .user(komboUser).storeName("Kombo Electronics").storeSlug("kombo-aftersales")
                .status(PartnerStatus.APPROVED).settlementCurrency("GMD").pickupCountryCode("GM")
                .defaultCommissionRate(new BigDecimal("10.00"))
                .build());
        teranga = vendors.save(Vendor.builder()
                .user(terangaUser).storeName("Teranga Mobile").storeSlug("teranga-aftersales")
                .status(PartnerStatus.APPROVED).settlementCurrency("GMD").pickupCountryCode("SN")
                .defaultCommissionRate(new BigDecimal("10.00"))
                .build());

        order = new Order();
        order.setOrderNumber("SJL-AS-0001");
        order.setSubtotal(new BigDecimal("210.00"));
        order.setTotal(new BigDecimal("210.00"));
        order.setCurrency("EUR");
        order.setCustomer(buyer);
        order = orders.save(order);

        FxSnapshot fx = new FxSnapshot("GMD", "EUR", RATE, LocalDateTime.now().minusDays(3),
                FxSource.PUBLISHED_RATE, "seed-quote");

        komboSlice = vendorOrders.save(VendorOrder.builder()
                .order(order).vendor(kombo).status(VendorOrderStatus.DELIVERED)
                .nativeCurrency("GMD")
                .subtotalNative(new BigDecimal("12150.00")).totalNative(new BigDecimal("12150.00"))
                .subtotal(new BigDecimal("180.00")).total(new BigDecimal("180.00"))
                .commissionRate(new BigDecimal("10.00"))
                .commissionNative(new BigDecimal("1215.00"))
                .payoutNative(new BigDecimal("10935.00"))
                .fx(fx)
                .build());

        terangaSlice = vendorOrders.save(VendorOrder.builder()
                .order(order).vendor(teranga).status(VendorOrderStatus.DELIVERED)
                .nativeCurrency("GMD")
                .subtotalNative(new BigDecimal("2025.00")).totalNative(new BigDecimal("2025.00"))
                .subtotal(new BigDecimal("30.00")).total(new BigDecimal("30.00"))
                .commissionRate(new BigDecimal("10.00"))
                .commissionNative(new BigDecimal("202.50"))
                .payoutNative(new BigDecimal("1822.50"))
                .fx(fx)
                .build());

        com.sujula.model.products.Product galaxy = productRows.save(
                com.sujula.model.products.Product.builder()
                        .name("Galaxy A15 128GB").slug("galaxy-a15-aftersales")
                        .vendor(kombo)
                        .price(new BigDecimal("6075.00")).priceCurrency("GMD")
                        .stock(4).active(true).country("GM")
                        .build());

        phone = orderItems.save(OrderItem.builder()
                .order(order).vendorOrder(komboSlice).vendor(kombo).product(galaxy)
                .quantity(2)
                .unitPrice(new BigDecimal("6075.00"))
                .totalPrice(new BigDecimal("12150.00"))
                .unitPriceConverted(new BigDecimal("90.00"))
                .totalPriceConverted(new BigDecimal("180.00"))
                .currency("GMD")
                .productName("Galaxy A15 128GB")
                .assignedImeis("356938035643809")
                .build());

        // A parcel that actually arrived, because the return window is counted
        // from the custody chain rather than from the order (C4).
        Shipment shipment = shipments.save(Shipment.builder()
                .reference("SHP-AS0001").vendorOrder(komboSlice)
                .recipientName("Fatou Ceesay").destinationCity("Serrekunda")
                .destinationCountry("GM").parcelCount(1)
                .build());
        events.save(CustodyEvent.builder()
                .shipment(shipment).type(CustodyEventType.COLLECTED)
                .recordedByUserId(komboUser.getId()).codePresented("111111")
                .occurredAt(LocalDateTime.now().minusDays(3)).clientEventId("as-collected").build());
        shipment.applyDerivedState(com.sujula.model.constant.ShipmentStatus.DELIVERED, 0,
                LocalDateTime.now().minusDays(3), LocalDateTime.now().minusDays(1), null);
        shipments.save(shipment);

        entityManager.flush();
        entityManager.refresh(komboSlice);
        entityManager.refresh(order);
    }

    private User user(String email, UserRole role) {
        User person = new User();
        person.setEmail(email);
        person.setPassword("x");
        person.setFirstName(email.substring(0, email.indexOf('.') > 0 ? email.indexOf('.') : 3));
        person.setLastName("Person");
        person.setRole(role);
        return users.save(person);
    }

    private AfterSalesResponses.ReturnDetail openReturn(ReturnReason reason, int quantity) {
        return returns.open(buyer.getId(), new AfterSalesRequests.OpenReturn(
                komboSlice.getId(), reason, "The screen is cracked.",
                List.of(new AfterSalesRequests.ReturnItem(phone.getId(), quantity)),
                List.of("https://media.invalid/crack.jpg")));
    }

    /** Posts the sale and takes it out of escrow, so there is money to freeze. */
    private void postAndRelease(VendorOrder slice) {
        ledger.postSale(slice);
        slice.setEscrowReleasedAt(LocalDateTime.now().minusHours(1));
        vendorOrders.save(slice);
        ledger.releaseEscrow(slice, LocalDateTime.now().minusHours(1));
        entityManager.flush();
    }

    // ── Opening a return ─────────────────────────────────────────────────────

    @Test
    void aReturnIsAgainstOneSellersSliceAndNamedItems() {
        AfterSalesResponses.ReturnDetail opened = openReturn(ReturnReason.DAMAGED_IN_TRANSIT, 1);

        assertEquals(ReturnStatus.REQUESTED, opened.status());
        assertEquals(1, opened.lines().size());
        // One of two, not both. A buyer returning one of the cases they bought
        // must not be refunded for the one they kept.
        assertEquals(1, opened.lines().get(0).quantity());
        assertEquals(new BigDecimal("90.00"), opened.claimed().amount());
        assertEquals("EUR", opened.claimed().currency());
        assertEquals("GMD", opened.claimed().nativeCurrency());
    }

    @Test
    void bothCurrenciesAndTheRateFromTheDayOfTheOrderAreOnEveryFigure() {
        AfterSalesResponses.ReturnDetail opened = openReturn(ReturnReason.FAULTY, 2);

        // The buyer sees euros, the seller sees dalasis, and the rate that joins
        // them is the one from the order — not today's (C2).
        assertEquals(new BigDecimal("180.00"), opened.claimed().amount());
        assertEquals(new BigDecimal("12150.00"), opened.claimed().amountNative());
        assertEquals(RATE, opened.claimed().rate());
        assertNotNull(opened.claimed().rateAt());
    }

    @Test
    void theReasonDecidesWhoPaysTheCarriageAndIsFrozenThere() {
        assertTrue(openReturn(ReturnReason.FAULTY, 1).sellerPaysCarriage());

        returnRows.deleteAll();
        entityManager.flush();

        AfterSalesResponses.ReturnDetail changedMind =
                openReturn(ReturnReason.NO_LONGER_WANTED, 1);
        assertFalse(changedMind.sellerPaysCarriage());
        // Said to the buyer in words, because being charged carriage they did
        // not expect is how somebody learns not to use the platform.
        assertTrue(changedMind.carriageNote().contains("You pay the carriage"));
    }

    @Test
    void youCannotReturnMoreThanYouBought() {
        openReturn(ReturnReason.FAULTY, 2);
        entityManager.flush();
        // Settled and closed, so the "one open return at a time" guard no longer
        // catches it and the counting guard is the only thing standing between
        // the buyer and a third refund on two goods.
        returnRows.findAll().forEach(row -> {
            row.setStatus(ReturnStatus.REFUNDED);
            returnRows.save(row);
        });
        entityManager.flush();

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> returns.open(buyer.getId(), new AfterSalesRequests.OpenReturn(
                        komboSlice.getId(), ReturnReason.FAULTY, "another",
                        List.of(new AfterSalesRequests.ReturnItem(phone.getId(), 1)), List.of())));
        assertTrue(refused.getMessage().contains("already asked to return"), refused.getMessage());
    }

    @Test
    void aRejectedReturnDoesNotCountAgainstWhatIsLeftToReturn() {
        Long id = openReturn(ReturnReason.FAULTY, 2).id();
        returns.reject(komboUser.getId(), id,
                new AfterSalesRequests.RejectReturn("Nothing wrong with it."));
        entityManager.flush();

        // A seller who refuses a return must not thereby use up the buyer's
        // right to ask again for a different reason.
        assertNotNull(returns.open(buyer.getId(), new AfterSalesRequests.OpenReturn(
                komboSlice.getId(), ReturnReason.NOT_AS_DESCRIBED, "It is the wrong colour",
                List.of(new AfterSalesRequests.ReturnItem(phone.getId(), 2)), List.of())));
    }

    @Test
    void aSecondOpenReturnOnTheSameSliceIsRefused() {
        openReturn(ReturnReason.FAULTY, 1);
        entityManager.flush();

        assertThrows(BadRequestException.class, () -> openReturn(ReturnReason.WRONG_ITEM, 1));
    }

    @Test
    void anotherBuyersOrderIsNotFoundRatherThanForbidden() {
        // "Forbidden" would confirm that a guessed id is somebody's live order.
        assertThrows(ResourceNotFoundException.class,
                () -> returns.open(komboUser.getId(), new AfterSalesRequests.OpenReturn(
                        komboSlice.getId(), ReturnReason.FAULTY, "x",
                        List.of(new AfterSalesRequests.ReturnItem(phone.getId(), 1)), List.of())));
    }

    // ── The offer, which is the ordinary settlement here ─────────────────────

    @Test
    void theSellerOffersMoneyAndTheSellersHalfIsComputedAtTheFrozenRate() {
        Long id = openReturn(ReturnReason.NOT_AS_DESCRIBED, 1).id();

        AfterSalesResponses.ReturnDetail offered = returns.offerPartialRefund(
                komboUser.getId(), id,
                new AfterSalesRequests.OfferPartialRefund(new BigDecimal("45.00"),
                        "Keep it — carriage back would cost more than this."));

        assertEquals(ReturnStatus.OFFER_MADE, offered.status());
        assertEquals(new BigDecimal("45.00"), offered.offer().amount().amount());
        // 45 EUR at 0.01481481 display-per-native is 3037.50 GMD. Divided at the
        // order's rate rather than looked up today, so the seller cannot be
        // charged at a rate nobody agreed to.
        assertEquals(new BigDecimal("3037.50"), offered.offer().amount().amountNative());
        assertTrue(offered.offer().open());
    }

    @Test
    void anOfferLargerThanTheClaimIsRefused() {
        Long id = openReturn(ReturnReason.FAULTY, 1).id();

        assertThrows(BadRequestException.class, () -> returns.offerPartialRefund(
                komboUser.getId(), id,
                new AfterSalesRequests.OfferPartialRefund(new BigDecimal("500.00"), null)));
    }

    @Test
    void acceptingFixesWhatIsOwedAndTheClaimSurvivesBesideIt() {
        Long id = openReturn(ReturnReason.NOT_AS_DESCRIBED, 1).id();
        returns.offerPartialRefund(komboUser.getId(), id,
                new AfterSalesRequests.OfferPartialRefund(new BigDecimal("45.00"), null));

        AfterSalesResponses.ReturnDetail accepted = returns.acceptOffer(buyer.getId(), id);

        assertEquals(ReturnStatus.OFFER_ACCEPTED, accepted.status());
        assertNotNull(accepted.offer().acceptedAt());
        // What was claimed is still readable beside what was settled — the buyer
        // needs to see both.
        assertEquals(new BigDecimal("90.00"), accepted.claimed().amount());
        ReturnRequest row = returnRows.findById(id).orElseThrow();
        assertEquals(new BigDecimal("45.00"), row.settlementAmount());
    }

    @Test
    void aNewOfferSupersedesAnUnacceptedOne() {
        Long id = openReturn(ReturnReason.FAULTY, 1).id();
        returns.offerPartialRefund(komboUser.getId(), id,
                new AfterSalesRequests.OfferPartialRefund(new BigDecimal("20.00"), null));
        returns.offerPartialRefund(komboUser.getId(), id,
                new AfterSalesRequests.OfferPartialRefund(new BigDecimal("45.00"), null));

        ReturnRequest row = returnRows.findById(id).orElseThrow();
        assertEquals(new BigDecimal("45.00"), row.getOfferedAmount());
        assertNull(row.getOfferAcceptedAt(), "raising a new offer un-accepts nothing that was accepted");
    }

    @Test
    void theBuyerCannotApproveTheirOwnReturn() {
        Long id = openReturn(ReturnReason.FAULTY, 1).id();
        // The seller's endpoints resolve a vendor from the caller, so a buyer
        // is not a seller of anything and the row is simply not found.
        assertThrows(ResourceNotFoundException.class,
                () -> returns.approve(buyer.getId(), id, null));
    }

    // ── Escalation and the freeze ────────────────────────────────────────────

    @Test
    void escalatingFreezesTheSellersMoneyOnThisSliceAndNoOther() {
        postAndRelease(komboSlice);
        postAndRelease(terangaSlice);
        BigDecimal komboBefore = ledger.balance(kombo.getId(), "GMD").available();
        BigDecimal terangaBefore = ledger.balance(teranga.getId(), "GMD").available();

        Long id = openReturn(ReturnReason.FAULTY, 2).id();
        returns.reject(komboUser.getId(), id, new AfterSalesRequests.RejectReturn("Nothing wrong"));
        AfterSalesResponses.ReturnDetail escalated = returns.escalate(buyer.getId(), id,
                new AfterSalesRequests.EscalateReturn("It does not turn on."));
        entityManager.flush();

        assertEquals(ReturnStatus.ESCALATED, escalated.status());
        assertNotNull(escalated.disputeReference());

        // Kombo's money is held.
        BigDecimal komboAfter = ledger.balance(kombo.getId(), "GMD").available();
        assertTrue(komboAfter.compareTo(komboBefore) < 0,
                "the disputed seller's available balance goes down");

        // Teranga's charger went on the same payment and is untouched (C3).
        assertEquals(0, ledger.balance(teranga.getId(), "GMD").available()
                .compareTo(terangaBefore),
                "one seller's dispute must not hold another seller's payout");
    }

    @Test
    void theHoldIsALedgerRowTheSellerCanRead() {
        postAndRelease(komboSlice);
        Long id = openReturn(ReturnReason.FAULTY, 2).id();
        returns.reject(komboUser.getId(), id, new AfterSalesRequests.RejectReturn("No"));
        returns.escalate(buyer.getId(), id, new AfterSalesRequests.EscalateReturn("It is broken"));
        entityManager.flush();

        VendorLedgerEntry hold = entries.findByVendorOrderIdOrderByOccurredAtAsc(komboSlice.getId())
                .stream().filter(e -> e.getType() == LedgerEntryType.DISPUTE_HOLD)
                .findFirst().orElseThrow();

        // A row rather than a flag: a seller whose balance dropped overnight can
        // see which sale it was and read why beside it.
        assertTrue(hold.getAmount().signum() < 0);
        assertTrue(hold.getDescription().contains("dispute"));
        assertNotNull(hold.getReference());
    }

    @Test
    void aSliceStillInEscrowGetsNoHoldRowBecauseThereIsNothingToHold() {
        // Posted but never released. The money is not payable, so a negative row
        // would take the balance down twice for one sale.
        ledger.postSale(komboSlice);
        entityManager.flush();

        Long id = openReturn(ReturnReason.FAULTY, 2).id();
        returns.reject(komboUser.getId(), id, new AfterSalesRequests.RejectReturn("No"));
        returns.escalate(buyer.getId(), id, new AfterSalesRequests.EscalateReturn("broken"));
        entityManager.flush();

        assertTrue(entries.findByVendorOrderIdOrderByOccurredAtAsc(komboSlice.getId()).stream()
                .noneMatch(e -> e.getType() == LedgerEntryType.DISPUTE_HOLD));
        // And the freeze still bites, by the other half of the mechanism.
        assertNotNull(vendorOrders.findById(komboSlice.getId()).orElseThrow()
                .getDisputeFrozenAt());
    }

    @Test
    void aDeliveryRecordedAfterTheDisputeCannotReleaseTheEscrow() {
        ledger.postSale(komboSlice);
        entityManager.flush();

        Long id = openReturn(ReturnReason.NEVER_ARRIVED, 2).id();
        returns.reject(komboUser.getId(), id, new AfterSalesRequests.RejectReturn("It went"));
        returns.escalate(buyer.getId(), id,
                new AfterSalesRequests.EscalateReturn("Nothing came."));
        entityManager.flush();
        entityManager.refresh(komboSlice);

        // The parcel arriving is exactly what the argument is about, so the event
        // that normally pays the seller must not pay them in the middle of it.
        int released = ledger.releaseEscrow(komboSlice, LocalDateTime.now());
        assertEquals(0, released);
        assertEquals(0, ledger.balance(kombo.getId(), "GMD").available().signum());
    }

    @Test
    void withdrawingLiftsTheFreezeAndTheSellerIsWholeAgain() {
        postAndRelease(komboSlice);
        BigDecimal before = ledger.balance(kombo.getId(), "GMD").available();

        Long id = openReturn(ReturnReason.FAULTY, 2).id();
        returns.reject(komboUser.getId(), id, new AfterSalesRequests.RejectReturn("No"));
        returns.escalate(buyer.getId(), id, new AfterSalesRequests.EscalateReturn("broken"));
        entityManager.flush();

        Long disputeId = disputeRows.findAll().get(0).getId();
        AfterSalesResponses.DisputeDetail withdrawn = disputes.withdraw(buyer.getId(), disputeId,
                new AfterSalesRequests.WithdrawDispute("It was my mistake."));
        entityManager.flush();

        assertEquals(DisputeStatus.WITHDRAWN, withdrawn.status());
        assertFalse(withdrawn.freeze().active());
        assertNull(vendorOrders.findById(komboSlice.getId()).orElseThrow().getDisputeFrozenAt());
        assertEquals(0, ledger.balance(kombo.getId(), "GMD").available().compareTo(before),
                "the hold comes off in full");
    }

    @Test
    void theSellerCannotWithdrawADisputeAgainstThemselves() {
        Long id = openReturn(ReturnReason.FAULTY, 2).id();
        returns.reject(komboUser.getId(), id, new AfterSalesRequests.RejectReturn("No"));
        returns.escalate(buyer.getId(), id, new AfterSalesRequests.EscalateReturn("broken"));
        entityManager.flush();

        Long disputeId = disputeRows.findAll().get(0).getId();
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> disputes.withdraw(komboUser.getId(), disputeId, null));
        assertTrue(refused.getMessage().contains("Only the person who raised this"));
    }

    @Test
    void theSellerCanReadTheDisputeAgainstThemAndIsToldWhyTheirBalanceMoved() {
        postAndRelease(komboSlice);
        Long id = openReturn(ReturnReason.FAULTY, 2).id();
        returns.reject(komboUser.getId(), id, new AfterSalesRequests.RejectReturn("No"));
        returns.escalate(buyer.getId(), id, new AfterSalesRequests.EscalateReturn("broken"));
        entityManager.flush();

        Long disputeId = disputeRows.findAll().get(0).getId();
        AfterSalesResponses.DisputeDetail sellerView = disputes.detail(komboUser.getId(), disputeId);

        assertTrue(sellerView.freeze().active());
        assertTrue(sellerView.freeze().explanation().contains("off your available balance"));
        assertTrue(sellerView.freeze().explanation().contains("Nothing else you have sold"));

        AfterSalesResponses.DisputeDetail buyerView = disputes.detail(buyer.getId(), disputeId);
        assertTrue(buyerView.freeze().explanation().contains("has not been paid"));
    }

    @Test
    void thebuyersOpeningStatementIsTheFirstMessageInTheCaseFile() {
        Long id = openReturn(ReturnReason.FAULTY, 2).id();
        returns.reject(komboUser.getId(), id,
                new AfterSalesRequests.RejectReturn("The phone was fine when it left."));
        returns.escalate(buyer.getId(), id,
                new AfterSalesRequests.EscalateReturn("It does not turn on at all."));
        entityManager.flush();

        Long disputeId = disputeRows.findAll().get(0).getId();
        AfterSalesResponses.DisputeDetail detail = disputes.detail(buyer.getId(), disputeId);

        // Both sides, in order, so a moderator reads a conversation rather than
        // one party's account and a status column.
        assertEquals(2, detail.messages().size());
        assertEquals("BUYER", detail.messages().get(0).authorSide());
        assertTrue(detail.messages().get(0).body().contains("does not turn on"));
        assertEquals("VENDOR", detail.messages().get(1).authorSide());
        assertTrue(detail.messages().get(1).body().contains("was fine when it left"));
    }

    @Test
    void aStrangerCannotReadADispute() {
        Long id = openReturn(ReturnReason.FAULTY, 2).id();
        returns.reject(komboUser.getId(), id, new AfterSalesRequests.RejectReturn("No"));
        returns.escalate(buyer.getId(), id, new AfterSalesRequests.EscalateReturn("broken"));
        entityManager.flush();

        Long disputeId = disputeRows.findAll().get(0).getId();
        assertThrows(ResourceNotFoundException.class,
                () -> disputes.detail(terangaUser.getId(), disputeId));
    }

    // ── The window ───────────────────────────────────────────────────────────

    @Test
    void aParcelThatWasNeverDeliveredCannotBeReturnedButCanBeReportedMissing() {
        VendorOrder undelivered = terangaSlice;

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> returns.open(buyer.getId(), new AfterSalesRequests.OpenReturn(
                        undelivered.getId(), ReturnReason.FAULTY, "x",
                        List.of(new AfterSalesRequests.ReturnItem(phone.getId(), 1)), List.of())));
        assertTrue(refused.getMessage().contains("not been delivered yet"));
    }

    @Test
    void theListsShowEachSideWhetherItIsWaitingOnThem() {
        Long id = openReturn(ReturnReason.FAULTY, 1).id();
        entityManager.flush();

        AfterSalesResponses.ReturnSummary sellerRow = returns
                .list(komboUser.getId(), null, PageRequest.of(0, 10)).getContent().get(0);
        AfterSalesResponses.ReturnSummary buyerRow = returns
                .list(buyer.getId(), null, PageRequest.of(0, 10)).getContent().get(0);

        // Eight time zones apart and neither can ask the other what is
        // happening, so each is told whether the ball is theirs.
        assertTrue(sellerRow.waitingOnYou());
        assertFalse(buyerRow.waitingOnYou());
        assertTrue(sellerRow.whatHappensNext().startsWith("Decide whether"));
        assertTrue(buyerRow.whatHappensNext().contains("The seller has been told"));
        assertEquals(id, sellerRow.id());
    }
}
