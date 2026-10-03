package com.sujula.service.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.sujula.dto.request.admin.AdminMoneyRequests;
import com.sujula.dto.request.money.MoneyRequests;
import com.sujula.exceptions.BadRequestException;
import com.sujula.model.constant.BankAccountType;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.UserRole;
import com.sujula.model.user.BankAccount;
import com.sujula.model.user.Payout;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.finance.PayoutBatchRepository;
import com.sujula.repository.money.PayoutRepository;
import com.sujula.repository.money.VendorLedgerEntryRepository;
import com.sujula.repository.user.BankAccountRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.AuditService;
import com.sujula.service.ExchangeRateService;
import com.sujula.service.NotificationService;
import com.sujula.service.admin.impl.AdminMoneyServiceImpl;
import com.sujula.service.money.impl.VendorMoneyServiceImpl;
import com.sujula.service.reference.CurrencyCatalogue;
import com.sujula.service.reference.ReferenceDataProperties;
import com.sujula.service.refund.RefundCoordinator;
import com.sujula.service.security.StepUpVerifier;

/** Real relational proof that one vendor balance has one payout commitment winner. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({VendorMoneyServiceImpl.class, AdminMoneyServiceImpl.class, MoneyLedger.class,
        StatementRenderer.class, FxSpreadRegistry.class,
        PayoutCommitmentConcurrencyIntegrationTest.Money.class,
        com.sujula.service.security.FieldEncryptionService.class})
@TestPropertySource(properties =
        "sujula.security.field-encryption.key=c3VqdWxhLXRlc3Qta2V5LTMyLWJ5dGVzLWV4YWN0ISE=")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PayoutCommitmentConcurrencyIntegrationTest {

    @TestConfiguration
    static class Money {
        @Bean
        CurrencyCatalogue currencyCatalogue() {
            return CurrencyCatalogue.of(new ReferenceDataProperties());
        }
    }

    @Autowired private VendorMoneyServiceImpl vendorMoney;
    @Autowired private AdminMoneyServiceImpl adminMoney;
    @Autowired private MoneyLedger moneyLedger;
    @Autowired private VendorRepository vendors;
    @Autowired private UserRepository users;
    @Autowired private BankAccountRepository bankAccounts;
    @Autowired private PayoutRepository payouts;
    @Autowired private VendorLedgerEntryRepository entries;
    @Autowired private PayoutBatchRepository batches;
    @Autowired private TransactionTemplate transactions;

    @MockitoBean private AuditService audit;
    @MockitoBean private StepUpVerifier stepUp;
    @MockitoBean private NotificationService notifications;
    @MockitoBean private ExchangeRateService exchangeRates;
    @MockitoBean private RefundCoordinator refundCoordinator;

    @Test
    void twoSelfServiceRequestsCannotCommitTheSameBalance() throws Exception {
        Fixture fixture = fixture("GMD", "self");
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        race(
                () -> requestSelf(fixture, succeeded, rejected),
                () -> requestSelf(fixture, succeeded, rejected));

        Snapshot snapshot = snapshot(fixture.vendorId(), "GMD");
        assertEquals(1, succeeded.get());
        assertEquals(1, rejected.get());
        assertEquals(0, snapshot.committed().compareTo(new BigDecimal("100.00")));
        assertEquals(0, snapshot.available().signum());
    }

    @Test
    void batchPreparationAndSelfServiceHaveOneCommitmentWinner() throws Exception {
        Fixture fixture = fixture("XOF", "batch-self");

        race(
                () -> adminMoney.prepareBatch(fixture.admin(), prepare("XOF")),
                () -> {
                    try {
                        vendorMoney.requestPayout(fixture.vendorUserId(),
                                new MoneyRequests.RequestPayout("XOF", null));
                    } catch (BadRequestException expectedLoser) {
                        // Whichever request gets the vendor lock second sees zero.
                    }
                });

        Snapshot snapshot = snapshot(fixture.vendorId(), "XOF");
        assertEquals(0, snapshot.committed().compareTo(new BigDecimal("100")));
        assertEquals(0, snapshot.available().signum());
        assertEquals(1, snapshot.payoutCount());
    }

    @Test
    void twoBatchPreparationsCannotCommitBeyondAvailability() throws Exception {
        Fixture fixture = fixture("USD", "two-batches");

        race(
                () -> prepareIgnoringOpenRun(fixture.admin(), "USD"),
                () -> prepareIgnoringOpenRun(fixture.admin(), "USD"));

        Snapshot snapshot = snapshot(fixture.vendorId(), "USD");
        assertEquals(0, snapshot.committed().compareTo(new BigDecimal("100.00")));
        assertEquals(0, snapshot.available().signum());
        assertEquals(1, snapshot.payoutCount());
        assertTrue(batches.count() >= 1);
    }

    private Fixture fixture(String currency, String label) {
        return transactions.execute(ignored -> {
            User admin = users.save(user("admin-" + label + "@sujula.gm", UserRole.ADMIN));
            User seller = users.save(user("seller-" + label + "@sujula.gm", UserRole.VENDOR));
            Vendor vendor = vendors.save(Vendor.builder()
                    .user(seller).storeName("Race " + label).storeSlug("race-" + label)
                    .status(PartnerStatus.APPROVED).settlementCurrency(currency)
                    .addressCountryCode("GM").build());
            bankAccounts.save(BankAccount.builder()
                    .vendor(vendor).accountType(BankAccountType.SAVINGS)
                    .accountHolderName("Race Seller").bankName("Race Bank")
                    .accountNumber("enc:xxxx").accountNumberLast4("0042")
                    .currency(currency).isDefault(true).verified(true).build());
            moneyLedger.postAdjustment(vendor, new BigDecimal("100.00"), currency,
                    "Concurrency fixture opening balance", admin);
            return new Fixture(admin, seller.getId(), vendor.getId());
        });
    }

    private void requestSelf(Fixture fixture, AtomicInteger succeeded, AtomicInteger rejected) {
        try {
            vendorMoney.requestPayout(fixture.vendorUserId(),
                    new MoneyRequests.RequestPayout("GMD", null));
            succeeded.incrementAndGet();
        } catch (BadRequestException expectedLoser) {
            rejected.incrementAndGet();
        }
    }

    private void prepareIgnoringOpenRun(User admin, String currency) {
        try {
            adminMoney.prepareBatch(admin, prepare(currency));
        } catch (BadRequestException expectedLoser) {
            // The open-batch check may reject before the common vendor lock.
        }
    }

    private static AdminMoneyRequests.PrepareBatch prepare(String currency) {
        return new AdminMoneyRequests.PrepareBatch(currency, BigDecimal.ONE, null, null,
                "admin-password", "123456");
    }

    private Snapshot snapshot(Long vendorId, String currency) {
        return transactions.execute(ignored -> {
            List<Payout> vendorPayouts = payouts.findForVendor(
                    vendorId, null, PageRequest.of(0, 20)).getContent();
            BigDecimal committed = vendorPayouts.stream()
                    .flatMap(payout -> entries.findByPayoutId(payout.getId()).stream())
                    .filter(entry -> entry.getType()
                            == com.sujula.model.constant.LedgerEntryType.PAYOUT)
                    .map(entry -> entry.getAmount().abs())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return new Snapshot(committed, moneyLedger.balance(vendorId, currency).available(),
                    vendorPayouts.size());
        });
    }

    private static void race(Runnable first, Runnable second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            List<java.util.concurrent.Future<?>> futures = List.of(
                    pool.submit(() -> runWhenReleased(ready, start, first)),
                    pool.submit(() -> runWhenReleased(ready, start, second)));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (var future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        }
    }

    private static void runWhenReleased(CountDownLatch ready, CountDownLatch start,
                                        Runnable operation) {
        ready.countDown();
        await(start);
        operation.run();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent start timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static User user(String email, UserRole role) {
        return User.builder().email(email).password("x").firstName("Race").lastName("Person")
                .role(role).enabled(true).build();
    }

    private record Fixture(User admin, Long vendorUserId, Long vendorId) {
    }

    private record Snapshot(BigDecimal committed, BigDecimal available, int payoutCount) {
    }
}
