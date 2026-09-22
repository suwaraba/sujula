package com.sujula.config.seed;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.sujula.model.Address;
import com.sujula.model.Notification;
import com.sujula.model.auth.AccountDataRequest;
import com.sujula.model.auth.MfaRecoveryCode;
import com.sujula.model.auth.OAuthAccount;
import com.sujula.model.auth.PhoneVerification;
import com.sujula.model.auth.UserSession;
import com.sujula.model.constant.AuthProvider;
import com.sujula.model.constant.DataRequestStatus;
import com.sujula.model.constant.DataRequestType;
import com.sujula.model.constant.GeocodeConfidence;
import com.sujula.model.constant.NotificationChannel;
import com.sujula.model.constant.NotificationEvent;
import com.sujula.model.constant.SessionRevocationReason;
import com.sujula.model.constant.UserRole;
import com.sujula.model.notification.NotificationPreference;
import com.sujula.model.notification.PushDevice;
import com.sujula.model.user.User;

/**
 * The people, and everything that hangs off an account.
 *
 * <p>The roll call is chosen to make the marketplace's own shape visible in the
 * data: buyers in Madrid, London, Paris and Stockholm; sellers in Banjul, Dakar,
 * Serrekunda and Thiès; drivers and pickup-point operators on both sides of the
 * border. A dataset where everybody is in one country and one currency cannot
 * demonstrate a single rule this system is built around.
 *
 * <p>Account states are sampled deliberately rather than incidentally. There is
 * an account that is blocked, one flagged for fraud, one locked out by failed
 * logins, one that never verified its email, one holding a live password-reset
 * token and one that has been disabled. Those are the rows that break login
 * code, and the whole reason to have sample data is to have them on hand.
 */
@Component
class PeopleStage implements SeedStage {

    @Override
    public String name() {
        return "People and accounts";
    }

    @Override
    public void seed(SeedCatalogue cat) {
        users(cat);
        cat.flush();
        addresses(cat);
        oauthAccounts(cat);
        sessions(cat);
        phoneVerifications(cat);
        recoveryCodes(cat);
        dataRequests(cat);
        pushDevices(cat);
        notificationPreferences(cat);
        notifications(cat);
    }

    // ── Users ────────────────────────────────────────────────────────────────

    private void users(SeedCatalogue cat) {
        String hash = cat.samplePasswordHash();

        // Staff.
        put(cat, "admin", User.builder()
                .email("admin@sujula.gm").password(hash)
                .firstName("Adama").lastName("Bojang").phone("+2203000001")
                .role(UserRole.ADMIN)
                .enabled(true).emailVerified(true).phoneVerified(true)
                .preferredCurrency("GMD").preferredLanguage("en").detectedCountryCode("GM")
                // The only account with a second factor, because it is the only
                // one whose compromise hands over the whole platform.
                .totpSecret("JBSWY3DPEHPK3PXP").totpEnabled(true).totpVerified(true)
                .build());

        put(cat, "support", User.builder()
                .email("support@sujula.gm").password(hash)
                .firstName("Haddy").lastName("Njie").phone("+2203000002")
                .role(UserRole.SUPPORT)
                .enabled(true).emailVerified(true).phoneVerified(true)
                .preferredCurrency("GMD").preferredLanguage("en").detectedCountryCode("GM")
                .build());

        // Sellers.
        put(cat, "fatou", User.builder()
                .email("fatou.njie@banjulphones.gm").password(hash)
                .firstName("Fatou").lastName("Njie").phone("+2207100101")
                .role(UserRole.VENDOR)
                .enabled(true).emailVerified(true).phoneVerified(true)
                .preferredCurrency("GMD").preferredLanguage("en").detectedCountryCode("GM")
                .build());

        put(cat, "omar", User.builder()
                .email("omar.diop@dakartech.sn").password(hash)
                .firstName("Omar").lastName("Diop").phone("+221770200202")
                .role(UserRole.VENDOR)
                .enabled(true).emailVerified(true).phoneVerified(true)
                .preferredCurrency("XOF").preferredLanguage("fr").detectedCountryCode("SN")
                .build());

        put(cat, "awa", User.builder()
                .email("awa.camara@serrekundahome.gm").password(hash)
                .firstName("Awa").lastName("Camara").phone("+2207100303")
                .role(UserRole.VENDOR)
                .enabled(true).emailVerified(true).phoneVerified(false)
                .preferredCurrency("GMD").preferredLanguage("en").detectedCountryCode("GM")
                .build());

        put(cat, "lamin", User.builder()
                .email("lamin.sanneh@kololistyle.gm").password(hash)
                .firstName("Lamin").lastName("Sanneh").phone("+2207100404")
                .role(UserRole.VENDOR)
                .enabled(true).emailVerified(true).phoneVerified(true)
                .preferredCurrency("GMD").preferredLanguage("en").detectedCountryCode("GM")
                .build());

        put(cat, "ndeye", User.builder()
                .email("ndeye.fall@thiesmarket.sn").password(hash)
                .firstName("Ndèye").lastName("Fall").phone("+221770500505")
                .role(UserRole.VENDOR)
                .enabled(true).emailVerified(true).phoneVerified(false)
                .preferredCurrency("XOF").preferredLanguage("fr").detectedCountryCode("SN")
                .build());

        put(cat, "sona", User.builder()
                .email("sona.badjie@kerewancrafts.gm").password(hash)
                .firstName("Sona").lastName("Badjie").phone("+2207100606")
                .role(UserRole.VENDOR)
                .enabled(true).emailVerified(true).phoneVerified(false)
                .preferredCurrency("GMD").preferredLanguage("en").detectedCountryCode("GM")
                .build());

        put(cat, "alieu", User.builder()
                .email("alieu.ceesay@farafennifoods.gm").password(hash)
                .firstName("Alieu").lastName("Ceesay").phone("+2207100707")
                .role(UserRole.VENDOR)
                .enabled(true).emailVerified(false).phoneVerified(false)
                .preferredCurrency("GMD").preferredLanguage("en").detectedCountryCode("GM")
                .build());

        // Buyers. The diaspora half pays in EUR, GBP and SEK and ships to the
        // Gambia and Senegal; the local half pays and receives in the same place.
        put(cat, "isatou", User.builder()
                .email("isatou.ceesay@example.es").password(hash)
                .firstName("Isatou").lastName("Ceesay").phone("+34600111222")
                .role(UserRole.CUSTOMER)
                .enabled(true).emailVerified(true).phoneVerified(true)
                .preferredCurrency("EUR").preferredLanguage("es").detectedCountryCode("ES")
                .build());

        put(cat, "modou", User.builder()
                .email("modou.jallow@example.co.uk").password(hash)
                .firstName("Modou").lastName("Jallow").phone("+447700900111")
                .role(UserRole.CUSTOMER)
                .enabled(true).emailVerified(true).phoneVerified(true)
                .preferredCurrency("GBP").preferredLanguage("en").detectedCountryCode("GB")
                .build());

        put(cat, "binta", User.builder()
                .email("binta.touray@example.gm").password(hash)
                .firstName("Binta").lastName("Touray").phone("+2207200111")
                .role(UserRole.CUSTOMER)
                .enabled(true).emailVerified(true).phoneVerified(true)
                .preferredCurrency("GMD").preferredLanguage("en").detectedCountryCode("GM")
                .build());

        put(cat, "sally", User.builder()
                .email("sally.mendy@example.fr").password(hash)
                .firstName("Sally").lastName("Mendy").phone("+33600222333")
                .role(UserRole.CUSTOMER)
                .enabled(true).emailVerified(true).phoneVerified(false)
                .preferredCurrency("EUR").preferredLanguage("fr").detectedCountryCode("FR")
                .build());

        put(cat, "cheikh", User.builder()
                .email("cheikh.ndiaye@example.sn").password(hash)
                .firstName("Cheikh").lastName("Ndiaye").phone("+221770333444")
                .role(UserRole.CUSTOMER)
                .enabled(true).emailVerified(true).phoneVerified(true)
                .preferredCurrency("XOF").preferredLanguage("fr").detectedCountryCode("SN")
                .build());

        // Awkward accounts. Each of these is a login path somebody has to handle.
        put(cat, "yankuba", User.builder()
                .email("yankuba.bah@example.gm").password(hash)
                .firstName("Yankuba").lastName("Bah").phone("+2207200222")
                .role(UserRole.CUSTOMER)
                .enabled(true).emailVerified(false).phoneVerified(false)
                .preferredCurrency("GMD").preferredLanguage("en").detectedCountryCode("GM")
                .emailVerificationToken("sample-verify-yankuba-1f4c9")
                .emailVerificationTokenExpiry(cat.hoursAhead(20))
                .build());

        put(cat, "mariama", User.builder()
                .email("mariama.sowe@example.se").password(hash)
                .firstName("Mariama").lastName("Sowe").phone("+46700111222")
                .role(UserRole.CUSTOMER)
                .enabled(true).emailVerified(true).phoneVerified(true)
                .preferredCurrency("SEK").preferredLanguage("en").detectedCountryCode("SE")
                .passwordResetToken("sample-reset-mariama-8b21d")
                .passwordResetTokenExpiry(cat.hoursAhead(1))
                .build());

        put(cat, "baboucarr", User.builder()
                .email("baboucarr.jatta@example.gm").password(hash)
                .firstName("Baboucarr").lastName("Jatta").phone("+2207200333")
                .role(UserRole.CUSTOMER)
                .enabled(true).emailVerified(true).phoneVerified(true)
                .blocked(true)
                .preferredCurrency("GMD").preferredLanguage("en").detectedCountryCode("GM")
                .build());

        put(cat, "fanta", User.builder()
                .email("fanta.kanteh@example.gm").password(hash)
                .firstName("Fanta").lastName("Kanteh").phone("+2207200444")
                .role(UserRole.CUSTOMER)
                .enabled(true).emailVerified(true).phoneVerified(false)
                .fraud(true)
                .preferredCurrency("GMD").preferredLanguage("en").detectedCountryCode("GM")
                .build());

        put(cat, "sulayman", User.builder()
                .email("sulayman.gaye@example.gm").password(hash)
                .firstName("Sulayman").lastName("Gaye").phone("+2207200555")
                .role(UserRole.CUSTOMER)
                .enabled(true).emailVerified(true).phoneVerified(true)
                .preferredCurrency("GMD").preferredLanguage("en").detectedCountryCode("GM")
                .failedLoginAttempts(5)
                .lastFailedLoginAt(cat.hoursAgo(1))
                .lockedUntil(cat.hoursAhead(1))
                .build());

        put(cat, "kaddy", User.builder()
                .email("kaddy.secka@example.gm").password(hash)
                .firstName("Kaddy").lastName("Secka").phone("+2207200666")
                .role(UserRole.CUSTOMER)
                .enabled(false).emailVerified(true).phoneVerified(true)
                .preferredCurrency("GMD").preferredLanguage("en").detectedCountryCode("GM")
                .build());

        // Drivers and pickup-point operators.
        put(cat, "ebrima", User.builder()
                .email("ebrima.colley@sujula.gm").password(hash)
                .firstName("Ebrima").lastName("Colley").phone("+2207300111")
                .role(UserRole.DELIVERY)
                .enabled(true).emailVerified(true).phoneVerified(true)
                .preferredCurrency("GMD").preferredLanguage("en").detectedCountryCode("GM")
                .build());

        put(cat, "saikou", User.builder()
                .email("saikou.barrow@sujula.gm").password(hash)
                .firstName("Saikou").lastName("Barrow").phone("+2207300222")
                .role(UserRole.DELIVERY)
                .enabled(true).emailVerified(true).phoneVerified(true)
                .preferredCurrency("GMD").preferredLanguage("en").detectedCountryCode("GM")
                .build());

        put(cat, "aminata", User.builder()
                .email("aminata.sarr@sujula.sn").password(hash)
                .firstName("Aminata").lastName("Sarr").phone("+221770777888")
                .role(UserRole.DELIVERY)
                .enabled(true).emailVerified(true).phoneVerified(true)
                .preferredCurrency("XOF").preferredLanguage("fr").detectedCountryCode("SN")
                .build());

        put(cat, "ousman", User.builder()
                .email("ousman.faal@sujula.gm").password(hash)
                .firstName("Ousman").lastName("Faal").phone("+2207300333")
                .role(UserRole.DELIVERY)
                .enabled(true).emailVerified(true).phoneVerified(false)
                .preferredCurrency("GMD").preferredLanguage("en").detectedCountryCode("GM")
                .build());

        put(cat, "bakary", User.builder()
                .email("bakary.mendy@sujula.gm").password(hash)
                .firstName("Bakary").lastName("Mendy").phone("+2207300444")
                .role(UserRole.DELIVERY)
                .enabled(true).emailVerified(true).phoneVerified(true)
                .preferredCurrency("GMD").preferredLanguage("en").detectedCountryCode("GM")
                .build());

        put(cat, "jainaba", User.builder()
                .email("jainaba.drammeh@sujula.gm").password(hash)
                .firstName("Jainaba").lastName("Drammeh").phone("+2207300555")
                .role(UserRole.DELIVERY)
                .enabled(true).emailVerified(true).phoneVerified(true)
                .preferredCurrency("GMD").preferredLanguage("en").detectedCountryCode("GM")
                .build());

        put(cat, "musa", User.builder()
                .email("musa.jarju@sujula.gm").password(hash)
                .firstName("Musa").lastName("Jarju").phone("+2207400111")
                .role(UserRole.PICKUP_OPERATOR)
                .enabled(true).emailVerified(true).phoneVerified(true)
                .preferredCurrency("GMD").preferredLanguage("en").detectedCountryCode("GM")
                .build());

        put(cat, "adama", User.builder()
                .email("adama.diallo@sujula.sn").password(hash)
                .firstName("Adama").lastName("Diallo").phone("+221770888999")
                .role(UserRole.PICKUP_OPERATOR)
                .enabled(true).emailVerified(true).phoneVerified(true)
                .preferredCurrency("XOF").preferredLanguage("fr").detectedCountryCode("SN")
                .build());
    }

    private void put(SeedCatalogue cat, String key, User user) {
        cat.users.put(key, cat.save(user));
    }

    // ── Addresses ────────────────────────────────────────────────────────────

    /**
     * Where people are, and where their parcels go — which are not the same list.
     *
     * <p>Isatou has two: a home in Madrid she pays from and a sister's house in
     * Serrekunda she sends to. That one buyer holding two addresses in two
     * continents is the case C1 exists for, so it is the first thing this
     * dataset puts on the table.
     */
    private void addresses(SeedCatalogue cat) {
        cat.addresses.put("isatou-madrid", cat.save(Address.builder()
                .user(cat.user("isatou")).label("Home")
                .fullName("Isatou Ceesay").phone("+34600111222")
                .street("Calle de Bravo Murillo 145").apartmentSuite("3ºB")
                .city("Madrid").state("Comunidad de Madrid").postalCode("28020").countryCode("ES")
                .latitude(40.4599).longitude(-3.6996)
                .geocodeConfidence(GeocodeConfidence.EXACT).geocodedAt(cat.daysAgo(40))
                .isDefault(true)
                .build()));

        cat.addresses.put("isatou-serrekunda", cat.save(Address.builder()
                .user(cat.user("isatou")).label("Sister — Serrekunda")
                .fullName("Aji Ceesay").phone("+2207700100")
                .street("Sayerr Jobe Avenue, near Westfield")
                .city("Serrekunda").state("Kanifing").countryCode("GM")
                .latitude(13.4383).longitude(-16.6781)
                // Confirmed by dropping a pin: the street has no number, so the
                // geocoder could only ever have been approximate.
                .geocodeConfidence(GeocodeConfidence.USER_CONFIRMED)
                .geocodedAt(cat.daysAgo(38)).pinConfirmedAt(cat.daysAgo(38))
                .isDefault(false)
                .build()));

        cat.addresses.put("modou-london", cat.save(Address.builder()
                .user(cat.user("modou")).label("Home")
                .fullName("Modou Jallow").phone("+447700900111")
                .street("214 Seven Sisters Road").apartmentSuite("Flat 4")
                .city("London").state("England").postalCode("N4 3NX").countryCode("GB")
                .latitude(51.5720).longitude(-0.1050)
                .geocodeConfidence(GeocodeConfidence.INTERPOLATED).geocodedAt(cat.daysAgo(30))
                .isDefault(true)
                .build()));

        cat.addresses.put("modou-brikama", cat.save(Address.builder()
                .user(cat.user("modou")).label("Mother — Brikama")
                .fullName("Mariama Jallow").phone("+2207700200")
                .street("Brikama Nyambai Road, opposite the mosque")
                .city("Brikama").state("West Coast").countryCode("GM")
                .latitude(13.2712).longitude(-16.6494)
                .geocodeConfidence(GeocodeConfidence.CENTROID).geocodedAt(cat.daysAgo(29))
                .isDefault(false)
                .build()));

        cat.addresses.put("binta-banjul", cat.save(Address.builder()
                .user(cat.user("binta")).label("Home")
                .fullName("Binta Touray").phone("+2207200111")
                .street("12 Rene Blain Street")
                .city("Banjul").state("Banjul").countryCode("GM")
                .latitude(13.4549).longitude(-16.5790)
                .geocodeConfidence(GeocodeConfidence.EXACT).geocodedAt(cat.daysAgo(60))
                .isDefault(true)
                .build()));

        cat.addresses.put("sally-paris", cat.save(Address.builder()
                .user(cat.user("sally")).label("Domicile")
                .fullName("Sally Mendy").phone("+33600222333")
                .street("18 Rue du Faubourg Saint-Denis")
                .city("Paris").state("Île-de-France").postalCode("75010").countryCode("FR")
                .latitude(48.8710).longitude(2.3540)
                .geocodeConfidence(GeocodeConfidence.EXACT).geocodedAt(cat.daysAgo(15))
                .isDefault(true)
                .build()));

        cat.addresses.put("cheikh-dakar", cat.save(Address.builder()
                .user(cat.user("cheikh")).label("Maison")
                .fullName("Cheikh Ndiaye").phone("+221770333444")
                .street("Avenue Cheikh Anta Diop, Point E")
                .city("Dakar").state("Dakar").countryCode("SN")
                .latitude(14.6937).longitude(-17.4441)
                .geocodeConfidence(GeocodeConfidence.APPROXIMATE).geocodedAt(cat.daysAgo(12))
                .isDefault(true)
                .build()));

        // Never geocoded at all: no key configured when it was saved, which is
        // the degraded path the application is expected to survive.
        cat.addresses.put("mariama-stockholm", cat.save(Address.builder()
                .user(cat.user("mariama")).label("Hem")
                .fullName("Mariama Sowe").phone("+46700111222")
                .street("Rinkebysvängen 72")
                .city("Stockholm").state("Stockholms län").postalCode("163 74").countryCode("SE")
                .geocodeConfidence(GeocodeConfidence.NONE)
                .isDefault(true)
                .build()));

        cat.addresses.put("yankuba-bakau", cat.save(Address.builder()
                .user(cat.user("yankuba")).label("Home")
                .fullName("Yankuba Bah").phone("+2207200222")
                .street("Atlantic Road, Bakau New Town")
                .city("Bakau").state("Kanifing").countryCode("GM")
                .latitude(13.4780).longitude(-16.6810)
                .geocodeConfidence(GeocodeConfidence.CENTROID).geocodedAt(cat.daysAgo(5))
                .isDefault(true)
                .build()));

        // Soft-deleted. Orders placed to it must still resolve, which is why the
        // row is kept rather than removed.
        cat.addresses.put("binta-old", cat.save(Address.builder()
                .user(cat.user("binta")).label("Old flat")
                .fullName("Binta Touray").phone("+2207200111")
                .street("Kairaba Avenue, above the pharmacy")
                .city("Serrekunda").state("Kanifing").countryCode("GM")
                .geocodeConfidence(GeocodeConfidence.APPROXIMATE).geocodedAt(cat.daysAgo(200))
                .deletedAt(cat.daysAgo(20))
                .isDefault(false)
                .build()));
    }

    // ── Federated sign-in ────────────────────────────────────────────────────

    private void oauthAccounts(SeedCatalogue cat) {
        cat.save(OAuthAccount.builder()
                .user(cat.user("isatou")).provider(AuthProvider.GOOGLE)
                .providerUserId("google-oauth2|114500219937712")
                .email("isatou.ceesay@example.es")
                .lastUsedAt(cat.daysAgo(1))
                .build());
        cat.save(OAuthAccount.builder()
                .user(cat.user("isatou")).provider(AuthProvider.APPLE)
                .providerUserId("apple|001742.8f3c91ab.0917")
                // Apple's private relay: the address the platform holds is not
                // the address the person actually reads.
                .email("k9x2mq7w4p@privaterelay.appleid.com")
                .lastUsedAt(cat.daysAgo(22))
                .build());
        cat.save(OAuthAccount.builder()
                .user(cat.user("modou")).provider(AuthProvider.GOOGLE)
                .providerUserId("google-oauth2|108334920017745")
                .email("modou.jallow@example.co.uk")
                .lastUsedAt(cat.hoursAgo(6))
                .build());
        cat.save(OAuthAccount.builder()
                .user(cat.user("sally")).provider(AuthProvider.GOOGLE)
                .providerUserId("google-oauth2|117620038845511")
                .email("sally.mendy@example.fr")
                .lastUsedAt(cat.daysAgo(9))
                .build());
        cat.save(OAuthAccount.builder()
                .user(cat.user("cheikh")).provider(AuthProvider.APPLE)
                .providerUserId("apple|002911.4bd7e20c.1145")
                .email("cheikh.ndiaye@example.sn")
                .build());
        // Linked and never used since: the account was created through the
        // provider and the person has signed in with a password ever after.
        cat.save(OAuthAccount.builder()
                .user(cat.user("mariama")).provider(AuthProvider.GOOGLE)
                .providerUserId("google-oauth2|119005472233810")
                .email("mariama.sowe@example.se")
                .build());
    }

    // ── Sessions ─────────────────────────────────────────────────────────────

    private void sessions(SeedCatalogue cat) {
        cat.save(session(cat, "isatou", "a1f9c2d7e4b60381a1f9c2d7e4b60381a1f9c2d7e4b60381a1f9c2d7e4b60381",
                "iPhone 13 — Safari", "ES", cat.hoursAgo(2), cat.daysAhead(29), null, null));
        cat.save(session(cat, "isatou", "b2e8d3c6f5a71492b2e8d3c6f5a71492b2e8d3c6f5a71492b2e8d3c6f5a71492",
                "Windows — Chrome", "ES", cat.daysAgo(3), cat.daysAhead(27), null, null));
        cat.save(session(cat, "modou", "c3d7e4b5a6928503c3d7e4b5a6928503c3d7e4b5a6928503c3d7e4b5a6928503",
                "Pixel 7 — Chrome", "GB", cat.hoursAgo(6), cat.daysAhead(30), null, null));

        // Signed out normally.
        UserSession loggedOut = session(cat, "binta",
                "d4c6f5a7b8039614d4c6f5a7b8039614d4c6f5a7b8039614d4c6f5a7b8039614",
                "Android — Chrome", "GM", cat.daysAgo(4), cat.daysAhead(26), null, null);
        loggedOut.setRevokedAt(cat.daysAgo(4));
        loggedOut.setRevokedReason(SessionRevocationReason.LOGOUT);
        cat.save(loggedOut);

        // Expired on its own, never revoked. The distinction matters: one is a
        // decision somebody made and the other is time passing.
        cat.save(session(cat, "sally", "e5b5a6c7d9140725e5b5a6c7d9140725e5b5a6c7d9140725e5b5a6c7d9140725",
                "MacBook — Safari", "FR", cat.daysAgo(40), cat.daysAgo(10), null, null));

        // A refresh token presented twice. The second presentation is the alarm:
        // either the token leaked or a client is replaying, and both answers end
        // the session.
        UserSession replayed = session(cat, "cheikh",
                "f6a4b7c8e0251836f6a4b7c8e0251836f6a4b7c8e0251836f6a4b7c8e0251836",
                "Android — Samsung Internet", "SN", cat.daysAgo(6), cat.daysAhead(24),
                "096f6a4b7c8e0251836f6a4b7c8e0251836f6a4b7c8e0251836f6a4b7c8e0251", null);
        replayed.setRevokedAt(cat.daysAgo(6));
        replayed.setRevokedReason(SessionRevocationReason.TOKEN_REPLAY);
        cat.save(replayed);

        // Ended by an administrator when the account was blocked.
        UserSession byAdmin = session(cat, "baboucarr",
                "07b3c8d9f13629470f7b3c8d9f13629471b3c8d9f1362947cb3c8d9f13629470",
                "Android — Chrome", "GM", cat.daysAgo(8), cat.daysAhead(22), null, null);
        byAdmin.setRevokedAt(cat.daysAgo(7));
        byAdmin.setRevokedReason(SessionRevocationReason.ADMIN);
        cat.save(byAdmin);

        // Support, working as the buyer with it recorded on the session itself.
        // Impersonation that is not visible in the session row is impersonation
        // nobody can audit afterwards.
        UserSession impersonated = session(cat, "isatou",
                "18c2d9e0a24730581f8c2d9e0a24730582c2d9e0a2473058d38c2d9e0a247305",
                "Support console", "GM", cat.hoursAgo(3), cat.hoursAhead(1), null,
                cat.user("support").getId());
        impersonated.setImpersonationReason("Buyer could not see the tracking page for SJL-1004");
        cat.save(impersonated);

        // Everything ended after a password change.
        UserSession credentialsChanged = session(cat, "mariama",
                "29d1e0f1b35841692a9d1e0f1b35841693b1e0f1b3584169e4d1e0f1b3584169",
                "Windows — Edge", "SE", cat.daysAgo(2), cat.daysAhead(28), null, null);
        credentialsChanged.setRevokedAt(cat.hoursAgo(20));
        credentialsChanged.setRevokedReason(SessionRevocationReason.CREDENTIALS_CHANGED);
        cat.save(credentialsChanged);

        cat.save(session(cat, "admin", "3ae0f1a2c469527a3ae0f1a2c469527a3ae0f1a2c469527a3ae0f1a2c469527a",
                "Windows — Firefox", "GM", cat.hoursAgo(1), cat.daysAhead(30), null, null));
    }

    private UserSession session(SeedCatalogue cat, String userKey, String tokenHash,
                                String device, String country, LocalDateTime lastSeen,
                                LocalDateTime expires, String previousHash, Long impersonatedBy) {
        return UserSession.builder()
                .user(cat.user(userKey))
                .refreshTokenHash(tokenHash)
                .previousTokenHash(previousHash)
                .deviceLabel(device)
                .userAgent("Mozilla/5.0 (sample data) " + device)
                .ipAddress("198.51.100." + (Math.abs(tokenHash.hashCode()) % 200 + 10))
                .countryCode(country)
                .lastSeenAt(lastSeen)
                .expiresAt(expires)
                .impersonatedByUserId(impersonatedBy)
                .build();
    }

    // ── Phone verification ───────────────────────────────────────────────────

    /**
     * Codes sent to phones, in every state the flow can leave one in.
     *
     * <p>A live one, a spent one, an expired one and one that burned all five
     * attempts. The last is the row that proves the attempt ceiling is enforced
     * rather than merely declared.
     */
    private void phoneVerifications(SeedCatalogue cat) {
        cat.save(PhoneVerification.builder()
                .user(cat.user("isatou")).phone("+34600111222")
                .codeHash("$2a$10$sampleHashForConfirmedCodeIsatouAAAAAAAAAAAAAAAAAAAAAAAAAAA")
                .expiresAt(cat.daysAgo(40).plusMinutes(10))
                .attempts(1).confirmedAt(cat.daysAgo(40))
                .build());
        cat.save(PhoneVerification.builder()
                .user(cat.user("modou")).phone("+447700900111")
                .codeHash("$2a$10$sampleHashForConfirmedCodeModouAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
                .expiresAt(cat.daysAgo(30).plusMinutes(10))
                .attempts(0).confirmedAt(cat.daysAgo(30))
                .build());
        cat.save(PhoneVerification.builder()
                .user(cat.user("sally")).phone("+33600222333")
                .codeHash("$2a$10$sampleHashForLiveCodeSallyAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
                .expiresAt(cat.hoursAhead(1))
                .attempts(0)
                .build());
        cat.save(PhoneVerification.builder()
                .user(cat.user("yankuba")).phone("+2207200222")
                .codeHash("$2a$10$sampleHashForExpiredCodeYankubaAAAAAAAAAAAAAAAAAAAAAAAAAAA")
                .expiresAt(cat.hoursAgo(4))
                .attempts(2)
                .build());
        cat.save(PhoneVerification.builder()
                .user(cat.user("fanta")).phone("+2207200444")
                .codeHash("$2a$10$sampleHashForBurntCodeFantaAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
                .expiresAt(cat.hoursAhead(2))
                .attempts(PhoneVerification.MAX_ATTEMPTS)
                .build());
        cat.save(PhoneVerification.builder()
                .user(cat.user("awa")).phone("+2207100303")
                .codeHash("$2a$10$sampleHashForPendingCodeAwaAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
                .expiresAt(cat.hoursAhead(1))
                .attempts(1)
                .build());
    }

    // ── Recovery codes ───────────────────────────────────────────────────────

    private void recoveryCodes(SeedCatalogue cat) {
        // Eight, two of them already spent, because that is what a real set
        // looks like a few months after it was issued.
        for (int i = 1; i <= 8; i++) {
            cat.save(MfaRecoveryCode.builder()
                    .user(cat.user("admin"))
                    .codeHash("$2a$10$sampleRecoveryCodeHashAdmin" + String.format("%02d", i)
                            + "AAAAAAAAAAAAAAAAAAAAAAAAAAA")
                    .usedAt(i <= 2 ? cat.daysAgo(30L - i) : null)
                    .build());
        }
    }

    // ── Data export and erasure ──────────────────────────────────────────────

    private void dataRequests(SeedCatalogue cat) {
        cat.save(AccountDataRequest.builder()
                .reference("DR-2024-000001").user(cat.user("isatou"))
                .type(DataRequestType.EXPORT).status(DataRequestStatus.COMPLETED)
                .startedAt(cat.daysAgo(14)).completedAt(cat.daysAgo(14).plusMinutes(3))
                .downloadUrl("https://files.sujula.gm/exports/DR-2024-000001.zip")
                .downloadExpiresAt(cat.daysAhead(16))
                .build());
        cat.save(AccountDataRequest.builder()
                .reference("DR-2024-000002").user(cat.user("modou"))
                .type(DataRequestType.EXPORT).status(DataRequestStatus.PROCESSING)
                .startedAt(cat.hoursAgo(1))
                .build());
        cat.save(AccountDataRequest.builder()
                .reference("DR-2024-000003").user(cat.user("sally"))
                .type(DataRequestType.EXPORT).status(DataRequestStatus.PENDING)
                .build());
        cat.save(AccountDataRequest.builder()
                .reference("DR-2024-000004").user(cat.user("kaddy"))
                .type(DataRequestType.ERASURE).status(DataRequestStatus.COMPLETED)
                .startedAt(cat.daysAgo(9)).completedAt(cat.daysAgo(9).plusMinutes(12))
                .build());
        cat.save(AccountDataRequest.builder()
                .reference("DR-2024-000005").user(cat.user("mariama"))
                .type(DataRequestType.EXPORT).status(DataRequestStatus.FAILED)
                .startedAt(cat.daysAgo(3)).completedAt(cat.daysAgo(3).plusMinutes(1))
                .failureReason("Object storage rejected the upload: no bucket configured")
                .build());
        // An erasure that cannot complete yet. Money still owed is a lawful
        // reason to keep the rows, and the request stays open rather than being
        // quietly dropped.
        cat.save(AccountDataRequest.builder()
                .reference("DR-2024-000006").user(cat.user("baboucarr"))
                .type(DataRequestType.ERASURE).status(DataRequestStatus.PENDING)
                .build());
    }

    // ── Push devices ─────────────────────────────────────────────────────────

    private void pushDevices(SeedCatalogue cat) {
        cat.save(PushDevice.builder().user(cat.user("isatou"))
                .token("fcm-sample-isatou-ios-0001").platform("IOS")
                .label("iPhone 13").lastSeenAt(cat.hoursAgo(2)).build());
        cat.save(PushDevice.builder().user(cat.user("modou"))
                .token("fcm-sample-modou-android-0002").platform("ANDROID")
                .label("Pixel 7").lastSeenAt(cat.hoursAgo(6)).build());
        cat.save(PushDevice.builder().user(cat.user("binta"))
                .token("fcm-sample-binta-web-0003").platform("WEB")
                .label("Chrome on Windows").lastSeenAt(cat.daysAgo(1)).build());
        cat.save(PushDevice.builder().user(cat.user("ebrima"))
                .token("fcm-sample-ebrima-android-0004").platform("ANDROID")
                .label("Driver phone").lastSeenAt(cat.hoursAgo(1)).build());
        cat.save(PushDevice.builder().user(cat.user("musa"))
                .token("fcm-sample-musa-android-0005").platform("ANDROID")
                .label("Shop tablet").lastSeenAt(cat.hoursAgo(4)).build());
        // Revoked by the person signing out of that device.
        cat.save(PushDevice.builder().user(cat.user("isatou"))
                .token("fcm-sample-isatou-old-0006").platform("ANDROID")
                .label("Old Android").lastSeenAt(cat.daysAgo(60))
                .revokedAt(cat.daysAgo(55)).revokedReason("Signed out on that device")
                .build());
        // Revoked because the provider said the token was gone. Keeping it would
        // mean a failed send on every notification for ever.
        cat.save(PushDevice.builder().user(cat.user("sally"))
                .token("fcm-sample-sally-stale-0007").platform("IOS")
                .label("iPad").lastSeenAt(cat.daysAgo(90))
                .revokedAt(cat.daysAgo(30)).revokedReason("Provider reported UNREGISTERED")
                .build());
    }

    // ── Notification preferences ─────────────────────────────────────────────

    /**
     * Who wants telling about what, and how.
     *
     * <p>Only the rows that differ from the default are worth storing, so these
     * are the opt-outs and the deliberate opt-ins: a buyer who wants parcel
     * codes by SMS but nothing by email, a seller who wants every sale pushed,
     * an account that has switched promotions off everywhere.
     */
    private void notificationPreferences(SeedCatalogue cat) {
        cat.save(preference(cat, "isatou", NotificationEvent.PARCEL_CODE, NotificationChannel.PUSH, true));
        cat.save(preference(cat, "isatou", NotificationEvent.PROMOTION, NotificationChannel.EMAIL, false));
        cat.save(preference(cat, "isatou", NotificationEvent.ORDER_UPDATE, NotificationChannel.IN_APP, true));
        cat.save(preference(cat, "modou", NotificationEvent.PROMOTION, NotificationChannel.PUSH, false));
        cat.save(preference(cat, "modou", NotificationEvent.PARCEL_DELIVERED, NotificationChannel.EMAIL, true));
        cat.save(preference(cat, "binta", NotificationEvent.PLATFORM_NOTICE, NotificationChannel.EMAIL, false));
        cat.save(preference(cat, "fatou", NotificationEvent.SALE_MADE, NotificationChannel.PUSH, true));
        cat.save(preference(cat, "fatou", NotificationEvent.LOW_STOCK, NotificationChannel.EMAIL, true));
        cat.save(preference(cat, "omar", NotificationEvent.ORDER_TO_FULFIL, NotificationChannel.PUSH, true));
        cat.save(preference(cat, "ebrima", NotificationEvent.DELIVERY_OFFERED, NotificationChannel.PUSH, true));
        cat.save(preference(cat, "musa", NotificationEvent.PICKUP_PARCEL_ARRIVED, NotificationChannel.IN_APP, true));
        cat.save(preference(cat, "musa", NotificationEvent.PICKUP_PARCEL_OVERDUE, NotificationChannel.EMAIL, true));
    }

    private NotificationPreference preference(SeedCatalogue cat, String userKey,
                                              NotificationEvent event,
                                              NotificationChannel channel, boolean enabled) {
        return NotificationPreference.builder()
                .user(cat.user(userKey)).event(event).channel(channel).enabled(enabled)
                .build();
    }

    // ── Notifications already sent ───────────────────────────────────────────

    private void notifications(SeedCatalogue cat) {
        cat.save(notification(cat, "isatou", NotificationEvent.ORDER_PLACED,
                "Order SJL-1001 placed", "Thank you. We will tell you when the seller has it ready.",
                "SJL-1001", "IN_APP,EMAIL", true));
        cat.save(notification(cat, "isatou", NotificationEvent.PARCEL_COLLECTED,
                "Your parcel is on its way", "Ebrima Colley collected it from Banjul Phones.",
                "SJL-1001", "IN_APP,PUSH", true));
        cat.save(notification(cat, "isatou", NotificationEvent.PARCEL_DELIVERED,
                "Delivered to Aji Ceesay", "The code was read back at the door in Serrekunda.",
                "SJL-1001", "IN_APP,PUSH,EMAIL", false));
        cat.save(notification(cat, "modou", NotificationEvent.PARCEL_AT_PICKUP_POINT,
                "Ready to collect in Brikama", "Ask for shelf B-14. Bring the six-digit code.",
                "SJL-1003", "IN_APP,PUSH", false));
        cat.save(notification(cat, "modou", NotificationEvent.PARCEL_CODE,
                "Your collection code", "Read this code to whoever hands the parcel over.",
                "SJL-1003", "PUSH", true));
        cat.save(notification(cat, "binta", NotificationEvent.ORDER_CANCELLED,
                "Order SJL-1005 cancelled", "The seller could not fulfil it. You have not been charged.",
                "SJL-1005", "IN_APP,EMAIL", false));
        cat.save(notification(cat, "fatou", NotificationEvent.SALE_MADE,
                "You sold a Tecno Spark 10", "Prepare it for collection within two days.",
                "SJL-1001", "IN_APP,PUSH", true));
        cat.save(notification(cat, "fatou", NotificationEvent.LOW_STOCK,
                "Low stock: Tecno Spark 10 128GB", "Two units left.",
                "SKU-TEC-SPK10-128", "IN_APP", false));
        cat.save(notification(cat, "omar", NotificationEvent.PAYOUT_SENT,
                "Payout sent", "482 750 CFA has been sent to your Ecobank account.",
                "PO-2024-0002", "IN_APP,EMAIL", true));
        cat.save(notification(cat, "ebrima", NotificationEvent.DELIVERY_OFFERED,
                "New delivery offered", "Banjul to Serrekunda, 9.4 km, D 210.",
                "SHP-1001", "PUSH", true));
        cat.save(notification(cat, "cheikh", NotificationEvent.DISPUTE_UPDATE,
                "Your dispute is under review", "Support has asked the seller for a response.",
                "DSP-000002", "IN_APP,EMAIL", false));
        cat.save(notification(cat, "sally", NotificationEvent.SECURITY_ALERT,
                "New sign-in from Paris", "If this was not you, change your password.",
                null, "EMAIL", true));
    }

    /**
     * One notification row.
     *
     * <p>No {@code createdAt} argument, deliberately: the column carries
     * {@code @CreationTimestamp}, so Hibernate overwrites anything set here on
     * the way in. A parameter that looks like it works and does not is worse
     * than not having one.
     */
    private Notification notification(SeedCatalogue cat, String userKey, NotificationEvent event,
                                      String title, String message, String reference,
                                      String sentOn, boolean read) {
        return Notification.builder()
                .user(cat.user(userKey)).event(event)
                .title(title).message(message)
                .referenceId(reference).sentOn(sentOn).read(read)
                .build();
    }
}
