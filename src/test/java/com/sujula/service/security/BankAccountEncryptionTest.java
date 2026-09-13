package com.sujula.service.security;

import com.sujula.model.constant.BankAccountType;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.UserRole;
import com.sujula.model.user.BankAccount;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.user.BankAccountRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the account number is ciphertext in the column, not merely annotated.
 *
 * <p>A converter that is declared but never wired is the failure this exists to
 * catch: everything compiles, every service test passes, the entity round-trips
 * through JPA perfectly — and the database holds a column of account numbers in
 * clear. The only way to know is to read the column back as a raw string, past
 * the mapping that would decrypt it, which is what this does.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
// The @DataJpaTest slice does not scan services, and the converter cannot be
// built without this one. That the context fails rather than falling back to
// plaintext is the intended behaviour, here and on a real deployment.
@Import(FieldEncryptionService.class)
@TestPropertySource(properties =
        "sujula.security.field-encryption.key=c3VqdWxhLXRlc3Qta2V5LTMyLWJ5dGVzLWV4YWN0ISE=")
class BankAccountEncryptionTest {

    private static final String ACCOUNT = "0123456789014417";
    private static final String IBAN = "ES9121000418450200051332";

    @Autowired private BankAccountRepository accounts;
    @Autowired private VendorRepository vendors;
    @Autowired private UserRepository users;
    @Autowired private EntityManager entityManager;

    private Vendor vendor;

    @BeforeEach
    void setUp() {
        User owner = new User();
        owner.setFirstName("Lamin");
        owner.setLastName("Touray");
        owner.setEmail("lamin.crypto@sujula.gm");
        owner.setPassword("x");
        owner.setRole(UserRole.VENDOR);
        owner.setPhone("+2203100099");
        users.save(owner);

        vendor = vendors.save(Vendor.builder()
                .user(owner)
                .storeName("Kombo Electronics")
                .storeSlug("kombo-electronics-crypto")
                .status(PartnerStatus.APPROVED)
                .settlementCurrency("GMD")
                .build());
    }

    private BankAccount save() {
        BankAccount account = accounts.save(BankAccount.builder()
                .vendor(vendor)
                .accountType(BankAccountType.CHECKING)
                .accountHolderName("Lamin Touray")
                .bankName("Trust Bank")
                .accountNumber(ACCOUNT)
                .accountNumberLast4(FieldEncryptionService.last4(ACCOUNT))
                .iban(IBAN)
                .ibanLast4(FieldEncryptionService.last4(IBAN))
                .currency("GMD")
                .isDefault(true)
                .build());
        entityManager.flush();
        entityManager.clear();
        return account;
    }

    @Test
    void theColumnHoldsCiphertextAndNotTheAccountNumber() {
        Long id = save().getId();

        String stored = (String) entityManager
                .createNativeQuery("SELECT account_number FROM vendor_bank_accounts WHERE id = ?1")
                .setParameter(1, id)
                .getSingleResult();

        // The thing that actually matters: the digits are not in the database.
        assertFalse(stored.contains(ACCOUNT), "the account number is stored in clear: " + stored);
        assertFalse(stored.contains("4417"), stored);
        assertTrue(stored.startsWith("enc:v1:"), stored);
        assertNotEquals(ACCOUNT, stored);

        String storedIban = (String) entityManager
                .createNativeQuery("SELECT iban FROM vendor_bank_accounts WHERE id = ?1")
                .setParameter(1, id)
                .getSingleResult();
        assertFalse(storedIban.contains(IBAN), storedIban);
    }

    @Test
    void andItComesBackReadableThroughTheMapping() {
        Long id = save().getId();

        BankAccount reloaded = accounts.findById(id).orElseThrow();

        assertEquals(ACCOUNT, reloaded.getAccountNumber());
        assertEquals(IBAN, reloaded.getIban());
        // The tail stays in clear, which is what a settings page renders.
        assertEquals("4417", reloaded.getAccountNumberLast4());
        assertEquals("1332", reloaded.getIbanLast4());
    }

    @Test
    void twoIdenticalAccountNumbersDoNotProduceIdenticalCiphertext() {
        Long first = save().getId();
        Long second = save().getId();

        String a = rawAccountNumber(first);
        String b = rawAccountNumber(second);

        // A fresh nonce per value. Without one, equal ciphertexts announce that
        // two stores are paid into the same account — which is exactly the kind
        // of thing a leaked dump should not reveal.
        assertNotEquals(a, b);
    }

    /**
     * Pins the constraint the service has to satisfy.
     *
     * <p>{@code bank_name} is NOT NULL, and the commonest payout route in this
     * market is a phone number with a provider rather than a bank. A service
     * that passed the request's null straight through would fail here — at
     * flush, after the caller's step-up has already been spent, so they would
     * have to type their password again to find out.
     *
     * <p>Mocked service tests cannot see this: the constraint lives in the
     * schema, not in Java.
     */
    @Test
    void aPayoutDestinationWithoutABankNameIsRejectedByTheSchema() {
        BankAccount noBank = BankAccount.builder()
                .vendor(vendor)
                .accountType(BankAccountType.MOBILE_MONEY)
                .accountHolderName("Lamin Touray")
                .bankName(null)
                .accountNumber("")
                .mobileMoneyPhone("+2203100002")
                .mobileMoneyLast4("0002")
                .currency("GMD")
                .isDefault(true)
                .build();

        assertThrows(Exception.class, () -> {
            accounts.save(noBank);
            entityManager.flush();
        });
    }

    @Test
    void andOneNamingItsProviderInstead() {
        BankAccount mobileMoney = accounts.save(BankAccount.builder()
                .vendor(vendor)
                .accountType(BankAccountType.MOBILE_MONEY)
                .accountHolderName("Lamin Touray")
                // What the service substitutes when no bank was given.
                .bankName("Africell Money")
                .mobileMoneyProvider("Africell Money")
                // Empty rather than null: the column predates this surface and
                // a mobile-money line genuinely has no account number.
                .accountNumber("")
                .mobileMoneyPhone("+2203100002")
                .mobileMoneyLast4("0002")
                .currency("GMD")
                .isDefault(true)
                .build());
        entityManager.flush();
        entityManager.clear();

        BankAccount reloaded = accounts.findById(mobileMoney.getId()).orElseThrow();
        assertEquals("+2203100002", reloaded.getMobileMoneyPhone());
        assertEquals("0002", reloaded.getMobileMoneyLast4());
        // And the phone number is ciphertext in the column like everything else.
        String stored = (String) entityManager
                .createNativeQuery("SELECT mobile_money_phone FROM vendor_bank_accounts WHERE id = ?1")
                .setParameter(1, mobileMoney.getId())
                .getSingleResult();
        assertTrue(stored.startsWith("enc:v1:"), stored);
        assertFalse(stored.contains("2203100002"), stored);
    }

    private String rawAccountNumber(Long id) {
        return (String) entityManager
                .createNativeQuery("SELECT account_number FROM vendor_bank_accounts WHERE id = ?1")
                .setParameter(1, id)
                .getSingleResult();
    }
}
