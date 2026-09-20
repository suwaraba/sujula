package com.sujula.config.seed;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.sujula.model.admin.CommissionRate;
import com.sujula.model.constant.BankAccountType;
import com.sujula.model.constant.GeocodeConfidence;
import com.sujula.model.constant.KycDocumentStatus;
import com.sujula.model.constant.KycDocumentType;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.StorePermission;
import com.sujula.model.constant.StoreStaffStatus;
import com.sujula.model.store.KycDocument;
import com.sujula.model.store.StoreOperatingHours;
import com.sujula.model.store.StoreStaff;
import com.sujula.model.user.BankAccount;
import com.sujula.model.user.Vendor;
import com.sujula.service.security.FieldEncryptionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The sellers, and the paperwork a seller carries.
 *
 * <p>Seven stores, covering every {@code PartnerStatus} there is: one still
 * gathering documents, one applied and waiting, two trading, one on holiday,
 * one suspended with its payouts frozen and one refused outright. A back office
 * whose sample data contains only approved stores cannot show what the approval
 * queue looks like, and the approval queue is most of what that screen is for.
 *
 * <p>Two of them price in CFA. That is not decoration: XOF has no minor unit, so
 * a store whose listings are in it is the one that catches money code which
 * assumes two decimal places everywhere (C2).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class StoreStage implements SeedStage {

    private final FieldEncryptionService encryption;

    @Override
    public String name() {
        return "Stores, KYC, staff and payout destinations";
    }

    @Override
    public void seed(SeedCatalogue cat) {
        vendors(cat);
        cat.flush();
        operatingHours(cat);
        kycDocuments(cat);
        staff(cat);
        bankAccounts(cat);
        commissionRates(cat);
    }

    // ── The stores ───────────────────────────────────────────────────────────

    private void vendors(SeedCatalogue cat) {
        cat.vendors.put("banjul-phones", cat.save(Vendor.builder()
                .user(cat.user("fatou"))
                .storeName("Banjul Phones").storeSlug("banjul-phones")
                .description("Handsets, accessories and screen repairs, on Liberation Avenue since 2016.")
                .storeEmail("hello@banjulphones.gm").storePhone("+2204400101")
                .website("https://banjulphones.gm")
                .logoUrl("https://cdn.sujula.gm/sample/stores/banjul-phones-logo.png")
                .bannerUrl("https://cdn.sujula.gm/sample/stores/banjul-phones-banner.jpg")
                .addressStreet("41 Liberation Avenue").addressCity("Banjul")
                .addressState("Banjul").addressPostalCode("").addressCountryCode("GM")
                .latitude(13.4530).longitude(-16.5775)
                .geocodeConfidence(GeocodeConfidence.EXACT).geocodedAt(cat.daysAgo(180))
                .pickupStreet("41 Liberation Avenue, rear entrance").pickupCity("Banjul")
                .pickupState("Banjul").pickupCountryCode("GM")
                .pickupLatitude(13.4531).pickupLongitude(-16.5773)
                .pickupGeocodeConfidence(GeocodeConfidence.USER_CONFIRMED)
                .pickupInstructions("Ring the bell at the blue door; ask for Fatou.")
                .status(PartnerStatus.APPROVED)
                .settlementCurrency("GMD")
                .balance(SeedCatalogue.money("18450.00"))
                .defaultCommissionRate(SeedCatalogue.money("8.00"))
                .rating(new BigDecimal("4.60")).totalReviews(37).totalSold(214)
                .businessRegistrationNumber("GM-BR-2016-004431").taxNumber("GM-TIN-884120")
                .returnPolicy("Fourteen days for anything unopened; sealed software is final.")
                .shippingPolicy("Ready within two working days. Collection from the shop is free.")
                .handlingDays(2)
                .build()));

        cat.vendors.put("dakar-tech", cat.save(Vendor.builder()
                .user(cat.user("omar"))
                .storeName("Dakar Tech").storeSlug("dakar-tech")
                .description("Téléphones et accessoires, Plateau, Dakar.")
                .storeEmail("bonjour@dakartech.sn").storePhone("+221338200202")
                .addressStreet("14 Rue Mohamed V").addressCity("Dakar")
                .addressState("Dakar").addressCountryCode("SN")
                .latitude(14.6690).longitude(-17.4370)
                .geocodeConfidence(GeocodeConfidence.EXACT).geocodedAt(cat.daysAgo(150))
                .pickupStreet("14 Rue Mohamed V").pickupCity("Dakar").pickupCountryCode("SN")
                .pickupLatitude(14.6690).pickupLongitude(-17.4370)
                .pickupGeocodeConfidence(GeocodeConfidence.EXACT)
                .status(PartnerStatus.ACTIVE)
                // Settles in CFA. Every payout for this store is a whole number
                // of francs, and any figure here with a decimal is a bug.
                .settlementCurrency("XOF")
                .balance(SeedCatalogue.wholeUnits("742500"))
                .defaultCommissionRate(SeedCatalogue.money("10.00"))
                .rating(new BigDecimal("4.30")).totalReviews(21).totalSold(96)
                .businessRegistrationNumber("SN-RC-2018-91207").taxNumber("SN-NINEA-0048219")
                .returnPolicy("Retour sous 7 jours, emballage d'origine.")
                .handlingDays(1)
                .build()));

        cat.vendors.put("serrekunda-home", cat.save(Vendor.builder()
                .user(cat.user("awa"))
                .storeName("Serrekunda Home").storeSlug("serrekunda-home")
                .description("Cookware, bedding and small appliances off Sayerr Jobe Avenue.")
                .storeEmail("orders@serrekundahome.gm").storePhone("+2204400303")
                .addressStreet("Sayerr Jobe Avenue").addressCity("Serrekunda")
                .addressState("Kanifing").addressCountryCode("GM")
                .latitude(13.4390).longitude(-16.6790)
                .geocodeConfidence(GeocodeConfidence.CENTROID).geocodedAt(cat.daysAgo(90))
                .pickupStreet("Sayerr Jobe Avenue, beside the tailor")
                .pickupCity("Serrekunda").pickupCountryCode("GM")
                .pickupLatitude(13.4391).pickupLongitude(-16.6789)
                .pickupGeocodeConfidence(GeocodeConfidence.APPROXIMATE)
                .status(PartnerStatus.APPROVED)
                .settlementCurrency("GMD")
                .balance(SeedCatalogue.money("6200.00"))
                .defaultCommissionRate(SeedCatalogue.money("12.00"))
                .rating(new BigDecimal("4.10")).totalReviews(12).totalSold(48)
                // Away for Tobaski. Listings stay visible; nothing can be bought.
                .vacationMode(true)
                .vacationMessage("Closed for Tobaski. Orders resume on the 20th.")
                .handlingDays(3)
                .build()));

        cat.vendors.put("kololi-style", cat.save(Vendor.builder()
                .user(cat.user("lamin"))
                .storeName("Kololi Style").storeSlug("kololi-style")
                .description("Clothing and fabric, Senegambia strip.")
                .storeEmail("shop@kololistyle.gm").storePhone("+2204400404")
                .addressStreet("Senegambia Strip").addressCity("Kololi")
                .addressState("West Coast").addressCountryCode("GM")
                .latitude(13.4470).longitude(-16.6950)
                .geocodeConfidence(GeocodeConfidence.APPROXIMATE).geocodedAt(cat.daysAgo(70))
                .status(PartnerStatus.SUSPENDED)
                .settlementCurrency("GMD")
                .balance(SeedCatalogue.money("3175.50"))
                .defaultCommissionRate(SeedCatalogue.money("10.00"))
                .rating(new BigDecimal("2.80")).totalReviews(9).totalSold(31)
                .adminNote("Suspended pending a decision on three non-delivery complaints.")
                // Suspension without a payout hold would let the balance leave
                // while the complaints that caused it are still open.
                .payoutsHeldAt(cat.daysAgo(11))
                .payoutsHeldReason("Three open non-delivery cases; funds held until resolved.")
                .handlingDays(2)
                .build()));

        cat.vendors.put("thies-market", cat.save(Vendor.builder()
                .user(cat.user("ndeye"))
                .storeName("Thiès Market").storeSlug("thies-market")
                .description("Épicerie et produits du terroir.")
                .storeEmail("contact@thiesmarket.sn").storePhone("+221339500505")
                .addressStreet("Avenue Léopold Sédar Senghor").addressCity("Thiès")
                .addressState("Thiès").addressCountryCode("SN")
                .geocodeConfidence(GeocodeConfidence.NONE)
                .status(PartnerStatus.PENDING_KYC)
                .settlementCurrency("XOF")
                .balance(SeedCatalogue.wholeUnits("0"))
                .defaultCommissionRate(SeedCatalogue.money("10.00"))
                .adminNote("Business registration uploaded; proof of address still missing.")
                .handlingDays(2)
                .build()));

        cat.vendors.put("kerewan-crafts", cat.save(Vendor.builder()
                .user(cat.user("sona"))
                .storeName("Kerewan Crafts").storeSlug("kerewan-crafts")
                .description("Baskets, carvings and dyed cloth from North Bank.")
                .storeEmail("sona@kerewancrafts.gm").storePhone("+2204400606")
                .addressStreet("Kerewan Market").addressCity("Kerewan")
                .addressState("North Bank").addressCountryCode("GM")
                .latitude(13.4890).longitude(-16.0890)
                .geocodeConfidence(GeocodeConfidence.CENTROID).geocodedAt(cat.daysAgo(6))
                .status(PartnerStatus.PENDING)
                .settlementCurrency("GMD")
                .balance(SeedCatalogue.money("0.00"))
                .defaultCommissionRate(SeedCatalogue.money("10.00"))
                .adminNote("Documents complete. Waiting on a reviewer.")
                .handlingDays(4)
                .build()));

        cat.vendors.put("farafenni-foods", cat.save(Vendor.builder()
                .user(cat.user("alieu"))
                .storeName("Farafenni Foods").storeSlug("farafenni-foods")
                .description("Dried fish, groundnuts and spices.")
                .storeEmail("alieu@farafennifoods.gm").storePhone("+2204400707")
                .addressStreet("Farafenni Highway").addressCity("Farafenni")
                .addressState("North Bank").addressCountryCode("GM")
                .geocodeConfidence(GeocodeConfidence.NONE)
                .status(PartnerStatus.REJECTED)
                .settlementCurrency("GMD")
                .balance(SeedCatalogue.money("0.00"))
                .defaultCommissionRate(SeedCatalogue.money("10.00"))
                .adminNote("Refused: the registration number belongs to a different business.")
                .handlingDays(2)
                .build()));
    }

    // ── When the shops are open ──────────────────────────────────────────────

    /**
     * Opening hours for two stores, covering the three shapes a day can take:
     * open, closed, and open with an unusual window.
     */
    private void operatingHours(SeedCatalogue cat) {
        for (DayOfWeek day : DayOfWeek.values()) {
            boolean sunday = day == DayOfWeek.SUNDAY;
            cat.save(StoreOperatingHours.builder()
                    .vendor(cat.vendor("banjul-phones")).dayOfWeek(day)
                    .closed(sunday)
                    .opensAt(sunday ? null : LocalTime.of(9, 0))
                    // Friday closes early for prayers.
                    .closesAt(sunday ? null
                            : day == DayOfWeek.FRIDAY ? LocalTime.of(12, 30) : LocalTime.of(19, 0))
                    .build());
        }
        for (DayOfWeek day : DayOfWeek.values()) {
            boolean weekend = day == DayOfWeek.SUNDAY;
            cat.save(StoreOperatingHours.builder()
                    .vendor(cat.vendor("dakar-tech")).dayOfWeek(day)
                    .closed(weekend)
                    .opensAt(weekend ? null : LocalTime.of(8, 30))
                    .closesAt(weekend ? null : LocalTime.of(20, 0))
                    .build());
        }
    }

    // ── Identity documents ───────────────────────────────────────────────────

    private void kycDocuments(SeedCatalogue cat) {
        cat.save(KycDocument.builder()
                .vendor(cat.vendor("banjul-phones")).type(KycDocumentType.NATIONAL_ID)
                .status(KycDocumentStatus.ACCEPTED)
                .fileUrl("https://files.sujula.gm/sample/kyc/banjul-phones-id.pdf")
                .originalFilename("fatou-njie-id.pdf").contentType("application/pdf")
                .sizeBytes(412_338L).expiresOn(LocalDate.now().plusYears(4))
                .reviewedBy(cat.user("admin")).reviewedAt(cat.daysAgo(178))
                .build());
        cat.save(KycDocument.builder()
                .vendor(cat.vendor("banjul-phones")).type(KycDocumentType.BUSINESS_REGISTRATION)
                .status(KycDocumentStatus.ACCEPTED)
                .fileUrl("https://files.sujula.gm/sample/kyc/banjul-phones-registration.pdf")
                .originalFilename("registration-2016.pdf").contentType("application/pdf")
                .sizeBytes(880_104L)
                .reviewedBy(cat.user("admin")).reviewedAt(cat.daysAgo(178))
                .build());
        // Superseded: the first upload expired and a newer one replaced it. The
        // old row stays, because the question "what did we accept, and when" has
        // to remain answerable.
        cat.save(KycDocument.builder()
                .vendor(cat.vendor("dakar-tech")).type(KycDocumentType.PROOF_OF_ADDRESS)
                .status(KycDocumentStatus.ACCEPTED)
                .fileUrl("https://files.sujula.gm/sample/kyc/dakar-tech-address-2022.pdf")
                .originalFilename("facture-senelec-2022.pdf").contentType("application/pdf")
                .sizeBytes(210_455L).expiresOn(LocalDate.now().minusMonths(2))
                .reviewedBy(cat.user("admin")).reviewedAt(cat.daysAgo(140))
                .supersededAt(cat.daysAgo(30))
                .build());
        cat.save(KycDocument.builder()
                .vendor(cat.vendor("dakar-tech")).type(KycDocumentType.PROOF_OF_ADDRESS)
                .status(KycDocumentStatus.ACCEPTED)
                .fileUrl("https://files.sujula.gm/sample/kyc/dakar-tech-address-2024.pdf")
                .originalFilename("facture-senelec-2024.pdf").contentType("application/pdf")
                .sizeBytes(233_901L).expiresOn(LocalDate.now().plusMonths(10))
                .reviewedBy(cat.user("support")).reviewedAt(cat.daysAgo(29))
                .build());
        cat.save(KycDocument.builder()
                .vendor(cat.vendor("thies-market")).type(KycDocumentType.BUSINESS_REGISTRATION)
                .status(KycDocumentStatus.SUBMITTED)
                .fileUrl("https://files.sujula.gm/sample/kyc/thies-registration.jpg")
                .originalFilename("registre-commerce.jpg").contentType("image/jpeg")
                .sizeBytes(1_904_220L)
                .build());
        cat.save(KycDocument.builder()
                .vendor(cat.vendor("farafenni-foods")).type(KycDocumentType.BUSINESS_REGISTRATION)
                .status(KycDocumentStatus.REJECTED)
                .fileUrl("https://files.sujula.gm/sample/kyc/farafenni-registration.jpg")
                .originalFilename("registration.jpg").contentType("image/jpeg")
                .sizeBytes(744_100L)
                .rejectionReason("The registration number on this certificate is issued to another business.")
                .reviewedBy(cat.user("admin")).reviewedAt(cat.daysAgo(21))
                .build());
        cat.save(KycDocument.builder()
                .vendor(cat.vendor("kerewan-crafts")).type(KycDocumentType.NATIONAL_ID)
                .status(KycDocumentStatus.SUBMITTED)
                .fileUrl("https://files.sujula.gm/sample/kyc/kerewan-id.pdf")
                .originalFilename("sona-badjie-id.pdf").contentType("application/pdf")
                .sizeBytes(388_210L).expiresOn(LocalDate.now().plusYears(2))
                .build());
        cat.save(KycDocument.builder()
                .vendor(cat.vendor("kololi-style")).type(KycDocumentType.TAX_CERTIFICATE)
                .status(KycDocumentStatus.ACCEPTED)
                .fileUrl("https://files.sujula.gm/sample/kyc/kololi-tax.pdf")
                .originalFilename("tax-clearance.pdf").contentType("application/pdf")
                .sizeBytes(155_003L).expiresOn(LocalDate.now().plusMonths(3))
                .reviewedBy(cat.user("admin")).reviewedAt(cat.daysAgo(68))
                .build());
    }

    // ── Who else can act for a store ─────────────────────────────────────────

    private void staff(SeedCatalogue cat) {
        cat.save(StoreStaff.builder()
                .vendor(cat.vendor("banjul-phones")).user(cat.user("binta"))
                .email("binta.touray@example.gm").displayName("Binta (counter)")
                .status(StoreStaffStatus.ACTIVE)
                .permissions(permissions(StorePermission.ORDERS_VIEW, StorePermission.ORDERS_FULFIL))
                .invitedBy(cat.user("fatou")).acceptedAt(cat.daysAgo(120))
                .build());
        cat.save(StoreStaff.builder()
                .vendor(cat.vendor("banjul-phones"))
                .email("stock@banjulphones.gm").displayName("Stock room")
                .status(StoreStaffStatus.INVITED)
                .permissions(permissions(StorePermission.CATALOGUE_MANAGE))
                .inviteTokenHash("a7c1sampleinvitehashbanjulphones0001aaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .inviteExpiresAt(cat.daysAhead(5))
                .invitedBy(cat.user("fatou"))
                .build());
        // Expired invitation. Still INVITED as a status, but no longer usable —
        // the two are different questions and the row has to answer both.
        cat.save(StoreStaff.builder()
                .vendor(cat.vendor("banjul-phones"))
                .email("temp@banjulphones.gm").displayName("Holiday cover")
                .status(StoreStaffStatus.INVITED)
                .permissions(permissions(StorePermission.ORDERS_VIEW))
                .inviteTokenHash("b8d2sampleinvitehashbanjulphones0002aaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .inviteExpiresAt(cat.daysAgo(4))
                .invitedBy(cat.user("fatou"))
                .build());
        cat.save(StoreStaff.builder()
                .vendor(cat.vendor("dakar-tech")).user(cat.user("cheikh"))
                .email("cheikh.ndiaye@example.sn").displayName("Cheikh (atelier)")
                .status(StoreStaffStatus.ACTIVE)
                .permissions(permissions(StorePermission.ORDERS_VIEW, StorePermission.ORDERS_FULFIL,
                        StorePermission.CATALOGUE_MANAGE))
                .invitedBy(cat.user("omar")).acceptedAt(cat.daysAgo(95))
                .build());
        cat.save(StoreStaff.builder()
                .vendor(cat.vendor("dakar-tech"))
                .email("compta@dakartech.sn").displayName("Comptabilité")
                .status(StoreStaffStatus.ACTIVE)
                .permissions(permissions(StorePermission.FINANCE_VIEW))
                .invitedBy(cat.user("omar")).acceptedAt(cat.daysAgo(60))
                .build());
        cat.save(StoreStaff.builder()
                .vendor(cat.vendor("serrekunda-home"))
                .email("awa.assistant@serrekundahome.gm").displayName("Shop assistant")
                .status(StoreStaffStatus.REVOKED)
                .permissions(permissions(StorePermission.ORDERS_VIEW))
                .invitedBy(cat.user("awa")).acceptedAt(cat.daysAgo(140))
                .revokedAt(cat.daysAgo(20))
                .build());
        cat.save(StoreStaff.builder()
                .vendor(cat.vendor("kololi-style"))
                .email("lamin.brother@kololistyle.gm").displayName("Ousainou")
                .status(StoreStaffStatus.ACTIVE)
                .permissions(permissions(StorePermission.ORDERS_VIEW, StorePermission.STORE_PROFILE_MANAGE,
                        StorePermission.STAFF_MANAGE))
                .invitedBy(cat.user("lamin")).acceptedAt(cat.daysAgo(55))
                .build());
    }

    private Set<StorePermission> permissions(StorePermission... granted) {
        return new LinkedHashSet<>(java.util.Arrays.asList(granted));
    }

    // ── Where the money goes ─────────────────────────────────────────────────

    /**
     * Payout destinations, in the vendor's own currency (C2).
     *
     * <p>Account numbers, IBANs and mobile-money lines go through
     * {@code EncryptedStringConverter}, which refuses to write anything at all
     * when no key is configured. So this checks first and says why it skipped,
     * rather than failing the whole dataset over a property a local machine has
     * no reason to have set.
     */
    private void bankAccounts(SeedCatalogue cat) {
        if (!encryption.isConfigured()) {
            log.warn("[Sample data] No sujula.security.field-encryption.key, so payout destinations "
                    + "were not written. Run with the 'sample' profile, or set that key, to get them.");
            return;
        }

        cat.save(BankAccount.builder()
                .vendor(cat.vendor("banjul-phones")).accountType(BankAccountType.CHECKING)
                .accountHolderName("Fatou Njie").bankName("Trust Bank Gambia")
                .accountNumber("0011002233445").accountNumberLast4(FieldEncryptionService.last4("0011002233445"))
                .routingNumber("TBGM001").swiftCode("TBLGGMGM")
                .currency("GMD").isDefault(true).verified(true)
                .lastChangedBy(cat.user("fatou")).lastChangedAt(cat.daysAgo(170))
                .build());
        cat.save(BankAccount.builder()
                .vendor(cat.vendor("banjul-phones")).accountType(BankAccountType.MOBILE_MONEY)
                .accountHolderName("Fatou Njie").bankName("Africell Money")
                .accountNumber("2207100101").accountNumberLast4(FieldEncryptionService.last4("2207100101"))
                .mobileMoneyPhone("+2207100101")
                .mobileMoneyLast4(FieldEncryptionService.last4("+2207100101"))
                .mobileMoneyProvider("Africell Money")
                .currency("GMD").isDefault(false).verified(true)
                .lastChangedBy(cat.user("fatou")).lastChangedAt(cat.daysAgo(40))
                .build());
        cat.save(BankAccount.builder()
                .vendor(cat.vendor("dakar-tech")).accountType(BankAccountType.CHECKING)
                .accountHolderName("Omar Diop").bankName("Ecobank Sénégal")
                .accountNumber("SN0810100000000112233").accountNumberLast4("2233")
                .iban("SN08 1010 0000 0001 1223 3445 66").ibanLast4("4566")
                .swiftCode("ECOCSNDA")
                .currency("XOF").isDefault(true).verified(true)
                .lastChangedBy(cat.user("omar")).lastChangedAt(cat.daysAgo(148))
                .build());
        cat.save(BankAccount.builder()
                .vendor(cat.vendor("serrekunda-home")).accountType(BankAccountType.SAVINGS)
                .accountHolderName("Awa Camara").bankName("GTBank Gambia")
                .accountNumber("0099887766554").accountNumberLast4(FieldEncryptionService.last4("0099887766554"))
                .currency("GMD").isDefault(true)
                // Unverified: a payout to it would be refused, which is the
                // state worth having a row for.
                .verified(false)
                .lastChangedBy(cat.user("awa")).lastChangedAt(cat.daysAgo(12))
                .build());
        cat.save(BankAccount.builder()
                .vendor(cat.vendor("kololi-style")).accountType(BankAccountType.MOBILE_MONEY)
                .accountHolderName("Lamin Sanneh").bankName("QMoney")
                .accountNumber("2207100404").accountNumberLast4(FieldEncryptionService.last4("2207100404"))
                .mobileMoneyPhone("+2207100404")
                .mobileMoneyLast4(FieldEncryptionService.last4("+2207100404"))
                .mobileMoneyProvider("QMoney")
                .currency("GMD").isDefault(true).verified(true)
                .lastChangedBy(cat.user("lamin")).lastChangedAt(cat.daysAgo(58))
                .build());
        cat.save(BankAccount.builder()
                .vendor(cat.vendor("kerewan-crafts")).accountType(BankAccountType.CRYPTO_WALLET)
                .accountHolderName("Sona Badjie").bankName("USDC (TRON)")
                .accountNumber("TQ5sampleWalletAddressKerewan0001")
                .accountNumberLast4("0001")
                .currency("USD").isDefault(true).verified(false)
                .lastChangedBy(cat.user("sona")).lastChangedAt(cat.daysAgo(5))
                .build());
    }

    // ── What the platform takes ──────────────────────────────────────────────

    /**
     * Commission, as a history rather than a field.
     *
     * <p>Rates are time-boxed: the row that applied to an order placed in March
     * is not the row that applies today, and settling an old order against
     * today's rate silently overpays or underpays somebody. So one closed period
     * and one open one per store, plus a category-specific override and a
     * platform-wide default with no vendor at all.
     */
    private void commissionRates(SeedCatalogue cat) {
        cat.save(CommissionRate.builder()
                .rate(SeedCatalogue.money("10.00"))
                .effectiveFrom(cat.daysAgo(400))
                .setBy(cat.user("admin"))
                .note("Platform default. Applies to any store with no rate of its own.")
                .build());
        cat.save(CommissionRate.builder()
                .vendor(cat.vendor("banjul-phones")).rate(SeedCatalogue.money("10.00"))
                .effectiveFrom(cat.daysAgo(180)).effectiveUntil(cat.daysAgo(60))
                .setBy(cat.user("admin"))
                .note("Opening rate.")
                .build());
        cat.save(CommissionRate.builder()
                .vendor(cat.vendor("banjul-phones")).rate(SeedCatalogue.money("8.00"))
                .effectiveFrom(cat.daysAgo(60))
                .setBy(cat.user("admin"))
                .note("Reduced after the store passed 200 completed orders.")
                .build());
        cat.save(CommissionRate.builder()
                .vendor(cat.vendor("dakar-tech")).rate(SeedCatalogue.money("10.00"))
                .effectiveFrom(cat.daysAgo(150))
                .setBy(cat.user("admin"))
                .build());
        cat.save(CommissionRate.builder()
                .vendor(cat.vendor("serrekunda-home")).rate(SeedCatalogue.money("12.00"))
                .effectiveFrom(cat.daysAgo(90))
                .setBy(cat.user("admin"))
                .note("Higher: bulky goods, more failed deliveries.")
                .build());
        cat.save(CommissionRate.builder()
                .vendor(cat.vendor("kololi-style")).rate(SeedCatalogue.money("10.00"))
                .effectiveFrom(cat.daysAgo(70))
                .setBy(cat.user("admin"))
                .build());
        // Set to end in the future: a scheduled change, not a historical one.
        cat.save(CommissionRate.builder()
                .vendor(cat.vendor("dakar-tech")).rate(SeedCatalogue.money("7.50"))
                .effectiveFrom(cat.daysAhead(30)).effectiveUntil(cat.daysAhead(120))
                .setBy(cat.user("admin"))
                .note("Promotional rate agreed for the next quarter.")
                .build());
    }
}
