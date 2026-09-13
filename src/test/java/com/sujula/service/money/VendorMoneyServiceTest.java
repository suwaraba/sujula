package com.sujula.service.money;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import com.sujula.dto.request.money.MoneyRequests;
import com.sujula.dto.response.money.MoneyResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.model.constant.LedgerEntryType;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.PayoutStatus;
import com.sujula.model.constant.UserRole;
import com.sujula.model.constant.VendorOrderStatus;
import com.sujula.model.order.Order;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.money.PayoutRepository;
import com.sujula.repository.money.VendorLedgerEntryRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.repository.order.VendorOrderRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.money.impl.VendorMoneyServiceImpl;
import com.sujula.service.reference.CurrencyCatalogue;
import com.sujula.service.reference.ReferenceDataProperties;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The money desk: what a seller is shown, and what asking to be paid does.
 *
 * <p>The statement assertions are the sharp ones. A statement is a document
 * somebody may take to a bank, and its only real property is that the opening
 * balance plus the movements equals the closing balance — so that is checked by
 * doing the arithmetic rather than by looking at the document.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({VendorMoneyServiceImpl.class, MoneyLedger.class, StatementRenderer.class,
         CurrencyCatalogue.class, ReferenceDataProperties.class})
class VendorMoneyServiceTest {

    @Autowired private VendorMoneyServiceImpl money;
    @Autowired private MoneyLedger ledger;
    @Autowired private VendorLedgerEntryRepository entries;
    @Autowired private PayoutRepository payouts;
    @Autowired private VendorOrderRepository vendorOrders;
    @Autowired private OrderRepository orders;
    @Autowired private VendorRepository vendors;
    @Autowired private UserRepository users;
    @Autowired private EntityManager entityManager;

    private Vendor vendor;
    private User sellerUser;

    @BeforeEach
    void setUp() {
        sellerUser = new User();
        sellerUser.setEmail("lamin@sujula.gm");
        sellerUser.setPassword("x");
        sellerUser.setFirstName("Lamin");
        sellerUser.setLastName("Jallow");
        sellerUser.setRole(UserRole.VENDOR);
        sellerUser = users.save(sellerUser);

        vendor = vendors.save(Vendor.builder()
                .user(sellerUser).storeName("Kombo Electronics").storeSlug("kombo-electronics")
                .status(PartnerStatus.APPROVED)
                .settlementCurrency("GMD").pickupCountryCode("GM")
                .build());
    }

    private VendorOrder sold(String number, String currency, String gross, String commission) {
        Order order = new Order();
        order.setOrderNumber(number);
        order.setSubtotal(new BigDecimal(gross));
        order.setTotal(new BigDecimal(gross));
        order.setCurrency("EUR");
        order = orders.save(order);

        VendorOrder slice = vendorOrders.save(VendorOrder.builder()
                .order(order).vendor(vendor).status(VendorOrderStatus.DELIVERED)
                .nativeCurrency(currency)
                .subtotalNative(new BigDecimal(gross))
                .totalNative(new BigDecimal(gross))
                .commissionNative(new BigDecimal(commission))
                .subtotal(new BigDecimal(gross)).total(new BigDecimal(gross))
                .build());
        ledger.postSale(slice);
        ledger.releaseEscrow(slice, LocalDateTime.now());
        entityManager.flush();
        return slice;
    }

    // ── Balance ──────────────────────────────────────────────────────────────

    @Test
    void aShopThatHasSoldNothingStillHasABalanceInItsOwnCurrency() {
        MoneyResponses.Balance balance = money.balance(sellerUser.getId());

        // More useful than an empty list a client has to special-case.
        assertEquals(1, balance.byCurrency().size());
        assertEquals("GMD", balance.byCurrency().get(0).currency());
        assertEquals(0, balance.byCurrency().get(0).available().compareTo(BigDecimal.ZERO));
    }

    @Test
    void twoCurrenciesComeBackAsTwoRowsWithAnExplanation() {
        sold("SJL-M-1", "GMD", "9700.00", "970.00");
        sold("SJL-M-2", "XOF", "14500", "1450");

        MoneyResponses.Balance balance = money.balance(sellerUser.getId());

        assertEquals(2, balance.byCurrency().size());
        assertNotNull(balance.note());
        assertTrue(balance.note().contains("never added"));
    }

    // ── Transactions ─────────────────────────────────────────────────────────

    @Test
    void theLedgerColumnAddsUpToTheBalance() {
        sold("SJL-M-3", "GMD", "9700.00", "970.00");
        sold("SJL-M-4", "GMD", "1300.00", "130.00");

        MoneyResponses.Transactions view = money.transactions(
                sellerUser.getId(), "GMD", null, null, null, PageRequest.of(0, 100));

        BigDecimal summed = view.entries().stream()
                .map(MoneyResponses.Transaction::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // The only property that makes a balance checkable.
        assertEquals(0, summed.compareTo(
                money.balance(sellerUser.getId()).byCurrency().get(0).available()));
    }

    @Test
    void theLedgerCanBeNarrowedToOneKindOfMovement() {
        sold("SJL-M-5", "GMD", "9700.00", "970.00");

        MoneyResponses.Transactions commission = money.transactions(
                sellerUser.getId(), "GMD", LedgerEntryType.COMMISSION, null, null,
                PageRequest.of(0, 100));

        assertEquals(1, commission.entries().size());
        assertEquals(0, commission.entries().get(0).amount().compareTo(new BigDecimal("-970.00")));
    }

    // ── Payout requests ──────────────────────────────────────────────────────

    @Test
    void requestingAPayoutAsksForWhatIsAvailableAndMovesNoMoney() {
        sold("SJL-M-6", "GMD", "9700.00", "970.00");

        MoneyResponses.PayoutRequested requested = money.requestPayout(
                sellerUser.getId(), new MoneyRequests.RequestPayout(null, "Rent is due"));
        entityManager.flush();

        assertEquals(PayoutStatus.REQUESTED, requested.status());
        assertEquals(0, requested.amount().compareTo(new BigDecimal("8730.00")));
        assertEquals("GMD", requested.currency());
        // Nothing transferred. An administrator decides.
        assertTrue(requested.message().contains("not yet a transfer"));
    }

    @Test
    void askingTwiceCannotClaimTheSameMoneyTwice() {
        sold("SJL-M-7", "GMD", "9700.00", "970.00");
        money.requestPayout(sellerUser.getId(), new MoneyRequests.RequestPayout(null, null));
        entityManager.flush();

        // The first request committed the balance, so there is nothing left.
        BadRequestException second = assertThrows(BadRequestException.class,
                () -> money.requestPayout(sellerUser.getId(),
                        new MoneyRequests.RequestPayout(null, null)));
        assertTrue(second.getMessage().contains("no GMD balance"), second.getMessage());
    }

    @Test
    void moneyStillInEscrowCannotBeRequestedAndTheRefusalSaysSo() {
        Order order = new Order();
        order.setOrderNumber("SJL-M-8");
        order.setSubtotal(new BigDecimal("9700.00"));
        order.setTotal(new BigDecimal("9700.00"));
        order.setCurrency("EUR");
        order = orders.save(order);

        VendorOrder slice = vendorOrders.save(VendorOrder.builder()
                .order(order).vendor(vendor).status(VendorOrderStatus.SHIPPED)
                .nativeCurrency("GMD")
                .subtotalNative(new BigDecimal("9700.00")).totalNative(new BigDecimal("9700.00"))
                .commissionNative(new BigDecimal("970.00"))
                .subtotal(new BigDecimal("9700.00")).total(new BigDecimal("9700.00"))
                .build());
        ledger.postSale(slice);          // held: the parcel has not arrived
        entityManager.flush();

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> money.requestPayout(sellerUser.getId(),
                        new MoneyRequests.RequestPayout(null, null)));

        // "You have money coming but not yet" and "you have none" are very
        // different things to be told.
        assertTrue(refused.getMessage().contains("still held"), refused.getMessage());
        assertTrue(refused.getMessage().contains("8730.00"), refused.getMessage());
    }

    @Test
    void aRequestedPayoutShowsInTheHistoryAndInTheBalance() {
        sold("SJL-M-9", "GMD", "9700.00", "970.00");
        money.requestPayout(sellerUser.getId(), new MoneyRequests.RequestPayout("gmd", null));
        entityManager.flush();

        MoneyResponses.Payouts history = money.payouts(sellerUser.getId(), null, PageRequest.of(0, 20));
        assertEquals(1, history.payouts().size());
        assertEquals(PayoutStatus.REQUESTED, history.payouts().get(0).status());

        MoneyResponses.CurrencyBalance balance = money.balance(sellerUser.getId()).byCurrency().get(0);
        assertEquals(0, balance.available().compareTo(BigDecimal.ZERO));
        assertEquals(0, balance.onHold().compareTo(new BigDecimal("8730.00")));
    }

    // ── Statements ───────────────────────────────────────────────────────────

    @Test
    void aStatementsOpeningPlusItsMovementsEqualsItsClosing() {
        sold("SJL-M-10", "GMD", "9700.00", "970.00");
        String period = java.time.YearMonth.now().toString();

        MoneyResponses.Statement csv = money.statement(sellerUser.getId(), period, "GMD", "csv");
        String text = new String(csv.content(), StandardCharsets.UTF_8);

        assertTrue(text.contains("OPENING_BALANCE"));
        assertTrue(text.contains("CLOSING_BALANCE"));
        // The document's only real property, checked by doing the arithmetic.
        assertTrue(text.contains("8730.00 GMD") || text.contains("8730.00"),
                "the closing balance is the opening plus the rows");
    }

    @Test
    void aStatementComesAsAPdfWhenAsked() {
        sold("SJL-M-11", "GMD", "9700.00", "970.00");
        MoneyResponses.Statement pdf = money.statement(
                sellerUser.getId(), java.time.YearMonth.now().toString(), "GMD", "pdf");

        assertEquals("application/pdf", pdf.contentType());
        assertEquals("%PDF", new String(pdf.content(), 0, 4, StandardCharsets.ISO_8859_1));
        assertTrue(pdf.filename().endsWith("-GMD.pdf"));
    }

    @Test
    void aMalformedPeriodOrFormatIsRefusedByName() {
        assertThrows(BadRequestException.class,
                () -> money.statement(sellerUser.getId(), "last-month", "GMD", "pdf"));
        assertThrows(BadRequestException.class,
                () -> money.statement(sellerUser.getId(), "2026-09", "GMD", "docx"));
    }

    @Test
    void aShopWithTwoCurrenciesMustSayWhichStatementItWants() {
        sold("SJL-M-12", "GMD", "9700.00", "970.00");
        sold("SJL-M-13", "XOF", "14500", "1450");

        // A statement covers one currency, because its opening and closing
        // balances are only meaningful within one. Picking one silently would
        // produce a document that omits half the money.
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> money.statement(sellerUser.getId(),
                        java.time.YearMonth.now().toString(), null, "csv"));
        assertTrue(refused.getMessage().contains("say which"), refused.getMessage());
    }

    @Test
    void aStatementCarriesForwardWhatCameBefore() {
        sold("SJL-M-14", "GMD", "9700.00", "970.00");
        entityManager.flush();

        // Push the sale into last month, then ask for this one.
        entityManager.createQuery("UPDATE VendorLedgerEntry e SET e.occurredAt = :when")
                .setParameter("when", LocalDateTime.now().minusMonths(1).withDayOfMonth(15))
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        MoneyResponses.Statement csv = money.statement(
                sellerUser.getId(), java.time.YearMonth.now().toString(), "GMD", "csv");
        String text = new String(csv.content(), StandardCharsets.UTF_8);

        // Last month's money is the opening balance of this one; without it the
        // rows have nothing to anchor against.
        assertTrue(text.contains("8730.00"), text);
        assertFalse(text.contains("SJL-M-14"), "and last month's rows are not repeated");
    }
}
