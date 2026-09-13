package com.sujula.service.fulfilment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sujula.dto.response.fulfilment.FulfilmentResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.model.constant.VendorOrderStatus;
import com.sujula.model.order.Order;
import com.sujula.model.order.OrderItem;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.products.Product;
import com.sujula.model.user.Vendor;
import com.sujula.service.security.SignedTokens;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What ends up printed on a box, and what must not.
 *
 * <p>A parcel sits on a bench where everyone who walks past can read it, so the
 * label is tested for its omissions as much as its contents.
 */
class ParcelLabelRendererTest {

    private ParcelLabelProperties properties;
    private ParcelLabelRenderer renderer;
    private Vendor vendor;
    private VendorOrder slice;

    @BeforeEach
    void setUp() {
        properties = new ParcelLabelProperties();
        properties.setSigningSecret("a-test-signing-secret-for-parcel-labels");
        properties.setBaseUrl("https://sujula.gm");
        renderer = new ParcelLabelRenderer(properties);

        vendor = Vendor.builder().id(50L).storeName("Kombo Electronics")
                .pickupCountryCode("GM").build();

        Order order = new Order();
        order.setId(7L);
        order.setOrderNumber("SJL-TEST0001");
        order.setCurrency("GBP");
        order.setTotal(new BigDecimal("142.50"));

        Product product = new Product();
        product.setId(32L);
        product.setName("Galaxy A16");

        OrderItem item = OrderItem.builder()
                .id(91L).order(order).product(product).vendor(vendor).quantity(2)
                .unitPrice(new BigDecimal("8500.00")).totalPrice(new BigDecimal("17000.00"))
                .productName("Galaxy A16").productSku("GA16")
                .build();

        slice = VendorOrder.builder()
                .id(11L).order(order).vendor(vendor).status(VendorOrderStatus.READY_FOR_PICKUP)
                .items(new ArrayList<>(List.of(item)))
                .build();
    }

    private static FulfilmentResponses.Shipping shipping() {
        return new FulfilmentResponses.Shipping("Isatou Ceesay", "Serrekunda", "GM",
                false, "HOME_DELIVERY", null, "••• 345");
    }

    /**
     * The text actually laid out on the page.
     *
     * <p>Extracted rather than grepped out of the raw bytes. OpenPDF compresses
     * its content streams, so a search of the file finds nothing whatever is on
     * the page — which would make every "this must not be printed" assertion
     * below pass for the wrong reason, and keep passing after somebody printed
     * the address.
     */
    private static String textOf(byte[] pdf) {
        try {
            com.lowagie.text.pdf.PdfReader reader = new com.lowagie.text.pdf.PdfReader(pdf);
            com.lowagie.text.pdf.parser.PdfTextExtractor extractor =
                    new com.lowagie.text.pdf.parser.PdfTextExtractor(reader);
            StringBuilder text = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                text.append(extractor.getTextFromPage(page)).append('\n');
            }
            reader.close();
            return text.toString();
        } catch (Exception unreadable) {
            throw new AssertionError("the label was not a readable PDF", unreadable);
        }
    }

    private String rendered() {
        return textOf(renderer.render(slice, vendor, shipping()).content());
    }

    @Test
    void theLabelIsAPdfNamedForTheParcel() {
        var label = renderer.render(slice, vendor, shipping());

        assertEquals("application/pdf", label.contentType());
        assertEquals("parcel-SJL-TEST0001-11.pdf", label.filename());
        assertTrue(label.content().length > 500, "a label with a QR on it is not a few bytes");
        assertEquals("%PDF", new String(label.content(), 0, 4, StandardCharsets.ISO_8859_1));
    }

    @Test
    void itCarriesWhoTheParcelIsForAndWhereItIsGoing() {
        String pdf = rendered();

        assertTrue(pdf.contains("Isatou Ceesay"), "the recipient's name goes on the box");
        assertTrue(pdf.contains("Serrekunda"), "and the town, so it can be sorted");
        assertTrue(pdf.contains("SJL-TEST0001"), "and the order number, to quote to support");
        assertTrue(pdf.contains("Kombo Electronics"), "and who it came from");
    }

    @Test
    void itCarriesNoPriceAndNoContents() {
        String pdf = rendered();

        // A label saying what is in the box and what it cost is a shopping list
        // for whoever is deciding which parcel to take.
        assertFalse(pdf.contains("Galaxy A16"), "the contents must not be printed");
        assertFalse(pdf.contains("8500"), "nor the vendor's price");
        assertFalse(pdf.contains("142.50"), "nor what the buyer paid");
        assertFalse(pdf.contains("GBP"), "nor the currency they paid in");

        // But a count, which is what a driver checks against what they were handed.
        assertTrue(pdf.contains("2 item(s)"));
    }

    @Test
    void aScannedTokenNamesTheParcelAndNothingElse() {
        String url = renderer.scanUrl(slice);

        assertTrue(url.startsWith("https://sujula.gm/parcels/"));
        String token = url.substring(url.lastIndexOf('/') + 1);
        assertEquals(11L, renderer.resolve(token));

        // Signed, not encrypted: the id is readable, which is fine because it is
        // not a secret. What matters is that it cannot be changed.
        assertFalse(url.contains("Isatou"), "no name travels in the token");
        assertFalse(url.contains("Serrekunda"), "and no address");
    }

    @Test
    void anEditedTokenNamesNothing() {
        String url = renderer.scanUrl(slice);
        String token = url.substring(url.lastIndexOf('/') + 1);

        // Flip the last character of the payload: a different parcel id, signed
        // with a signature that no longer matches it.
        String tampered = "MTI" + token.substring(3);
        assertThrows(BadRequestException.class, () -> renderer.resolve(tampered));
        assertThrows(BadRequestException.class, () -> renderer.resolve("nonsense"));
        assertThrows(BadRequestException.class, () -> renderer.resolve(""));
    }

    @Test
    void anInvoiceTokenDoesNotOpenAParcelAndTheReverse() {
        // The same secret, different purposes. Without the purpose mixed into
        // the signature these would be interchangeable, and the scanner would
        // happily accept an invoice token naming a different order.
        SignedTokens invoice = new SignedTokens(properties.getSigningSecret(), "invoice");
        String invoiceToken = invoice.mint("11", java.time.Instant.now().plusSeconds(600).getEpochSecond());

        assertThrows(BadRequestException.class, () -> renderer.resolve(invoiceToken));

        String url = renderer.scanUrl(slice);
        String labelToken = url.substring(url.lastIndexOf('/') + 1);
        assertThrows(BadRequestException.class, () -> invoice.verify(labelToken, "invoice link"));
    }

    @Test
    void anExpiredTokenIsRefused() {
        properties.setQrTtl(Duration.ofSeconds(-1));
        ParcelLabelRenderer stale = new ParcelLabelRenderer(properties);

        String url = stale.scanUrl(slice);
        String token = url.substring(url.lastIndexOf('/') + 1);
        assertThrows(BadRequestException.class, () -> stale.resolve(token));
    }

    @Test
    void aPickupPointParcelSaysSoOnTheBox() {
        var toAPickupPoint = new FulfilmentResponses.Shipping("Isatou Ceesay", "Serrekunda", "GM",
                false, "PICKUP_POINT", "Westfield Junction Kiosk", "••• 345");

        String pdf = textOf(renderer.render(slice, vendor, toAPickupPoint).content());
        assertTrue(pdf.contains("Westfield Junction Kiosk"));
    }

    @Test
    void aCrossBorderParcelIsFlaggedForCustoms() {
        var abroad = new FulfilmentResponses.Shipping("Isatou Ceesay", "Dakar", "SN",
                true, "HOME_DELIVERY", null, "••• 345");

        String pdf = textOf(renderer.render(slice, vendor, abroad).content());
        assertTrue(pdf.contains("INTERNATIONAL"), "the seller has paperwork to do");
    }

    @Test
    void aLabelStillPrintsWhenTheAddressIsMissing() {
        // Degrading to "scan the code" beats failing: a seller with a parcel and
        // no label cannot dispatch at all, and the QR still resolves.
        var label = renderer.render(slice, vendor, null);
        assertTrue(label.content().length > 500);
    }
}
