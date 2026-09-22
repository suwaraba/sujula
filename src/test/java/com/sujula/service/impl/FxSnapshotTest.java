package com.sujula.service.impl;

import com.sujula.model.constant.FxSource;
import com.sujula.model.money.FxSnapshot;
import com.sujula.model.order.OrderItem;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.user.Vendor;
import com.sujula.service.cart.RateTable;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C2: a converted figure has to carry the rate it was converted at.
 *
 * <p>The rate table moves daily. Without the snapshot, a payout questioned next
 * month cannot be re-derived from anything still on the system — the amounts are
 * right and nobody can say why.
 */
class FxSnapshotTest {

    private static final LocalDateTime TAKEN_AT = LocalDateTime.of(2026, 9, 12, 9, 0);

    private static Vendor vendor() {
        return Vendor.builder()
                .id(50L)
                .storeName("Kombo Electronics")
                .settlementCurrency("GMD")
                .defaultCommissionRate(new BigDecimal("10.00"))
                .build();
    }

    private static OrderItem line() {
        return OrderItem.builder()
                .quantity(1)
                .unitPrice(new BigDecimal("900.00"))
                .totalPrice(new BigDecimal("900.00"))
                .currency("GMD")
                .deliveryCost(BigDecimal.ZERO)
                .build();
    }

    /** 800 dalasi of goods sold to a buyer shopping in pounds. */
    private static VendorOrder slice(String nativeCurrency) {
        return VendorOrder.builder()
                .vendor(vendor())
                .nativeCurrency(nativeCurrency)
                .subtotalNative(new BigDecimal("800.00"))
                .totalNative(new BigDecimal("800.00"))
                .subtotal(new BigDecimal("10.00"))
                .total(new BigDecimal("10.00"))
                .items(List.of(line()))
                .build();
    }

    private static RateTable table(String target, Map<String, BigDecimal> rates, int scale) {
        return new RateTable(target, rates, scale, TAKEN_AT);
    }

    // ── The rule ─────────────────────────────────────────────────────────────

    @Test
    void theSliceRecordsTheRateItsFiguresWereConvertedAt() {
        VendorOrder slice = slice("GMD");

        OrderServiceImpl.freezeVendorSettlement(List.of(slice),
                table("GBP", Map.of("GMD", new BigDecimal("0.0125")), 2));

        FxSnapshot fx = slice.getFx();
        assertNotNull(fx, "a payout nobody can explain is the thing this prevents");
        assertEquals("GMD", fx.getNativeCurrency());
        assertEquals("GBP", fx.getDisplayCurrency());
        assertEquals(0, new BigDecimal("0.0125").compareTo(fx.getRate()));
        assertEquals(TAKEN_AT, fx.getRateAt(), "when the rate was published, not when we wrote it");
        assertEquals(FxSource.PUBLISHED_RATE, fx.getSource());
    }

    /**
     * The stored rate must reproduce the stored amounts. If it does not, the
     * snapshot is decorative rather than evidence.
     */
    @Test
    void theStoredRateReproducesTheStoredAmounts() {
        VendorOrder slice = slice("GMD");

        OrderServiceImpl.freezeVendorSettlement(List.of(slice),
                table("GBP", Map.of("GMD", new BigDecimal("0.0125")), 2));

        BigDecimal rederived = slice.getTotalNative()
                .multiply(slice.getFx().getRate())
                .setScale(2, java.math.RoundingMode.HALF_UP);

        assertEquals(0, slice.getTotal().compareTo(rederived),
                "native × rate must give back what the buyer was charged");
    }

    /**
     * "No conversion happened" is a fact worth writing down. Left absent it is
     * indistinguishable from nobody having recorded anything.
     */
    @Test
    void aSameCurrencySaleRecordsIdentityRatherThanNothing() {
        VendorOrder slice = slice("GMD");

        OrderServiceImpl.freezeVendorSettlement(List.of(slice), table("GMD", Map.of(), 2));

        FxSnapshot fx = slice.getFx();
        assertNotNull(fx);
        assertTrue(fx.isIdentity());
        assertEquals(FxSource.IDENTITY, fx.getSource());
        assertEquals(0, BigDecimal.ONE.compareTo(fx.getRate()));
    }

    /**
     * A slice spanning two listing currencies has no single rate. Recording one
     * would be a fiction that looked like evidence.
     */
    @Test
    void aSliceWithNoSingleCurrencyRecordsNoRate() {
        VendorOrder slice = slice(null);

        OrderServiceImpl.freezeVendorSettlement(List.of(slice),
                table("GBP", Map.of("GMD", new BigDecimal("0.0125")), 2));

        assertNull(slice.getFx());
    }

    @Test
    void anUnpricablePairRecordsNoRate() {
        VendorOrder slice = slice("XOF");

        OrderServiceImpl.freezeVendorSettlement(List.of(slice),
                table("GBP", Map.of("GMD", new BigDecimal("0.0125")), 2));

        assertNull(slice.getFx(), "no rate was available, so none was used");
    }

    /** The older call path records nothing rather than inventing a rate. */
    @Test
    void withoutARateTableNothingIsInvented() {
        VendorOrder slice = slice("GMD");

        OrderServiceImpl.freezeVendorSettlement(List.of(slice));

        assertNull(slice.getFx());
        assertNotNull(slice.getPayoutNative(), "the settlement figures are still frozen");
    }

    // ── The snapshot type itself ─────────────────────────────────────────────

    @Test
    void anIdentitySnapshotIsRecognisedFromItsCurrenciesToo() {
        FxSnapshot fx = FxSnapshot.published("GMD", "gmd", BigDecimal.ONE, TAKEN_AT);

        assertTrue(fx.isIdentity(), "same currency either side, whatever the source says");
    }

    @Test
    void aHeldQuoteKeepsItsProvenance() {
        FxSnapshot fx = FxSnapshot.held("GMD", "GBP", new BigDecimal("0.0125"), TAKEN_AT, "q-123");

        assertEquals(FxSource.HELD_QUOTE, fx.getSource());
        assertEquals("q-123", fx.getQuoteId(),
                "the quote row is what proves the buyer was shown this rate");
        assertFalse(fx.isIdentity());
    }

    @Test
    void anUnrecordedSnapshotSaysSo() {
        assertFalse(new FxSnapshot().isRecorded());
        assertTrue(FxSnapshot.identity("GMD", TAKEN_AT).isRecorded());
    }

    // ── Rounding at the conversion boundary ──────────────────────────────────

    /**
     * XOF has no minor unit. A conversion landing on 1250.50 CFA produces a total
     * nobody can tender.
     */
    @Test
    void convertingIntoCfaLandsOnAWholeFranc() {
        RateTable cfa = table("XOF", Map.of("GMD", new BigDecimal("8.53")), 0);

        BigDecimal converted = cfa.convert(new BigDecimal("100"), "GMD");

        assertEquals(0, new BigDecimal("853").compareTo(converted));
        assertEquals(0, converted.scale());
    }

    @Test
    void convertingIntoSterlingKeepsItsPence() {
        RateTable gbp = table("GBP", Map.of("GMD", new BigDecimal("0.0125")), 2);

        assertEquals(new BigDecimal("10.00"), gbp.convert(new BigDecimal("800"), "GMD"));
    }

    @Test
    void aMissingRateConvertsToNullNeverToZero() {
        RateTable gbp = table("GBP", Map.of(), 2);

        assertNull(gbp.convert(new BigDecimal("800"), "GMD"),
                "a missing rate read as zero would give the goods away");
    }

    @Test
    void theTableRemembersWhenItWasRead() {
        assertEquals(TAKEN_AT, table("GBP", Map.of(), 2).takenAt());
        assertNull(new RateTable("GBP", Map.of()).takenAt(),
                "the older two-argument shape has nothing to freeze");
    }
}
