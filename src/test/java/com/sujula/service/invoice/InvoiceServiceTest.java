package com.sujula.service.invoice;

import com.sujula.dto.response.buyerorder.BuyerOrderResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.model.constant.DeliveryMode;
import com.sujula.model.constant.OrderStatus;
import com.sujula.model.constant.PaymentMethod;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.money.FxSnapshot;
import com.sujula.model.order.Order;
import com.sujula.model.order.OrderItem;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.order.OrderRepository;
import com.sujula.service.invoice.impl.InvoiceServiceImpl;
import com.sujula.service.reference.CurrencyCatalogue;
import com.sujula.service.reference.ReferenceDataProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The invoice, and the link that carries it.
 *
 * <p>The link is a bearer credential: whoever holds it gets the document, and
 * the document has an address and a phone number on it. So the tests that matter
 * are the ones about what the token refuses — an edited order id, a re-signed
 * payload, an expiry that has passed — rather than about the layout.
 */
class InvoiceServiceTest {

    private static final Long ORDER = 5500L;

    private OrderRepository orders;
    private InvoiceProperties properties;
    private InvoiceServiceImpl invoices;

    @BeforeEach
    void setUp() {
        orders = mock(OrderRepository.class);
        properties = new InvoiceProperties();
        properties.setSigningSecret("a-fixed-secret-for-this-test");
        properties.setBaseUrl("https://sujula.example");
        properties.setIssuerName("Sujula");

        invoices = new InvoiceServiceImpl(orders,
                CurrencyCatalogue.of(new ReferenceDataProperties()), properties);

        when(orders.findById(ORDER)).thenReturn(Optional.of(order()));
    }

    /** Madrid pays in EUR; Banjul is owed GMD; Dakar is owed XOF, which has no minor unit. */
    private static Order order() {
        Order order = new Order();
        order.setId(ORDER);
        order.setOrderNumber("SJL-INV0001");
        order.setStatus(OrderStatus.PROCESSING);
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setPaymentMethod(PaymentMethod.CARD);
        order.setPaidAt(LocalDateTime.of(2026, 3, 1, 12, 0));
        order.setCreatedAt(LocalDateTime.of(2026, 3, 1, 11, 55));
        order.setCurrency("EUR");
        order.setSubtotal(new BigDecimal("212.00"));
        order.setShippingCost(new BigDecimal("18.00"));
        order.setTotal(new BigDecimal("230.00"));
        order.setDeliveryMode(DeliveryMode.HOME_DELIVERY);

        User buyer = new User();
        buyer.setId(700L);
        buyer.setFirstName("Fatou");
        buyer.setLastName("Ceesay");
        buyer.setEmail("fatou@example.es");
        order.setCustomer(buyer);
        order.setBillingCity("Madrid");
        order.setBillingCountry("ES");

        order.setShippingFullName("Awa Ceesay");
        order.setShippingPhone("+2203001122");
        order.setShippingCity("Serrekunda");
        order.setShippingCountry("GM");

        order.setVendorOrders(new ArrayList<>(List.of(
                slice(1L, "Banjul Electronics", "GMD", new BigDecimal("120.00"), new BigDecimal("8400.00")),
                slice(2L, "Dakar Mobile", "XOF", new BigDecimal("92.00"), new BigDecimal("60000")))));
        order.setItems(new ArrayList<>());
        order.getVendorOrders().forEach(slice -> order.getItems().addAll(slice.getItems()));
        return order;
    }

    private static VendorOrder slice(Long id, String storeName, String nativeCurrency,
                                     BigDecimal total, BigDecimal totalNative) {
        Vendor vendor = new Vendor();
        vendor.setId(id);
        vendor.setStoreName(storeName);

        VendorOrder vendorOrder = new VendorOrder();
        vendorOrder.setId(id);
        vendorOrder.setVendor(vendor);
        vendorOrder.setNativeCurrency(nativeCurrency);
        vendorOrder.setSubtotal(total);
        vendorOrder.setTotal(total);
        vendorOrder.setTotalNative(totalNative);
        vendorOrder.setPayoutNative(totalNative);
        vendorOrder.setFx(FxSnapshot.published(nativeCurrency, "EUR",
                total.divide(totalNative, 8, RoundingMode.HALF_UP),
                LocalDateTime.of(2026, 3, 1, 9, 0)));

        OrderItem item = new OrderItem();
        item.setId(id * 10);
        item.setVendorOrder(vendorOrder);
        item.setQuantity(1);
        item.setProductName("Phone");
        item.setUnitPriceConverted(total);
        item.setTotalPriceConverted(total);
        item.setDeliveryCost(BigDecimal.ZERO);
        vendorOrder.setItems(new ArrayList<>(List.of(item)));
        return vendorOrder;
    }

    // ── The link ─────────────────────────────────────────────────────────────

    @Test
    void theLinkIsAbsoluteAndExpiresSoon() {
        BuyerOrderResponses.DocumentLink link = invoices.link(order());

        assertTrue(link.url().startsWith("https://sujula.example/invoices/"), link.url());
        assertEquals("application/pdf", link.contentType());
        assertTrue(link.expiresAt().isAfter(LocalDateTime.now(java.time.ZoneOffset.UTC)));
        assertTrue(link.expiresAt().isBefore(
                LocalDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(16)));
    }

    @Test
    void aValidTokenRendersTheOrderItNames() {
        InvoiceService.RenderedInvoice document = invoices.render(token(invoices.link(order())));

        assertEquals("sujula-invoice-SJL-INV0001.pdf", document.filename());
        assertEquals("application/pdf", document.contentType());
        assertTrue(document.content().length > 800);
        // A real PDF, not an empty stream with a hopeful content type.
        assertEquals("%PDF", new String(document.content(), 0, 4, StandardCharsets.US_ASCII));
    }

    @Test
    void editingTheOrderIdInTheTokenIsRefused() {
        String original = token(invoices.link(order()));
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
                        ("9999." + (System.currentTimeMillis() / 1000 + 600))
                                .getBytes(StandardCharsets.UTF_8))
                + "." + original.split("\\.")[1];

        // The signature covers the whole payload, so the id cannot be swapped
        // while keeping a signature that was issued for another order.
        assertThrows(BadRequestException.class, () -> invoices.render(forged));
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRefused() {
        InvoiceProperties other = new InvoiceProperties();
        other.setSigningSecret("a-different-secret");
        InvoiceServiceImpl elsewhere = new InvoiceServiceImpl(orders,
                CurrencyCatalogue.of(new ReferenceDataProperties()), other);

        String foreign = token(elsewhere.link(order()));

        assertThrows(BadRequestException.class, () -> invoices.render(foreign));
    }

    @Test
    void anExpiredLinkIsRefusedAndSaysSoWithoutSayingWhy() {
        properties.setLinkTtl(Duration.ofMinutes(-1));
        String stale = token(invoices.link(order()));

        BadRequestException refused =
                assertThrows(BadRequestException.class, () -> invoices.render(stale));

        assertTrue(refused.getMessage().contains("expired"), refused.getMessage());
        // Nothing about the order: a probe learns the link is dead, not whose it was.
        assertFalse(refused.getMessage().contains("SJL-INV0001"), refused.getMessage());
    }

    @Test
    void rubbishIsRefusedRatherThanThrowingSomethingElse() {
        assertThrows(BadRequestException.class, () -> invoices.render("not-a-token"));
        assertThrows(BadRequestException.class, () -> invoices.render("aaaa.bbbb"));
        assertThrows(BadRequestException.class, () -> invoices.render(""));
    }

    @Test
    void twoLinksForOneOrderAreNotTheSameString() {
        // The expiry is inside the signed payload, so a link minted a second
        // later signs differently. Nothing here is a stable, guessable URL.
        String first = token(invoices.link(order()));
        properties.setLinkTtl(Duration.ofMinutes(20));
        String second = token(invoices.link(order()));

        assertNotEquals(first, second);
    }

    @Test
    void anUnsetSecretStillProducesWorkingLinksForThisProcess() {
        InvoiceProperties unset = new InvoiceProperties();
        InvoiceServiceImpl local = new InvoiceServiceImpl(orders,
                CurrencyCatalogue.of(new ReferenceDataProperties()), unset);
        when(orders.findById(anyLong())).thenReturn(Optional.of(order()));

        // Degrading to a generated key means dev works without configuration;
        // the warning at startup is what stops it reaching a server unnoticed.
        assertEquals("%PDF", new String(local.render(token(local.link(order()))).content(),
                0, 4, StandardCharsets.US_ASCII));
    }

    // ── What the document says ───────────────────────────────────────────────

    /**
     * The invoice has to survive being read by somebody disputing it a year
     * later, so the three things that make this marketplace unusual have to be
     * on the page rather than implied by it.
     */
    @Test
    void theDocumentStatesBothPartiesEachSellerAndEveryRate() throws Exception {
        String text = extract(invoices.render(token(invoices.link(order()))).content());

        // C1 — two locations, two blocks. The payer is in Madrid; the goods went
        // to Serrekunda. One "customer address" would misstate who did what.
        assertTrue(text.contains("BILLED TO"), text);
        assertTrue(text.contains("Fatou Ceesay"), text);
        assertTrue(text.contains("Madrid"), text);
        assertTrue(text.contains("DELIVERED TO"), text);
        assertTrue(text.contains("Awa Ceesay"), text);
        assertTrue(text.contains("Serrekunda"), text);

        // C3 — a section per seller, each totalling on its own, so a later
        // cancellation of one reads against this document instead of
        // contradicting it.
        assertTrue(text.contains("Banjul Electronics"), text);
        assertTrue(text.contains("Dakar Mobile"), text);
        assertEquals(2, text.split("Vendor total", -1).length - 1, text);

        // C2 — each payout in the seller's own currency, with the rate and the
        // moment it was taken. XOF has no minor unit, so 60000 and not 60000.00.
        assertTrue(text.contains("GMD 8400.00"), text);
        assertTrue(text.contains("XOF 60000"), text);
        assertFalse(text.contains("XOF 60000.00"), text);
        assertTrue(text.contains("taken 1 Mar 2026 09:00 UTC"), text);

        // And the buyer's own total, in the currency they were actually charged.
        assertTrue(text.contains("Total charged"), text);
        assertTrue(text.contains("EUR 230.00"), text);
    }

    private static String extract(byte[] pdf) throws Exception {
        return new com.lowagie.text.pdf.parser.PdfTextExtractor(
                new com.lowagie.text.pdf.PdfReader(pdf)).getTextFromPage(1);
    }

    private static String token(BuyerOrderResponses.DocumentLink link) {
        return link.url().substring(link.url().lastIndexOf('/') + 1);
    }
}
