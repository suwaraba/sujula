package com.sujula.service.money;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.sujula.model.constant.LedgerEntryType;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.PayoutStatus;
import com.sujula.model.constant.RefundRequestStatus;
import com.sujula.model.constant.UserRole;
import com.sujula.model.constant.VendorOrderStatus;
import com.sujula.model.money.FxSnapshot;
import com.sujula.model.order.Order;
import com.sujula.model.order.RefundRequest;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.user.Payout;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.money.VendorLedgerEntryRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.repository.order.RefundRequestRepository;
import com.sujula.repository.order.VendorOrderRepository;
import com.sujula.repository.money.PayoutRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.reference.CurrencyCatalogue;
import com.sujula.service.reference.ReferenceDataProperties;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The money ledger against a real database.
 *
 * <p>Mocks cannot answer the questions that matter here. Whether a balance is
 * actually the sum of its rows is a claim about aggregate SQL; whether two
 * currencies stay apart is a claim about a GROUP BY; and whether XOF rounds to
 * whole francs is a claim about what survives a round trip through a numeric
 * column.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({MoneyLedger.class, CurrencyCatalogue.class, ReferenceDataProperties.class})
class MoneyLedgerTest {

    @Autowired private MoneyLedger ledger;
    @Autowired private VendorLedgerEntryRepository entries;
    @Autowired private VendorOrderRepository vendorOrders;
    @Autowired private OrderRepository orders;
    @Autowired private RefundRequestRepository refunds;
    @Autowired private PayoutRepository payouts;
    @Autowired private VendorRepository vendors;
    @Autowired private UserRepository users;
    @Autowired private EntityManager entityManager;

    private Vendor banjul;
    private User seller;

    @BeforeEach
    void setUp() {
        seller = new User();
        seller.setEmail("lamin@sujula.gm");
        seller.setPassword("x");
        seller.setFirstName("Lamin");
        seller.setLastName("Jallow");
        seller.setRole(UserRole.VENDOR);
        seller = users.save(seller);

        banjul = vendors.save(Vendor.builder()
                .user(seller).storeName("Kombo Electronics").storeSlug("kombo-electronics")
                .status(PartnerStatus.APPROVED)
                .settlementCurrency("GMD").pickupCountryCode("GM")
                .build());
    }

    /** A slice worth {@code gross} in {@code currency}, with 10% commission. */
    private VendorOrder slice(String number, String currency, String gross, String commission) {
        Order order = new Order();
        order.setOrderNumber(number);
        order.setSubtotal(new BigDecimal(gross));
        order.setTotal(new BigDecimal(gross));
        order.setCurrency("EUR");
        order = orders.save(order);

        FxSnapshot fx = new FxSnapshot();
        fx.setNativeCurrency(currency);
        fx.setDisplayCurrency("EUR");
        fx.setRate(new BigDecimal("0.01100000"));
        fx.setRateAt(LocalDateTime.now());

        return vendorOrders.save(VendorOrder.builder()
                .order(order).vendor(banjul).status(VendorOrderStatus.DELIVERED)
                .nativeCurrency(currency)
                .subtotalNative(new BigDecimal(gross))
                .totalNative(new BigDecimal(gross))
                .commissionNative(new BigDecimal(commission))
                .commissionRate(new BigDecimal("10.00"))
                .payoutNative(new BigDecimal(gross).subtract(new BigDecimal(commission)))
                .subtotal(new BigDecimal(gross)).total(new BigDecimal(gross))
                .fx(fx)
                .build());
    }

    // ── Posting ──────────────────────────────────────────────────────────────

    @Test
    void aSalePostsGrossAndCommissionSeparately() {
        VendorOrder sale = slice("SJL-L-0001", "GMD", "9700.00", "970.00");

        List<com.sujula.model.money.VendorLedgerEntry> posted = ledger.postSale(sale);
        entityManager.flush();

        assertEquals(2, posted.size());
        // Gross in, commission out — not a single net row the seller has to trust.
        assertEquals(new BigDecimal("9700.00"), posted.get(0).getAmount());
        assertEquals(LedgerEntryType.SALE, posted.get(0).getType());
        assertEquals(new BigDecimal("-970.00"), posted.get(1).getAmount());
        assertEquals(LedgerEntryType.COMMISSION, posted.get(1).getType());
    }

    @Test
    void aSalePostsHeldUntilTheParcelArrives() {
        VendorOrder sale = slice("SJL-L-0002", "GMD", "9700.00", "970.00");
        ledger.postSale(sale);
        entityManager.flush();

        MoneyLedger.Balance balance = ledger.balance(banjul.getId(), "GMD");

        // Earned, and not payable. The whole point of escrow on a marketplace
        // where the parcel may still be crossing a border.
        assertEquals(0, balance.available().compareTo(BigDecimal.ZERO), "nothing is payable yet");
        assertEquals(0, balance.pending().compareTo(new BigDecimal("8730.00")));
    }

    @Test
    void releasingEscrowMakesItPayableWithoutMovingAnything() {
        VendorOrder sale = slice("SJL-L-0003", "GMD", "9700.00", "970.00");
        ledger.postSale(sale);
        entityManager.flush();

        int released = ledger.releaseEscrow(sale, LocalDateTime.now());
        entityManager.flush();

        assertEquals(2, released);
        MoneyLedger.Balance balance = ledger.balance(banjul.getId(), "GMD");
        assertEquals(0, balance.available().compareTo(new BigDecimal("8730.00")));
        assertEquals(0, balance.pending().compareTo(BigDecimal.ZERO));
        // The total did not change: escrow is about when, not how much.
        assertEquals(0, balance.total().compareTo(new BigDecimal("8730.00")));
    }

    @Test
    void postingTheSameSaleTwiceDoesNotPayTheSellerTwice() {
        VendorOrder sale = slice("SJL-L-0004", "GMD", "9700.00", "970.00");
        ledger.postSale(sale);
        entityManager.flush();
        ledger.postSale(sale);
        entityManager.flush();

        assertEquals(2, entries.findByVendorOrderIdOrderByOccurredAtAsc(sale.getId()).size());
    }

    // ── The balance is the sum of the rows ───────────────────────────────────

    @Test
    void theBalanceIsExactlyTheSumOfTheRows() {
        VendorOrder one = slice("SJL-L-0005", "GMD", "9700.00", "970.00");
        VendorOrder two = slice("SJL-L-0006", "GMD", "1300.00", "130.00");
        ledger.postSale(one);
        ledger.postSale(two);
        ledger.releaseEscrow(one, LocalDateTime.now());
        ledger.releaseEscrow(two, LocalDateTime.now());
        entityManager.flush();

        BigDecimal summed = entries.findAll().stream()
                .filter(e -> e.getCurrency().equals("GMD"))
                .map(com.sujula.model.money.VendorLedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // No stored balance anywhere to drift from this.
        assertEquals(0, ledger.balance(banjul.getId(), "GMD").available().compareTo(summed));
    }

    // ── Two currencies stay two ──────────────────────────────────────────────

    @Test
    void aVendorWhoTradedInTwoCurrenciesHasTwoBalancesAndNeverOne() {
        VendorOrder dalasi = slice("SJL-L-0007", "GMD", "9700.00", "970.00");
        VendorOrder francs = slice("SJL-L-0008", "XOF", "14500", "1450");
        ledger.postSale(dalasi);
        ledger.postSale(francs);
        ledger.releaseEscrow(dalasi, LocalDateTime.now());
        ledger.releaseEscrow(francs, LocalDateTime.now());
        entityManager.flush();

        Map<String, MoneyLedger.Balance> balances = ledger.balances(banjul.getId());

        // Two rows, and no third that added them. Producing one figure would
        // need a rate nobody agreed to at a moment nobody can name (C2).
        assertEquals(2, balances.size());
        assertEquals(0, balances.get("GMD").available().compareTo(new BigDecimal("8730.00")));
        assertEquals(0, balances.get("XOF").available().compareTo(new BigDecimal("13050")));
    }

    @Test
    void francsRoundToWholeFrancsBecauseXofHasNoMinorUnits() {
        VendorOrder francs = slice("SJL-L-0009", "XOF", "14500", "1450.50");
        ledger.postSale(francs);
        ledger.releaseEscrow(francs, LocalDateTime.now());
        entityManager.flush();

        // 1450.50 CFA is not an amount that exists.
        BigDecimal commission = entries.findByVendorOrderIdOrderByOccurredAtAsc(francs.getId())
                .stream().filter(e -> e.getType() == LedgerEntryType.COMMISSION)
                .findFirst().orElseThrow().getAmount();
        assertEquals(0, commission.stripTrailingZeros().scale() <= 0 ? 0 : 1,
                "a CFA amount must have no minor units: " + commission);
        assertEquals(0, commission.compareTo(new BigDecimal("-1451")));
    }

    // ── The slice's currency, not the vendor's current one ───────────────────

    @Test
    void anOldOrderPostsInTheCurrencyItWasSoldIn() {
        VendorOrder old = slice("SJL-L-0010", "XOF", "14500", "1450");
        // The shop has since moved to settling in dalasi.
        banjul.setSettlementCurrency("GMD");
        vendors.save(banjul);

        ledger.postSale(old);
        entityManager.flush();

        // Reading the vendor row here would restate an old order in a currency
        // it was never sold in.
        assertEquals("XOF",
                entries.findByVendorOrderIdOrderByOccurredAtAsc(old.getId()).get(0).getCurrency());
    }

    @Test
    void everyEntryCarriesTheRateItsOrderWasStruckAt() {
        VendorOrder sale = slice("SJL-L-0011", "GMD", "9700.00", "970.00");
        ledger.postSale(sale);
        entityManager.flush();
        entityManager.clear();

        // Carried, not looked up. By the time anybody reads a ledger the
        // published rate has moved.
        assertNotNull(entries.findByVendorOrderIdOrderByOccurredAtAsc(sale.getId()).get(0).getFx());
    }

    // ── Refunds ──────────────────────────────────────────────────────────────

    @Test
    void aRefundTakesTheMoneyBackAndReturnsTheCommissionSeparately() {
        VendorOrder sale = slice("SJL-L-0012", "GMD", "9700.00", "970.00");
        ledger.postSale(sale);
        ledger.releaseEscrow(sale, LocalDateTime.now());
        entityManager.flush();

        ledger.postRefund(sale, new BigDecimal("9700.00"), new BigDecimal("970.00"),
                "RFN-1", "Damaged in transit", LocalDateTime.now());
        entityManager.flush();

        // Back to nothing: 9700 - 970 - 9700 + 970.
        assertEquals(0, ledger.balance(banjul.getId(), "GMD").available().compareTo(BigDecimal.ZERO));

        // And the commission return is its own row, because that is the thing a
        // seller opens a refund to check.
        assertTrue(entries.findByVendorOrderIdOrderByOccurredAtAsc(sale.getId()).stream()
                .anyMatch(e -> e.getType() == LedgerEntryType.COMMISSION_REVERSAL));
    }

    @Test
    void refundingASaleThatIsStillHeldCancelsItInsideEscrow() {
        VendorOrder sale = slice("SJL-L-0017", "GMD", "9700.00", "970.00");
        ledger.postSale(sale);
        entityManager.flush();

        // The parcel never went. Nothing has been released, so the refund must
        // land on the same side of escrow as the sale - otherwise the two stop
        // cancelling and a fully refunded order leaves a balance behind.
        ledger.postRefund(sale, new BigDecimal("9700.00"), new BigDecimal("970.00"),
                "RFN-HELD", "Seller could not fulfil", LocalDateTime.now());
        entityManager.flush();

        MoneyLedger.Balance balance = ledger.balance(banjul.getId(), "GMD");
        assertEquals(0, balance.pending().compareTo(BigDecimal.ZERO),
                "the refund cancels the sale inside escrow");
        assertEquals(0, balance.available().compareTo(BigDecimal.ZERO),
                "and nothing became payable on the way through");

        // Specifically: the refund rows are held, not live.
        assertTrue(entries.findByVendorOrderIdOrderByOccurredAtAsc(sale.getId()).stream()
                        .allMatch(com.sujula.model.money.VendorLedgerEntry::isHeld),
                "every row on an unreleased slice is still held");
    }

    @Test
    void aRefundAfterAPayoutIsRecordedAsADebtRatherThanRefused() {
        VendorOrder sale = slice("SJL-L-0013", "GMD", "9700.00", "970.00");
        ledger.postSale(sale);
        ledger.releaseEscrow(sale, LocalDateTime.now());
        entityManager.flush();

        Payout paid = payouts.save(Payout.builder()
                .user(seller).vendor(banjul).amount(new BigDecimal("8730.00"))
                .currency("GMD").status(PayoutStatus.COMPLETED).reference("PAY-1").build());
        ledger.postPayout(banjul, paid);
        ledger.postRefund(sale, new BigDecimal("9700.00"), new BigDecimal("970.00"),
                "RFN-2", "Returned", LocalDateTime.now());
        entityManager.flush();

        // The seller was paid before the buyer asked. That is a debt, and
        // refusing to record it would only hide it.
        assertTrue(ledger.balance(banjul.getId(), "GMD").available().signum() < 0);
    }

    @Test
    void aSaleWithARefundBeingDecidedIsShownAsAtRiskWithoutBeingWithheld() {
        VendorOrder sale = slice("SJL-L-0014", "GMD", "9700.00", "970.00");
        ledger.postSale(sale);
        ledger.releaseEscrow(sale, LocalDateTime.now());
        refunds.save(RefundRequest.builder()
                .reference("RFN-OPEN").order(sale.getOrder()).vendorOrder(sale)
                .status(RefundRequestStatus.REQUESTED)
                .amount(new BigDecimal("106.70")).currency("EUR")
                .amountNative(new BigDecimal("9700.00"))
                .createdAt(LocalDateTime.now()).build());
        entityManager.flush();

        MoneyLedger.Balance balance = ledger.balance(banjul.getId(), "GMD");

        // Shown, so an approval is not a surprise ...
        assertEquals(0, balance.atRisk().compareTo(new BigDecimal("9700.00")));
        // ... and not withheld, because nothing has been decided.
        assertEquals(0, balance.available().compareTo(new BigDecimal("8730.00")));
    }

    // ── Payouts ──────────────────────────────────────────────────────────────

    @Test
    void requestingAPayoutTakesTheMoneyOutOfAvailableImmediately() {
        VendorOrder sale = slice("SJL-L-0015", "GMD", "9700.00", "970.00");
        ledger.postSale(sale);
        ledger.releaseEscrow(sale, LocalDateTime.now());
        entityManager.flush();

        Payout requested = payouts.save(Payout.builder()
                .user(seller).vendor(banjul).amount(new BigDecimal("8730.00"))
                .currency("GMD").status(PayoutStatus.REQUESTED).reference("PAY-2").build());
        ledger.postPayout(banjul, requested);
        entityManager.flush();

        MoneyLedger.Balance balance = ledger.balance(banjul.getId(), "GMD");

        // Otherwise a second request claims the same money while the first is
        // still being decided.
        assertEquals(0, balance.available().compareTo(BigDecimal.ZERO));
        assertEquals(0, balance.inFlight().compareTo(new BigDecimal("8730.00")));
    }

    @Test
    void aFailedPayoutComesBackAsAReversalRatherThanADeletion() {
        VendorOrder sale = slice("SJL-L-0016", "GMD", "9700.00", "970.00");
        ledger.postSale(sale);
        ledger.releaseEscrow(sale, LocalDateTime.now());
        Payout attempt = payouts.save(Payout.builder()
                .user(seller).vendor(banjul).amount(new BigDecimal("8730.00"))
                .currency("GMD").status(PayoutStatus.PROCESSING).reference("PAY-3").build());
        ledger.postPayout(banjul, attempt);
        entityManager.flush();

        attempt.setStatus(PayoutStatus.FAILED);
        payouts.save(attempt);
        ledger.reversePayout(attempt, "Bank rejected the account number");
        entityManager.flush();

        // The money is back ...
        assertEquals(0, ledger.balance(banjul.getId(), "GMD").available()
                .compareTo(new BigDecimal("8730.00")));
        // ... and both the attempt and its return are still readable, so the
        // seller can explain the gap in their statement.
        List<com.sujula.model.money.VendorLedgerEntry> all = entries.findByPayoutId(attempt.getId());
        assertEquals(2, all.size());
    }

    // ── Adjustments ──────────────────────────────────────────────────────────

    @Test
    void anAdjustmentMustSayWhy() {
        // An unexplained change to somebody's money is the one thing a ledger
        // exists to make impossible.
        assertThrows(IllegalArgumentException.class,
                () -> ledger.postAdjustment(banjul, new BigDecimal("100.00"), "GMD", "  ", seller));
    }

    @Test
    void anAdjustmentRecordsWhoMadeIt() {
        var adjustment = ledger.postAdjustment(banjul, new BigDecimal("-250.00"), "GMD",
                "Correcting a duplicate payout on 12 September", seller);
        entityManager.flush();

        assertEquals(seller.getId(), adjustment.getCreatedBy().getId());
        assertEquals(0, ledger.balance(banjul.getId(), "GMD").available()
                .compareTo(new BigDecimal("-250.00")));
    }

    @Test
    void aVendorWithNoHistoryHasAZeroBalanceRatherThanNothing() {
        MoneyLedger.Balance balance = ledger.balance(banjul.getId(), "GMD");

        assertEquals(0, balance.available().compareTo(BigDecimal.ZERO));
        assertEquals(0, balance.pending().compareTo(BigDecimal.ZERO));
        assertTrue(ledger.balances(banjul.getId()).isEmpty());
    }
}
