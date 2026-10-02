package com.sujula.repository.order;

import com.sujula.model.order.Cart;
import com.sujula.model.order.CartQuote;
import com.sujula.model.order.CartQuoteVendorSnapshot;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class CartQuoteVendorSnapshotPersistenceTest {

    @Autowired private CartRepository carts;
    @Autowired private CartQuoteRepository quotes;
    @Autowired private CartQuoteVendorSnapshotRepository snapshots;
    @Autowired private EntityManager entityManager;

    @Test
    void quotePersistsOneExactDiscountSnapshotPerVendorAndCouponAuditIdentity() {
        CartQuote quote = newQuote(new BigDecimal("20.00"));
        quote.setPlatformCouponId(9001L);
        quote.setPlatformCouponCode("PLATFORM20");

        quote.getVendorSnapshots().add(snapshot(
                quote, 101L, "13.37", "888.42", "3.37", 7101L, "VENDOR-A"));
        quote.getVendorSnapshots().add(snapshot(
                quote, 202L, "6.63", "6.63", "6.63", null, null));

        quotes.saveAndFlush(quote);
        entityManager.clear();

        CartQuote reloadedQuote = quotes.findById(quote.getId()).orElseThrow();
        List<CartQuoteVendorSnapshot> reloaded =
                snapshots.findAllByQuoteIdOrderByVendorId(quote.getId());

        assertEquals(9001L, reloadedQuote.getPlatformCouponId());
        assertEquals("PLATFORM20", reloadedQuote.getPlatformCouponCode());
        assertEquals(2, reloaded.size());

        CartQuoteVendorSnapshot vendorA = reloaded.get(0);
        assertEquals(101L, vendorA.getVendorId());
        assertEquals(new BigDecimal("13.37"), vendorA.getDiscountDisplay());
        assertEquals(new BigDecimal("888.42"), vendorA.getDiscountNative());
        assertEquals(new BigDecimal("3.37"), vendorA.getPlatformDiscountShareDisplay());
        assertEquals(7101L, vendorA.getVendorCouponId());
        assertEquals("VENDOR-A", vendorA.getVendorCouponCode());

        CartQuoteVendorSnapshot vendorB = reloaded.get(1);
        assertEquals(202L, vendorB.getVendorId());
        assertEquals(new BigDecimal("6.63"), vendorB.getDiscountDisplay());
        assertEquals(new BigDecimal("6.63"), vendorB.getDiscountNative());
        assertEquals(new BigDecimal("6.63"), vendorB.getPlatformDiscountShareDisplay());
        assertTrue(vendorB.getVendorCouponId() == null);
        assertTrue(vendorB.getVendorCouponCode() == null);

        BigDecimal allocatedDiscount = reloaded.stream()
                .map(CartQuoteVendorSnapshot::getDiscountDisplay)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(reloadedQuote.getDiscount(), allocatedDiscount);
    }

    @Test
    void databaseRejectsASecondSnapshotForTheSameQuoteAndVendor() {
        CartQuote quote = quotes.saveAndFlush(newQuote(BigDecimal.ZERO));
        snapshots.saveAndFlush(snapshot(
                quote, 101L, "0.00", "0.00", "0.00", null, null));

        assertThrows(DataIntegrityViolationException.class, () -> snapshots.saveAndFlush(snapshot(
                quote, 101L, "1.00", "1.00", "0.00", null, null)));
    }

    private CartQuote newQuote(BigDecimal discount) {
        Cart cart = carts.saveAndFlush(Cart.builder()
                .sessionId(UUID.randomUUID().toString())
                .token("cart-" + UUID.randomUUID())
                .displayCurrency("EUR")
                .build());
        LocalDateTime now = LocalDateTime.now();
        return CartQuote.builder()
                .id("quote-" + UUID.randomUUID())
                .cart(cart)
                .cartFingerprint("frozen-cart-fingerprint")
                .displayCurrency("EUR")
                .subtotal(new BigDecimal("100.00"))
                .discount(discount)
                .shipping(BigDecimal.ZERO)
                .tax(BigDecimal.ZERO)
                .total(new BigDecimal("80.00"))
                .complete(true)
                .createdAt(now)
                .expiresAt(now.plusMinutes(15))
                .build();
    }

    private static CartQuoteVendorSnapshot snapshot(
            CartQuote quote,
            long vendorId,
            String discountDisplay,
            String discountNative,
            String platformDiscountShareDisplay,
            Long vendorCouponId,
            String vendorCouponCode) {
        return CartQuoteVendorSnapshot.builder()
                .quote(quote)
                .vendorId(vendorId)
                .discountDisplay(new BigDecimal(discountDisplay))
                .discountNative(new BigDecimal(discountNative))
                .platformDiscountShareDisplay(new BigDecimal(platformDiscountShareDisplay))
                .vendorCouponId(vendorCouponId)
                .vendorCouponCode(vendorCouponCode)
                .build();
    }
}
