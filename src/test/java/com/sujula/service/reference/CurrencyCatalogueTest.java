package com.sujula.service.reference;

import com.sujula.exceptions.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Minor units, which are a correctness matter rather than a formatting one.
 *
 * <p>The CFA franc has no subdivision. There is no centime in circulation, so an
 * amount carrying one is an amount nobody can hand over — and a payout figure
 * carrying half a franc will never reconcile against what the bank moved.
 */
class CurrencyCatalogueTest {

    private ReferenceDataProperties properties;
    private CurrencyCatalogue catalogue;

    @BeforeEach
    void setUp() {
        properties = new ReferenceDataProperties();
        catalogue = new CurrencyCatalogue(properties);
        catalogue.index();
    }

    // ── The whole point ──────────────────────────────────────────────────────

    @Test
    void cfaHasNoSubdivision() {
        assertEquals(0, catalogue.minorUnits("XOF"));
        assertEquals(0, new BigDecimal("1").compareTo(catalogue.smallestUnit("XOF")));
    }

    @Test
    void dalasiAndSterlingHaveTwo() {
        assertEquals(2, catalogue.minorUnits("GMD"));
        assertEquals(2, catalogue.minorUnits("GBP"));
        assertEquals(0, new BigDecimal("0.01").compareTo(catalogue.smallestUnit("GMD")));
    }

    /**
     * The failure this exists to prevent: 1250.50 CFA is not an amount, and
     * rounding to two places produces a total a buyer in Ziguinchor cannot pay.
     */
    @Test
    void roundingIsToTheCurrencysOwnScale() {
        BigDecimal amount = new BigDecimal("1250.50");

        assertEquals(new BigDecimal("1251"), catalogue.round(amount, "XOF"));
        assertEquals(new BigDecimal("1250.50"), catalogue.round(amount, "GMD"));
    }

    @Test
    void roundingIsHalfUpBecauseSomeoneIsAboutToBeCharged() {
        // Banker's rounding is right for summing many figures without bias and
        // wrong for one price a person pays, where the only defensible answer is
        // what they would get doing it by hand.
        assertEquals(new BigDecimal("3"), catalogue.round(new BigDecimal("2.5"), "XOF"));
        assertEquals(new BigDecimal("4"), catalogue.round(new BigDecimal("3.5"), "XOF"));
    }

    @Test
    void anUnknownCurrencyRoundsToTwoAsTheSaferGuess() {
        assertEquals(2, catalogue.minorUnits("ZZZ"));
        assertEquals(new BigDecimal("1250.50"), catalogue.round(new BigDecimal("1250.499"), "ZZZ"));
    }

    @Test
    void roundingNullIsNull() {
        org.junit.jupiter.api.Assertions.assertNull(catalogue.round(null, "GMD"));
    }

    // ── Lookup ───────────────────────────────────────────────────────────────

    @Test
    void codesAreCaseAndSpaceInsensitive() {
        assertTrue(catalogue.isSupported("gmd"));
        assertTrue(catalogue.isSupported("  GMD  "));
        assertEquals("GMD", catalogue.require("gmd"));
    }

    @Test
    void anUnsupportedCurrencyIsRefusedWithTheListOfWhatIsOnOffer() {
        BadRequestException refused =
                assertThrows(BadRequestException.class, () -> catalogue.require("JPY"));

        assertTrue(refused.getMessage().contains("JPY"));
        assertTrue(refused.getMessage().contains("GMD"),
                "a refusal that does not say what is supported makes the caller guess");
    }

    @Test
    void aMissingCurrencyIsRefusedToo() {
        assertThrows(BadRequestException.class, () -> catalogue.require(null));
        assertThrows(BadRequestException.class, () -> catalogue.require("  "));
        assertFalse(catalogue.isSupported(null));
    }

    // ── Startup ──────────────────────────────────────────────────────────────

    /**
     * Every currency fallback in the application resolves to the base, so a base
     * outside the catalogue means those fallbacks resolve to something nothing
     * can price. Better to refuse to start than to discover it at checkout.
     */
    @Test
    void aBaseCurrencyOutsideTheCatalogueStopsStartup() {
        ReferenceDataProperties broken = new ReferenceDataProperties();
        broken.setBaseCurrency("JPY");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> new CurrencyCatalogue(broken).index());

        assertTrue(refused.getMessage().contains("base-currency"));
    }

    @Test
    void theCatalogueKeepsItsConfiguredOrder() {
        assertEquals("GMD", catalogue.all().get(0).getCode(),
                "the home currency leads, because the order is the display order");
        assertEquals("GMD", catalogue.baseCurrency());
    }

    /** A currency can be settled in without being offered to buyers, and vice versa. */
    @Test
    void buyerFacingAndSettlementAreSeparateQuestions() {
        assertTrue(catalogue.find("NGN").orElseThrow().isBuyerFacing());
        assertFalse(catalogue.find("NGN").orElseThrow().isSettlement(),
                "naira is quoted to buyers but is not a currency vendors are paid out in");
        assertTrue(catalogue.find("XOF").orElseThrow().isSettlement());
    }

    @Test
    void aBlankCodeInConfigurationIsSkippedRatherThanIndexed() {
        ReferenceDataProperties sparse = new ReferenceDataProperties();
        sparse.getCurrencies().add(new ReferenceDataProperties.Currency(
                "  ", "Nothing", "?", 2, true, true));

        CurrencyCatalogue built = new CurrencyCatalogue(sparse);
        built.index();

        assertFalse(built.isSupported("  "));
        assertEquals(sparse.getCurrencies().size() - 1, built.all().size());
    }
}
