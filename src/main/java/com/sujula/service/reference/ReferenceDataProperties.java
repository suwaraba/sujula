package com.sujula.service.reference;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What this deployment says it supports.
 *
 * <p>Reference data is configuration, not code. Which currencies a marketplace
 * trades in, which countries it will ship to and which locales it speaks are
 * decisions that change on a business's timetable rather than a release's, and
 * every one of them was previously a string literal scattered across a dozen
 * classes. The defaults below are the Gambia-first launch: the home market, the
 * CFA neighbours goods actually move between, and the diaspora markets that buy
 * from them.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "sujula.reference")
public class ReferenceDataProperties {

    /** The home market. Falls back to this whenever nothing better is known. */
    private String baseCurrency = "GMD";
    private String baseCountry = "GM";
    private String defaultLocale = "en-GM";

    /**
     * How long a locked exchange rate is honoured.
     *
     * <p>Fifteen minutes is a compromise between two failures. Too short and a
     * buyer filling in a card form watches the total change underneath them; too
     * long and the platform is quoting a rate the market has left behind, which
     * on a volatile pair is a loss taken on every order.
     */
    private Duration fxQuoteTtl = Duration.ofMinutes(15);

    private List<Currency> currencies = defaultCurrencies();
    private List<Country> countries = defaultCountries();
    private List<Locale> locales = defaultLocales();
    private Config config = new Config();

    /**
     * A currency this marketplace can price in.
     *
     * @param minorUnits how many decimal places the currency actually has. Not
     *                   a formatting hint: XOF has none, so 1250.50 CFA is not
     *                   an amount that exists, and rounding it to two places
     *                   produces a total nobody can pay
     */
    @Getter
    @Setter
    public static class Currency {
        private String code;
        private String name;
        private String symbol;
        private int minorUnits = 2;
        /** Whether buyers may shop in it. A currency can be settled in but not offered. */
        private boolean buyerFacing = true;
        /** Whether a vendor may be paid out in it. */
        private boolean settlement = true;

        public Currency() {}

        public Currency(String code, String name, String symbol, int minorUnits,
                        boolean buyerFacing, boolean settlement) {
            this.code = code;
            this.name = name;
            this.symbol = symbol;
            this.minorUnits = minorUnits;
            this.buyerFacing = buyerFacing;
            this.settlement = settlement;
        }
    }

    /**
     * A country, and what may happen there.
     *
     * <p>Buying and shipping are separate flags because they are separate
     * questions. Someone in London buys from a Gambian vendor and has it
     * delivered to a relative in Serekunda: their country supports buying and
     * not shipping, and collapsing the two into one "supported" boolean makes
     * that ordinary transaction impossible to express.
     */
    @Getter
    @Setter
    public static class Country {
        private String code;
        private String name;
        private String currency;
        private String dialCode;
        private boolean buy = true;
        private boolean ship = false;

        public Country() {}

        public Country(String code, String name, String currency, String dialCode,
                       boolean buy, boolean ship) {
            this.code = code;
            this.name = name;
            this.currency = currency;
            this.dialCode = dialCode;
            this.buy = buy;
            this.ship = ship;
        }
    }

    @Getter
    @Setter
    public static class Locale {
        private String tag;
        private String name;
        private String nativeName;
        /** Right-to-left, so a client knows to flip its layout. */
        private boolean rtl = false;

        public Locale() {}

        public Locale(String tag, String name, String nativeName, boolean rtl) {
            this.tag = tag;
            this.name = name;
            this.nativeName = nativeName;
            this.rtl = rtl;
        }
    }

    /**
     * What {@code /config/public} hands an anonymous caller.
     *
     * <p>Everything here is world-readable by construction. It is an allow-list
     * rather than a filtered view of the environment, which is the distinction
     * that keeps a future property named {@code sujula.payment.callback-secret}
     * from appearing in it by accident.
     */
    @Getter
    @Setter
    public static class Config {
        /** Named switches a client may read. Values are booleans, nothing more. */
        private Map<String, Boolean> features = defaultFeatures();

        /** Below these, a client should tell the user to update rather than fail oddly. */
        private Map<String, String> minimumAppVersions = new LinkedHashMap<>(Map.of(
                "android", "1.0.0",
                "ios", "1.0.0",
                "web", "1.0.0"));

        private String supportEmail = "support@sujula.gm";
        private String supportPhone = "+2203100000";
        private String supportWhatsapp = "+2203100000";
        private String termsUrl = "https://sujula.gm/terms";
        private String privacyUrl = "https://sujula.gm/privacy";

        private static Map<String, Boolean> defaultFeatures() {
            Map<String, Boolean> features = new LinkedHashMap<>();
            features.put("guestCheckout", true);
            features.put("payOnDelivery", true);
            features.put("pickupPoints", true);
            features.put("multiCurrency", true);
            features.put("reviews", true);
            features.put("giftCards", true);
            features.put("loyalty", false);
            features.put("returns", false);
            return features;
        }
    }

    // ── Defaults ─────────────────────────────────────────────────────────────

    /**
     * The launch set.
     *
     * <p>Minor units come from ISO 4217, not from habit. XOF and XAF have none —
     * the smallest CFA note is a franc — and getting that wrong is not cosmetic:
     * it produces order totals that cannot be tendered.
     */
    private static List<Currency> defaultCurrencies() {
        List<Currency> currencies = new ArrayList<>();
        currencies.add(new Currency("GMD", "Gambian dalasi",     "D",   2, true,  true));
        currencies.add(new Currency("XOF", "West African CFA franc", "CFA", 0, true, true));
        currencies.add(new Currency("GBP", "Pound sterling",     "£",   2, true,  true));
        currencies.add(new Currency("EUR", "Euro",               "€",   2, true,  true));
        currencies.add(new Currency("USD", "US dollar",          "$",   2, true,  true));
        currencies.add(new Currency("NGN", "Nigerian naira",     "₦",   2, true,  false));
        currencies.add(new Currency("SLE", "Sierra Leonean leone", "Le", 2, true, false));
        currencies.add(new Currency("MRU", "Mauritanian ouguiya", "UM", 2, true,  false));
        currencies.add(new Currency("CAD", "Canadian dollar",    "$",   2, true,  false));
        currencies.add(new Currency("SEK", "Swedish krona",      "kr",  2, true,  false));
        return currencies;
    }

    /**
     * Where goods can go, and where money can come from.
     *
     * <p>Shipping is the short list — the home market and the neighbours a parcel
     * can actually reach overland. Buying is the long one, because the diaspora
     * is a large part of the demand and sends goods home rather than to itself.
     */
    private static List<Country> defaultCountries() {
        List<Country> countries = new ArrayList<>();
        countries.add(new Country("GM", "The Gambia",   "GMD", "+220", true, true));
        countries.add(new Country("SN", "Senegal",      "XOF", "+221", true, true));
        countries.add(new Country("GW", "Guinea-Bissau","XOF", "+245", true, true));
        countries.add(new Country("GN", "Guinea",       "GNF", "+224", true, false));
        countries.add(new Country("ML", "Mali",         "XOF", "+223", true, false));
        countries.add(new Country("MR", "Mauritania",   "MRU", "+222", true, false));
        countries.add(new Country("SL", "Sierra Leone", "SLE", "+232", true, false));
        countries.add(new Country("NG", "Nigeria",      "NGN", "+234", true, false));
        countries.add(new Country("GB", "United Kingdom","GBP","+44",  true, false));
        countries.add(new Country("US", "United States","USD", "+1",   true, false));
        countries.add(new Country("CA", "Canada",       "CAD", "+1",   true, false));
        countries.add(new Country("ES", "Spain",        "EUR", "+34",  true, false));
        countries.add(new Country("FR", "France",       "EUR", "+33",  true, false));
        countries.add(new Country("IT", "Italy",        "EUR", "+39",  true, false));
        countries.add(new Country("DE", "Germany",      "EUR", "+49",  true, false));
        countries.add(new Country("SE", "Sweden",       "SEK", "+46",  true, false));
        return countries;
    }

    private static List<Locale> defaultLocales() {
        List<Locale> locales = new ArrayList<>();
        locales.add(new Locale("en-GM", "English (Gambia)", "English", false));
        locales.add(new Locale("en-GB", "English (UK)",     "English", false));
        locales.add(new Locale("fr-SN", "French (Senegal)", "Français", false));
        locales.add(new Locale("ar",    "Arabic",           "العربية", true));
        return locales;
    }
}
