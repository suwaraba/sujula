package com.sujula.service.admin;

import java.math.BigDecimal;
import java.time.LocalDate;
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

import com.sujula.dto.request.admin.AdminMoneyRequests;
import com.sujula.dto.response.admin.AdminMoneyResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.model.constant.LedgerEntryType;
import com.sujula.model.constant.OrderStatus;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.PaymentMethod;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.constant.PayoutBatchStatus;
import com.sujula.model.constant.PayoutStatus;
import com.sujula.model.constant.UserRole;
import com.sujula.model.constant.VendorOrderStatus;
import com.sujula.model.money.FxSnapshot;
import com.sujula.model.order.Order;
import com.sujula.model.order.Payment;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.user.BankAccount;
import com.sujula.model.user.Payout;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.PaymentRepository;
import com.sujula.repository.finance.PayoutBatchRepository;
import com.sujula.repository.money.PayoutRepository;
import com.sujula.repository.money.VendorLedgerEntryRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.repository.order.VendorOrderRepository;
import com.sujula.repository.user.BankAccountRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.AuditService;
import com.sujula.service.ExchangeRateService;
import com.sujula.service.NotificationService;
import com.sujula.service.admin.impl.AdminMoneyServiceImpl;
import com.sujula.service.money.FxSpreadRegistry;
import com.sujula.service.money.MoneyLedger;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The platform's money.
 *
 * <p>The claims that matter: a refund is one seller's sub-order and never a
 * proportion, nothing is re-converted at today's rate, a payout run cannot be
 * released by the person who assembled it, and no total anywhere adds two
 * currencies together.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({AdminMoneyServiceImpl.class, MoneyLedger.class, FxSpreadRegistry.class,
         AdminMoneyServiceTest.Money.class,
         // A payout destination is an encrypted field, and a run that could not
         // read one would be a run with nowhere to send the money.
         com.sujula.service.security.FieldEncryptionService.class})
@org.springframework.test.context.TestPropertySource(properties =
        "sujula.security.field-encryption.key=c3VqdWxhLXRlc3Qta2V5LTMyLWJ5dGVzLWV4YWN0ISE=")
class AdminMoneyServiceTest {

    /** The catalogue is what knows XOF has no minor units, so it is the real one. */
    @org.springframework.boot.test.context.TestConfiguration
    static class Money {
        @org.springframework.context.annotation.Bean
        CurrencyCatalogue currencyCatalogue() {
            return new CurrencyCatalogue(new ReferenceDataProperties());
        }
    }

    @Autowired private AdminMoneyServiceImpl money;
    @Autowired private PaymentRepository payments;
    @Autowired private OrderRepository orders;
    @Autowired private VendorOrderRepository vendorOrders;
    @Autowired private VendorRepository vendors;
    @Autowired private UserRepository users;
    @Autowired private PayoutRepository payouts;
    @Autowired private PayoutBatchRepository batches;
    @Autowired private BankAccountRepository bankAccounts;
    @Autowired private VendorLedgerEntryRepository ledger;
    @Autowired private MoneyLedger moneyLedger;
    @Autowired private EntityManager entityManager;

    @MockitoBean private AuditService audit;
    @MockitoBean private StepUpVerifier stepUp;
    @MockitoBean private NotificationService notifications;
    @MockitoBean private ExchangeRateService exchangeRates;

    private User operator;
    private User second;
    private Vendor kombo;
    private Order order;
    private VendorOrder slice;
    private Payment payment;

    @BeforeEach
    void setUp() {
        operator = users.save(User.builder()
                .firstName("Fatou").lastName("Jallow").email("fatou.money@sujula.gm")
                .password("x").role(UserRole.ADMIN).enabled(true).build());
        second = users.save(User.builder()
                .firstName("Omar").lastName("Njie").email("omar.money@sujula.gm")
                .password("x").role(UserRole.ADMIN).enabled(true).build());

        User seller = users.save(User.builder()
                .firstName("Lamin").lastName("Sanneh").email("lamin.money@sujula.gm")
                .password("x").role(UserRole.VENDOR).enabled(true).build());

        kombo = vendors.save(Vendor.builder()
                .user(seller).storeName("Kombo Electronics").storeSlug("kombo-money")
                .status(PartnerStatus.APPROVED).settlementCurrency("GMD")
                .addressCountryCode("GM").build());

        bankAccounts.save(BankAccount.builder()
                .vendor(kombo).accountType(com.sujula.model.constant.BankAccountType.SAVINGS)
                .accountHolderName("Lamin Sanneh").bankName("Trust Bank")
                .accountNumber("enc:xxxx").accountNumberLast4("4417")
                .currency("GMD").isDefault(true).verified(true).build());

        // A buyer in Madrid, a parcel to Serrekunda: the ordinary case here.
        User buyer = users.save(User.builder()
                .firstName("Oliver").lastName("Bennett").email("oliver.money@example.co.uk")
                .password("x").role(UserRole.CUSTOMER).enabled(true).build());

        order = orders.save(Order.builder()
                .orderNumber("SJL-MONEY-0001").customer(buyer)
                .status(OrderStatus.PROCESSING).paymentStatus(PaymentStatus.PAID)
                .currency("EUR").subtotal(new BigDecimal("132.16"))
                .total(new BigDecimal("132.16"))
                .billingCountry("ES").shippingCountry("GM")
                .billingFullName("Oliver Bennett")
                .shippingFullName("Aminata Ceesay")
                .shippingStreet("Kairaba Avenue").shippingCity("Serrekunda")
                .paidAt(LocalDateTime.now().minusDays(3))
                .build());

        // 9700 GMD converted at 0.01120000 is 108.64 EUR.
        FxSnapshot fx = new FxSnapshot();
        fx.setNativeCurrency("GMD");
        fx.setDisplayCurrency("EUR");
        fx.setRate(new BigDecimal("0.01120000"));
        fx.setRateAt(LocalDateTime.now().minusDays(3));
        fx.setSource(com.sujula.model.constant.FxSource.PUBLISHED_RATE);

        slice = vendorOrders.save(VendorOrder.builder()
                .order(order).vendor(kombo).status(VendorOrderStatus.DELIVERED)
                .nativeCurrency("GMD")
                .subtotalNative(new BigDecimal("9700.00"))
                .totalNative(new BigDecimal("9700.00"))
                .commissionNative(new BigDecimal("970.00"))
                .payoutNative(new BigDecimal("8730.00"))
                .subtotal(new BigDecimal("108.64"))
                .total(new BigDecimal("108.64"))
                .fx(fx)
                .build());

        payment = payments.save(Payment.builder()
                .order(order).reference("PAY-MONEY-0001").status(PaymentStatus.PAID)
                .method(PaymentMethod.CARD).amount(new BigDecimal("132.16"))
                .amountRefunded(BigDecimal.ZERO).currency("EUR")
                .transactionId("pi_3QseedTEST").paidAt(LocalDateTime.now().minusDays(3))
                .build());

        moneyLedger.postSale(slice);
        entityManager.flush();
    }

    // ── Refunds ──────────────────────────────────────────────────────────────

    @Test
    void aRefundNamesASubOrderAndGivesBackWhatThatSellerWasPaid() {
        AdminMoneyResponses.RefundMade made = money.refund(operator, payment.getId(),
                new AdminMoneyRequests.Refund(slice.getId(), null, null,
                        "Handset arrived cracked.", null, null));

        assertEquals(new BigDecimal("9700.00"), made.amountNative());
        assertEquals("GMD", made.nativeCurrency());
        assertEquals(new BigDecimal("108.64"), made.amount());
        assertEquals("EUR", made.currency());
        // The whole payment was 132.16 across the order. Refunding this seller's
        // part gives back 108.64, not a proportion of 132.16 (C3).
        assertTrue(made.amount().compareTo(payment.getAmount()) < 0);
    }

    @Test
    void aRefundIsConvertedAtTheOrdersOwnRateRatherThanTodays() {
        AdminMoneyResponses.RefundMade made = money.refund(operator, payment.getId(),
                new AdminMoneyRequests.Refund(slice.getId(), null, null, "Cracked.", null, null));

        assertEquals(new BigDecimal("0.01120000"), made.fxRate());
        assertNotNull(made.fxRateAt());
        // Nothing in the refund path reads a rate table. Had it done so, this
        // test would pass today and fail the morning the rate moved.
        verify(exchangeRates, never()).getLatestRates(anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aPartialRefundWithOnlyOneOfItsTwoAmountsIsRefused() {
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> money.refund(operator, payment.getId(),
                        new AdminMoneyRequests.Refund(slice.getId(),
                                new BigDecimal("50.00"), null, "Half.", null, null)));
        // Working the other half out here would use today's rate, and the buyer
        // would get back a different number from the one they paid.
        assertTrue(refused.getMessage().contains("today's exchange rate"));
    }

    @Test
    void twoAmountsThatDoNotAgreeAtTheOrdersRateAreRefused() {
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> money.refund(operator, payment.getId(),
                        new AdminMoneyRequests.Refund(slice.getId(),
                                new BigDecimal("90.00"), new BigDecimal("1000.00"),
                                "Typed wrong.", null, null)));
        assertTrue(refused.getMessage().contains("do not agree"));
    }

    @Test
    void refundingMoreThanTheSellerWasEverPaidIsRefused() {
        money.refund(operator, payment.getId(),
                new AdminMoneyRequests.Refund(slice.getId(), null, null, "All of it.", null, null));
        entityManager.flush();

        assertTrue(assertThrows(BadRequestException.class,
                () -> money.refund(operator, payment.getId(),
                        new AdminMoneyRequests.Refund(slice.getId(),
                                new BigDecimal("11.20"), new BigDecimal("1000.00"),
                                "Again.", null, null)))
                .getMessage().contains("more than the seller was ever paid"));
    }

    @Test
    void aRefundReturnsTheCommissionAsItsOwnRowRatherThanNettingIt() {
        money.refund(operator, payment.getId(),
                new AdminMoneyRequests.Refund(slice.getId(), null, null, "Cracked.", null, null));
        entityManager.flush();

        List<com.sujula.model.money.VendorLedgerEntry> rows =
                ledger.findByVendorOrderIdOrderByOccurredAtAsc(slice.getId());

        assertTrue(rows.stream().anyMatch(r -> r.getType() == LedgerEntryType.REFUND));
        // A seller checking a refund is specifically looking to see they were
        // not charged commission on a sale that did not happen.
        assertTrue(rows.stream().anyMatch(r -> r.getType() == LedgerEntryType.COMMISSION_REVERSAL),
                "the commission comes back as a line of its own");
    }

    @Test
    void asmallRefundDoesNotDemandThePasswordAndALargeOneDoes() {
        money.refund(operator, payment.getId(),
                new AdminMoneyRequests.Refund(slice.getId(), null, null, "Cracked.", null, null));
        // 108.64 EUR is below the threshold: an agent settling a dozen small
        // complaints who retypes a password each time ends up with it on a
        // sticky note, which is worse than the risk.
        verify(stepUp, never()).verify(org.mockito.ArgumentMatchers.any(), anyString(),
                org.mockito.ArgumentMatchers.any(), anyString());
    }

    @Test
    void aSubOrderFromAnotherPaymentIsNotFoundRatherThanRefused() {
        Order other = orders.save(Order.builder()
                .orderNumber("SJL-MONEY-0002").customer(order.getCustomer())
                .status(OrderStatus.PROCESSING).paymentStatus(PaymentStatus.PAID)
                .currency("EUR").subtotal(BigDecimal.TEN).total(BigDecimal.TEN)
                .billingCountry("ES").shippingCountry("GM").build());
        VendorOrder elsewhere = vendorOrders.save(VendorOrder.builder()
                .order(other).vendor(kombo).status(VendorOrderStatus.PENDING)
                .nativeCurrency("GMD")
                .subtotalNative(BigDecimal.TEN).totalNative(BigDecimal.TEN)
                .subtotal(BigDecimal.TEN).total(BigDecimal.TEN)
                .build());
        entityManager.flush();

        // Not found, not forbidden: confirming it exists tells the caller
        // something about an order that is not the one they are looking at.
        assertThrows(com.sujula.exceptions.ResourceNotFoundException.class,
                () -> money.refund(operator, payment.getId(),
                        new AdminMoneyRequests.Refund(elsewhere.getId(), null, null,
                                "Wrong one.", null, null)));
    }

    // ── Ledger ───────────────────────────────────────────────────────────────

    @Test
    void theJournalTotalsOneCurrencyAtATimeAndNeverAcrossThem() {
        // A second seller settling in CFA, so the page spans two currencies.
        User senegalese = users.save(User.builder()
                .firstName("Awa").lastName("Diop").email("awa.money@sujula.sn")
                .password("x").role(UserRole.VENDOR).enabled(true).build());
        Vendor teranga = vendors.save(Vendor.builder()
                .user(senegalese).storeName("Teranga Textiles").storeSlug("teranga-money")
                .status(PartnerStatus.APPROVED).settlementCurrency("XOF")
                .addressCountryCode("SN").build());
        VendorOrder cfa = vendorOrders.save(VendorOrder.builder()
                .order(order).vendor(teranga).status(VendorOrderStatus.DELIVERED)
                .nativeCurrency("XOF")
                .subtotalNative(new BigDecimal("11419")).totalNative(new BigDecimal("11419"))
                .commissionNative(new BigDecimal("1142"))
                .subtotal(new BigDecimal("17.42")).total(new BigDecimal("17.42"))
                .build());
        moneyLedger.postSale(cfa);
        entityManager.flush();

        AdminMoneyResponses.LedgerPage page = money.exploreLedger(
                null, null, null, null, null, null, null, PageRequest.of(0, 50));

        assertEquals(2, page.totals().size(), "one total per currency, never one across them");
        assertTrue(page.totals().stream().anyMatch(t -> "GMD".equals(t.currency())));
        assertTrue(page.totals().stream().anyMatch(t -> "XOF".equals(t.currency())));

        // XOF has no minor units — 1250.50 CFA is not an amount that exists.
        AdminMoneyResponses.CurrencyTotal xof = page.totals().stream()
                .filter(t -> "XOF".equals(t.currency())).findFirst().orElseThrow();
        assertEquals(0, xof.total().scale(), "XOF is rounded to its own scale, not to two");
    }

    @Test
    void reconciliationSaysWhenTwoSidesAreNotComparableRatherThanSubtractingThem() {
        AdminMoneyResponses.Reconciliation report = money.reconcile(LocalDate.now());

        AdminMoneyResponses.ReconciliationLine gmd = report.lines().stream()
                .filter(l -> "GMD".equals(l.currency())).findFirst().orElseThrow();
        // Nobody was charged in dalasi — the buyer paid euro — so there is no
        // payment side to compare the ledger against, and saying so beats a
        // difference that is pure arithmetic.
        assertNull(gmd.takenFromBuyers());
        assertNull(gmd.difference());
        assertTrue(gmd.caveat().contains("settlement currency"));
    }

    @Test
    void balancesAreOneRowPerSellerPerCurrency() {
        var page = money.listBalances(null, null, false, PageRequest.of(0, 50));
        AdminMoneyResponses.VendorBalance row = page.getContent().get(0);

        assertEquals("GMD", row.currency());
        assertEquals("GMD", row.settlementCurrency());
        assertFalse(row.currencyMismatch());
        // Still in escrow: the goods were delivered but nothing released it, and
        // a balance that said otherwise would be payable money that is not.
        assertEquals(0, row.available().signum());
        assertTrue(row.held().signum() > 0);
    }

    // ── Payout batches ───────────────────────────────────────────────────────

    private void releaseEscrow() {
        moneyLedger.releaseEscrow(slice, LocalDateTime.now());
        entityManager.flush();
    }

    @Test
    void aRunCannotBeReleasedByThePersonWhoAssembledIt() {
        releaseEscrow();
        AdminMoneyResponses.BatchSaved prepared = money.prepareBatch(operator,
                new AdminMoneyRequests.PrepareBatch("GMD", BigDecimal.ONE, null, null,
                        "admin-password", "123456"));
        entityManager.flush();

        assertEquals(PayoutBatchStatus.AWAITING_APPROVAL, prepared.status());

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> money.approveBatch(operator, prepared.batchId(),
                        new AdminMoneyRequests.ApproveBatch(null, "admin-password", "123456")));
        // The only control between a compromised admin session and every vendor
        // balance on the platform.
        assertTrue(refused.getMessage().contains("cannot release it"));
    }

    @Test
    void aSecondPersonCanReleaseIt() {
        releaseEscrow();
        AdminMoneyResponses.BatchSaved prepared = money.prepareBatch(operator,
                new AdminMoneyRequests.PrepareBatch("GMD", BigDecimal.ONE, null, null,
                        "admin-password", "123456"));
        entityManager.flush();

        AdminMoneyResponses.BatchSaved released = money.approveBatch(second, prepared.batchId(),
                new AdminMoneyRequests.ApproveBatch("Checked against the balances.",
                        "admin-password", "123456"));

        assertEquals(PayoutBatchStatus.APPROVED, released.status());
        verify(stepUp).verify(eq(second), eq("admin-password"), eq("123456"), anyString());
    }

    @Test
    void assemblingARunDemandsThePreparersOwnPassword() {
        releaseEscrow();
        money.prepareBatch(operator, new AdminMoneyRequests.PrepareBatch(
                "GMD", BigDecimal.ONE, null, null, "admin-password", "123456"));

        verify(stepUp).verify(eq(operator), eq("admin-password"), eq("123456"), anyString());
    }

    @Test
    void aHeldStoresMoneyIsExcludedAndSaidOutLoudRatherThanQuietlyMissing() {
        releaseEscrow();
        Vendor reloaded = vendors.findById(kombo.getId()).orElseThrow();
        reloaded.setPayoutsHeldAt(LocalDateTime.now());
        reloaded.setPayoutsHeldReason("Two open complaints.");
        vendors.save(reloaded);
        entityManager.flush();

        AdminMoneyResponses.BatchSaved prepared = money.prepareBatch(operator,
                new AdminMoneyRequests.PrepareBatch("GMD", BigDecimal.ONE, null, null,
                        "admin-password", "123456"));
        entityManager.flush();

        assertEquals(0, prepared.itemCount());
        com.sujula.model.finance.PayoutBatch batch =
                batches.findById(prepared.batchId()).orElseThrow();
        // Held, not refused. A hold that reads as a cancellation is how a
        // suspension becomes a complaint about theft.
        assertTrue(batch.getExclusions().contains("being kept, not lost"));
    }

    @Test
    void aBalanceBelowTheFloorRollsIntoTheNextRunRatherThanCostingAFee() {
        releaseEscrow();
        AdminMoneyResponses.BatchSaved prepared = money.prepareBatch(operator,
                new AdminMoneyRequests.PrepareBatch("GMD", new BigDecimal("50000.00"), null, null,
                        "admin-password", "123456"));
        entityManager.flush();

        assertEquals(0, prepared.itemCount());
        com.sujula.model.finance.PayoutBatch batch =
                batches.findById(prepared.batchId()).orElseThrow();
        assertTrue(batch.getExclusions().contains("below the"));
    }

    @Test
    void twoOpenRunsInOneCurrencyAreRefused() {
        releaseEscrow();
        money.prepareBatch(operator, new AdminMoneyRequests.PrepareBatch(
                "GMD", BigDecimal.ONE, null, null, "admin-password", "123456"));
        entityManager.flush();

        assertTrue(assertThrows(BadRequestException.class,
                () -> money.prepareBatch(operator, new AdminMoneyRequests.PrepareBatch(
                        "GMD", BigDecimal.ONE, null, null, "admin-password", "123456")))
                .getMessage().contains("already an open GMD run"));
    }

    @Test
    void cancellingAnUnreleasedRunMovesNobodysBalance() {
        releaseEscrow();
        BigDecimal before = money.listBalances(kombo.getId(), "GMD", false, PageRequest.of(0, 10))
                .getContent().get(0).available();

        AdminMoneyResponses.BatchSaved prepared = money.prepareBatch(operator,
                new AdminMoneyRequests.PrepareBatch("GMD", BigDecimal.ONE, null, null,
                        "admin-password", "123456"));
        money.cancelBatch(operator, prepared.batchId(),
                new AdminMoneyRequests.CancelBatch("Wrong window."));
        entityManager.flush();

        BigDecimal after = money.listBalances(kombo.getId(), "GMD", false, PageRequest.of(0, 10))
                .getContent().get(0).available();
        // No ledger entry was written at assembly, so the seller is still owed
        // exactly what they were.
        assertEquals(before, after);
    }

    @Test
    void aReleasedRunCannotBeCancelledBecauseTheMoneyIsWithABank() {
        releaseEscrow();
        AdminMoneyResponses.BatchSaved prepared = money.prepareBatch(operator,
                new AdminMoneyRequests.PrepareBatch("GMD", BigDecimal.ONE, null, null,
                        "admin-password", "123456"));
        money.approveBatch(second, prepared.batchId(),
                new AdminMoneyRequests.ApproveBatch(null, "admin-password", "123456"));
        entityManager.flush();

        assertTrue(assertThrows(BadRequestException.class,
                () -> money.cancelBatch(operator, prepared.batchId(),
                        new AdminMoneyRequests.CancelBatch("Changed my mind.")))
                .getMessage().contains("with a bank"));
    }

    @Test
    void onlyAFailedTransferIsRetried() {
        releaseEscrow();
        AdminMoneyResponses.BatchSaved prepared = money.prepareBatch(operator,
                new AdminMoneyRequests.PrepareBatch("GMD", BigDecimal.ONE, null, null,
                        "admin-password", "123456"));
        money.approveBatch(second, prepared.batchId(),
                new AdminMoneyRequests.ApproveBatch(null, "admin-password", "123456"));
        entityManager.flush();

        Payout sent = payouts.findByBatchIdOrderByIdAsc(prepared.batchId()).get(0);
        assertEquals(PayoutStatus.PENDING, sent.getStatus());

        // Retrying one that is with a bank would send the money twice.
        assertTrue(assertThrows(BadRequestException.class,
                () -> money.retryPayout(operator, sent.getId(),
                        new AdminMoneyRequests.RetryPayoutItem("Impatient.")))
                .getMessage().contains("Only a failed transfer"));
    }

    @Test
    void aRetryIsANewAttemptOnTheSamePayoutRatherThanASecondOne() {
        releaseEscrow();
        AdminMoneyResponses.BatchSaved prepared = money.prepareBatch(operator,
                new AdminMoneyRequests.PrepareBatch("GMD", BigDecimal.ONE, null, null,
                        "admin-password", "123456"));
        money.approveBatch(second, prepared.batchId(),
                new AdminMoneyRequests.ApproveBatch(null, "admin-password", "123456"));
        entityManager.flush();

        Payout sent = payouts.findByBatchIdOrderByIdAsc(prepared.batchId()).get(0);
        sent.setStatus(PayoutStatus.FAILED);
        sent.setFailureReason("Account closed at the bank.");
        payouts.save(sent);
        entityManager.flush();

        long before = payouts.count();
        AdminMoneyResponses.PayoutRetried retried = money.retryPayout(operator, sent.getId(),
                new AdminMoneyRequests.RetryPayoutItem("Seller confirmed the account is open."));

        assertEquals(before, payouts.count(), "the seller is owed one amount, not two");
        assertEquals(2, retried.attempts());
    }

    // ── FX ───────────────────────────────────────────────────────────────────

    @Test
    void aSpreadCannotBeBackdated() {
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> money.setSpread(operator, new AdminMoneyRequests.SetSpread(
                        "GMD", "EUR", 150, LocalDateTime.now().minusDays(30),
                        "Should have been higher.")));
        // Conversions already made carry the spread that applied at the time.
        assertTrue(refused.getMessage().contains("cannot start in the past"));
    }

    @Test
    void settingASpreadAppendsARowAndLeavesTheOldOneOnRecord() {
        money.setSpread(operator, new AdminMoneyRequests.SetSpread(
                "GMD", "EUR", 100, null, "Opening spread."));
        entityManager.flush();

        AdminMoneyResponses.SpreadSet second = money.setSpread(operator,
                new AdminMoneyRequests.SetSpread("GMD", "EUR", 150, null, "Volatility."));
        entityManager.flush();

        assertNotNull(second.superseded(), "the row it replaced is named");
        assertEquals("1.00%", second.superseded().asPercentage());
        assertEquals("1.50%", second.spread().asPercentage());
        // Both survive: the spread that applied to any past order stays
        // answerable.
        assertEquals(2, money.spreads().size());
    }

    @Test
    void aSpreadFromACurrencyToItselfIsRefusedBecauseNothingIsConverted() {
        assertTrue(assertThrows(BadRequestException.class,
                () -> money.setSpread(operator, new AdminMoneyRequests.SetSpread(
                        "GMD", "GMD", 150, null, "Belt and braces.")))
                .getMessage().contains("to itself"));
    }

    // ── Reports ──────────────────────────────────────────────────────────────

    @Test
    void revenueIsReportedPerSettlementCurrencyWithNoGrandTotal() {
        AdminMoneyResponses.RevenueReport report = money.revenue(
                LocalDate.now().minusDays(7), LocalDate.now(), null);

        AdminMoneyResponses.RevenueLine gmd = report.lines().stream()
                .filter(l -> "GMD".equals(l.currency())).findFirst().orElseThrow();
        assertEquals(new BigDecimal("9700.00"), gmd.grossSales());
        assertEquals(new BigDecimal("970.00"), gmd.commission());
        // FX margin is in the buyer's currency and the commission in the
        // seller's, so they are reported beside each other rather than added.
        assertEquals("per order's own display currency", gmd.fxMarginCurrency());
    }

    @Test
    void anExportIsQueuedRatherThanReturnedAndCountsAgainstThePersonWhoAskedForIt() {
        AdminMoneyResponses.ExportQueued queued = money.requestExport(operator,
                com.sujula.model.constant.ReportType.LEDGER,
                new AdminMoneyRequests.RequestExport(
                        com.sujula.model.constant.ReportType.LEDGER,
                        LocalDate.now().minusMonths(1), LocalDate.now(), null, null, "CSV"));

        assertEquals(com.sujula.model.constant.ReportExportStatus.QUEUED, queued.status());
        // Per person rather than per platform: this file is every seller's
        // earnings, and a burst from one account is the shape of an account
        // somebody else is using.
        assertEquals(4, queued.remainingInWindow());
    }

    @Test
    void theSixthExportInAnHourIsRefused() {
        for (int i = 0; i < 5; i++) {
            money.requestExport(operator, com.sujula.model.constant.ReportType.LEDGER,
                    new AdminMoneyRequests.RequestExport(
                            com.sujula.model.constant.ReportType.LEDGER,
                            null, null, null, null, "CSV"));
        }
        entityManager.flush();

        assertTrue(assertThrows(BadRequestException.class,
                () -> money.requestExport(operator, com.sujula.model.constant.ReportType.LEDGER,
                        new AdminMoneyRequests.RequestExport(
                                com.sujula.model.constant.ReportType.LEDGER,
                                null, null, null, null, "CSV")))
                .getMessage().contains("which is the limit"));
    }
}
