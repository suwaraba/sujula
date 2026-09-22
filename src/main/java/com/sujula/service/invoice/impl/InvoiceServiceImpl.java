package com.sujula.service.invoice.impl;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;


import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.sujula.dto.response.buyerorder.BuyerOrderResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.money.FxSnapshot;
import com.sujula.model.order.Order;
import com.sujula.model.order.OrderItem;
import com.sujula.model.order.VendorOrder;
import com.sujula.repository.order.OrderRepository;
import com.sujula.service.invoice.InvoiceProperties;
import com.sujula.service.invoice.InvoiceService;
import com.sujula.service.reference.CurrencyCatalogue;
import com.sujula.service.security.SignedTokens;

import lombok.extern.slf4j.Slf4j;

/**
 * Renders the buyer's invoice and mints the signed link that serves it.
 *
 * <p>The document is laid out around the three rules that make this marketplace
 * what it is, because an invoice that hides them is an invoice somebody will
 * later dispute:
 *
 * <ul>
 *   <li><b>C1</b> — the payer's details and the delivery details sit in two
 *       separate blocks, side by side. They are frequently two different people
 *       in two different countries, and collapsing them into one "customer
 *       address" would misstate who bought and who received.</li>
 *   <li><b>C2</b> — every vendor section states the rate its figures were
 *       converted at and the moment that rate was taken. The buyer's total is in
 *       their currency; the vendor's is in the vendor's; the line between them
 *       is a number anybody can check.</li>
 *   <li><b>C3</b> — one section per vendor, each totalling independently, so a
 *       later cancellation or refund of one section is legible against this
 *       document rather than contradicting it.</li>
 * </ul>
 */
@Slf4j
@Service
public class InvoiceServiceImpl implements InvoiceService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm 'UTC'");
    private static final Font H1 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18f);
    private static final Font H2 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11f);
    private static final Font LABEL = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8f);
    private static final Font BODY = FontFactory.getFont(FontFactory.HELVETICA, 9f);
    private static final Font BODY_BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9f);
    private static final Font SMALL = FontFactory.getFont(FontFactory.HELVETICA, 7.5f);

    private final OrderRepository orders;
    private final CurrencyCatalogue currencies;
    private final InvoiceProperties properties;

    /**
     * The signer.
     *
     * <p>Shared with the parcel label rather than a second private copy of the
     * same HMAC. Two copies drift, and the one that rots is always the one
     * nobody re-read. The purpose string keeps them apart: an invoice token
     * presented to the label endpoint does not verify, and the reverse.
     */
    private final SignedTokens tokens;

    public InvoiceServiceImpl(OrderRepository orders,
                              CurrencyCatalogue currencies,
                              InvoiceProperties properties) {
        this.orders = orders;
        this.currencies = currencies;
        this.properties = properties;
        this.tokens = new SignedTokens(properties.getSigningSecret(), "invoice");
    }

    // ── Links ──────────────────────────────────────────────────────────────

    @Override
    public BuyerOrderResponses.DocumentLink link(Order order) {
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plus(properties.getLinkTtl());
        String token = tokens.mint(String.valueOf(order.getId()),
                expiresAt.toEpochSecond(ZoneOffset.UTC));

        String base = properties.getBaseUrl() == null ? "" : properties.getBaseUrl().trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return new BuyerOrderResponses.DocumentLink(
                base + properties.getDownloadPath() + "/" + token, expiresAt, "application/pdf");
    }

    /** The order a valid token names. */
    private Long verify(String token) {
        String subject = tokens.verify(token, "invoice link");
        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException malformed) {
            throw new BadRequestException("This invoice link is not valid.");
        }
    }


    // ── Rendering ────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public InvoiceService.RenderedInvoice render(String token) {
        Long orderId = verify(token);
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));

        byte[] pdf = build(order);
        String name = "sujula-invoice-"
                + (order.getOrderNumber() == null ? String.valueOf(order.getId()) : order.getOrderNumber())
                + ".pdf";
        return new InvoiceService.RenderedInvoice(pdf, name, "application/pdf");
    }

    private byte[] build(Order order) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 42f, 42f, 42f, 48f);
        try {
            PdfWriter.getInstance(document, out);
            document.addTitle("Invoice " + order.getOrderNumber());
            document.open();

            heading(document, order);
            parties(document, order);
            vendorSections(document, order);
            summary(document, order);
            footer(document);

            document.close();
        } catch (RuntimeException failed) {
            throw new IllegalStateException(
                    "Could not render the invoice for order " + order.getId(), failed);
        }
        return out.toByteArray();
    }

    private void heading(Document document, Order order) {
        PdfPTable head = table(new float[] {6, 4});

        PdfPCell issuer = bare();
        issuer.addElement(new Paragraph(properties.getIssuerName(), H1));
        if (notBlank(properties.getIssuerAddress())) {
            issuer.addElement(new Paragraph(properties.getIssuerAddress(), SMALL));
        }
        if (notBlank(properties.getIssuerTaxId())) {
            issuer.addElement(new Paragraph("Tax ID " + properties.getIssuerTaxId(), SMALL));
        }
        head.addCell(issuer);

        PdfPCell meta = bare();
        meta.setHorizontalAlignment(Element.ALIGN_RIGHT);
        Paragraph title = new Paragraph("INVOICE", H2);
        title.setAlignment(Element.ALIGN_RIGHT);
        meta.addElement(title);
        meta.addElement(right(order.getOrderNumber(), BODY_BOLD));
        meta.addElement(right(order.getCreatedAt() == null ? "" : DATE.format(order.getCreatedAt()), BODY));
        meta.addElement(right(order.getPaymentStatus() == null ? ""
                : "Payment " + order.getPaymentStatus().name().toLowerCase(), SMALL));
        head.addCell(meta);

        document.add(head);
        document.add(spacer(10f));
    }

    /**
     * The two locations, side by side and never merged (C1).
     *
     * <p>The left column is who paid; the right is where the goods went. A buyer
     * in Madrid sending a phone to their sister in Serrekunda sees exactly that,
     * rather than one address standing in for both.
     */
    private void parties(Document document, Order order) {
        PdfPTable parties = table(new float[] {1, 1});

        PdfPCell billed = boxed();
        billed.addElement(new Paragraph("BILLED TO", LABEL));
        for (String line : payerLines(order)) {
            billed.addElement(new Paragraph(line, BODY));
        }
        parties.addCell(billed);

        PdfPCell shipped = boxed();
        shipped.addElement(new Paragraph("DELIVERED TO", LABEL));
        for (String line : deliveryLines(order)) {
            shipped.addElement(new Paragraph(line, BODY));
        }
        parties.addCell(shipped);

        document.add(parties);
        document.add(spacer(12f));
    }

    private List<String> payerLines(Order order) {
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        if (order.getCustomer() != null) {
            String name = join(order.getCustomer().getFirstName(), order.getCustomer().getLastName());
            add(lines, name);
            add(lines, order.getCustomer().getEmail());
            add(lines, order.getCustomer().getPhone());
        } else {
            add(lines, order.getGuestName());
            add(lines, order.getGuestEmail());
            add(lines, order.getGuestPhone());
        }
        add(lines, order.getBillingStreet());
        add(lines, join(order.getBillingCity(), order.getBillingPostalCode()));
        add(lines, order.getBillingCountry());
        if (lines.isEmpty()) {
            lines.add("—");
        }
        return lines;
    }

    private List<String> deliveryLines(Order order) {
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        add(lines, order.getShippingFullName());
        add(lines, order.getShippingPhone());
        add(lines, order.getShippingStreet());
        add(lines, order.getShippingApartment());
        add(lines, join(order.getShippingCity(), order.getShippingPostalCode()));
        add(lines, order.getShippingState());
        add(lines, order.getShippingCountry());
        if (order.getDeliveryMode() != null) {
            lines.add(order.getDeliveryMode().name().replace('_', ' ').toLowerCase());
        }
        if (lines.isEmpty()) {
            lines.add("—");
        }
        return lines;
    }

    /**
     * One block per vendor (C3), each with its own lines, its own totals and the
     * rate its conversion used (C2).
     */
    private void vendorSections(Document document, Order order) {
        List<VendorOrder> slices = order.getVendorOrders();
        String display = order.getCurrency();

        if (slices == null || slices.isEmpty()) {
            document.add(linesTable(order.getItems(), display));
            return;
        }

        for (VendorOrder slice : slices) {
            String vendor = slice.getVendor() == null ? "Vendor" : slice.getVendor().getStoreName();
            Paragraph header = new Paragraph(vendor, H2);
            header.setSpacingBefore(6f);
            header.setSpacingAfter(3f);
            document.add(header);

            if (slice.getCancelledAt() != null) {
                document.add(new Paragraph(
                        "Cancelled " + DATE.format(slice.getCancelledAt())
                        + " — refunded separately from the rest of this order.", SMALL));
            }

            document.add(linesTable(slice.getItems(), display));
            document.add(sliceTotals(slice, display));

            FxSnapshot fx = slice.getFx();
            if (fx != null && fx.isRecorded() && !fx.isIdentity()) {
                document.add(new Paragraph(
                        "Paid out to the seller as " + money(slice.getPayoutNative(), fx.getNativeCurrency())
                        + ", converted at 1 " + fx.getNativeCurrency() + " = "
                        + fx.getRate().stripTrailingZeros().toPlainString() + " " + fx.getDisplayCurrency()
                        + (fx.getRateAt() == null ? "" : ", taken " + STAMP.format(fx.getRateAt()))
                        + ". That rate was fixed when the order was placed and is not recalculated.",
                        SMALL));
            }
            document.add(spacer(8f));
        }
    }

    private PdfPTable linesTable(List<OrderItem> items, String display) {
        PdfPTable lines = table(new float[] {8, 2, 3, 3});
        lines.addCell(headerCell("Item"));
        lines.addCell(headerCell("Qty"));
        lines.addCell(headerCell("Unit"));
        lines.addCell(headerCell("Amount"));

        if (items != null) {
            for (OrderItem item : items) {
                lines.addCell(itemCell(item));
                lines.addCell(cell(String.valueOf(item.getQuantity() == null ? 0 : item.getQuantity()),
                        BODY, Element.ALIGN_RIGHT));
                lines.addCell(cell(money(charged(item.getUnitPriceConverted(), item.getUnitPrice()), display),
                        BODY, Element.ALIGN_RIGHT));
                lines.addCell(cell(money(charged(item.getTotalPriceConverted(), item.getTotalPrice()), display),
                        BODY, Element.ALIGN_RIGHT));
            }
        }
        return lines;
    }

    /**
     * The description cell: the product's name, with the variant underneath when
     * there is one.
     *
     * <p>Built in one mode or the other rather than switched between them — a
     * cell given a phrase and then given elements renders whichever OpenPDF
     * happens to prefer, which is a bug that only shows up on the orders that
     * have variants.
     */
    private static PdfPCell itemCell(OrderItem item) {
        String name = item.getProductName() == null ? "" : item.getProductName();
        if (!notBlank(item.getSelectedOptions())) {
            return cell(name, BODY, Element.ALIGN_LEFT);
        }
        PdfPCell composite = cell(null, BODY, Element.ALIGN_LEFT);
        composite.setPhrase(null);
        composite.addElement(new Paragraph(name, BODY));
        composite.addElement(new Paragraph(item.getSelectedOptions(), SMALL));
        return composite;
    }

    private PdfPTable sliceTotals(VendorOrder slice, String display) {
        PdfPTable totals = table(new float[] {12, 4});
        totalRow(totals, "Subtotal", money(slice.getSubtotal(), display), false);

        BigDecimal delivery = slice.getItems() == null ? BigDecimal.ZERO
                : slice.getItems().stream()
                        .map(OrderItem::getDeliveryCost)
                        .filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (delivery.signum() != 0) {
            totalRow(totals, "Delivery", money(delivery, display), false);
        }
        if (slice.getDiscount() != null && slice.getDiscount().signum() != 0) {
            totalRow(totals, "Discount", "-" + money(slice.getDiscount(), display), false);
        }
        totalRow(totals, "Vendor total", money(slice.getTotal(), display), true);
        return totals;
    }

    private void summary(Document document, Order order) {
        String display = order.getCurrency();
        PdfPTable totals = table(new float[] {12, 4});
        totalRow(totals, "Order subtotal", money(order.getSubtotal(), display), false);
        if (order.getShippingCost() != null && order.getShippingCost().signum() != 0) {
            totalRow(totals, "Delivery", money(order.getShippingCost(), display), false);
        }
        if (order.getTaxAmount() != null && order.getTaxAmount().signum() != 0) {
            totalRow(totals, "Tax", money(order.getTaxAmount(), display), false);
        }
        if (order.getDiscount() != null && order.getDiscount().signum() != 0) {
            totalRow(totals, "Discount"
                    + (notBlank(order.getCouponCode()) ? " (" + order.getCouponCode() + ")" : ""),
                    "-" + money(order.getDiscount(), display), false);
        }
        totalRow(totals, "Total charged", money(order.getTotal(), display), true);
        document.add(totals);

        if (order.getPaidAt() != null) {
            document.add(new Paragraph("Paid " + STAMP.format(order.getPaidAt())
                    + (order.getPaymentMethod() == null ? ""
                       : " by " + order.getPaymentMethod().name().replace('_', ' ').toLowerCase()) + ".",
                    SMALL));
        }
    }

    private void footer(Document document) {
        document.add(spacer(14f));
        Paragraph note = new Paragraph(
                notBlank(properties.getFooterNote()) ? properties.getFooterNote()
                        : "Funds reach each seller once delivery of their goods is proven.",
                SMALL);
        note.setAlignment(Element.ALIGN_CENTER);
        document.add(note);
    }

    // ── Small helpers ────────────────────────────────────────────────────────

    /** What the buyer was charged, falling back to the listing figure when no conversion happened. */
    private static BigDecimal charged(BigDecimal converted, BigDecimal listed) {
        return converted != null ? converted : listed;
    }

    /** Rounds to the currency's real scale — XOF has no minor unit, so no ".00". */
    private String money(BigDecimal amount, String currencyCode) {
        if (amount == null || currencyCode == null) {
            return "—";
        }
        return currencyCode + " " + currencies.round(amount, currencyCode).toPlainString();
    }

    private static PdfPTable table(float[] widths) {
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100f);
        table.setSpacingBefore(2f);
        return table;
    }

    private static PdfPCell bare() {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0f);
        return cell;
    }

    private static PdfPCell boxed() {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.BOX);
        cell.setBorderWidth(0.4f);
        cell.setPadding(6f);
        return cell;
    }

    private static PdfPCell headerCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, LABEL));
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderWidth(0.6f);
        cell.setPadding(4f);
        cell.setHorizontalAlignment(text.equals("Item") ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
        return cell;
    }

    private static PdfPCell cell(String text, Font font, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(text == null ? "" : text, font));
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderWidth(0.2f);
        cell.setBorderColor(java.awt.Color.LIGHT_GRAY);
        cell.setPadding(4f);
        cell.setHorizontalAlignment(alignment);
        return cell;
    }

    private static void totalRow(PdfPTable table, String label, String value, boolean emphasis) {
        Font font = emphasis ? BODY_BOLD : BODY;
        PdfPCell left = new PdfPCell(new Phrase(label, font));
        left.setBorder(emphasis ? Rectangle.TOP : Rectangle.NO_BORDER);
        left.setHorizontalAlignment(Element.ALIGN_RIGHT);
        left.setPadding(3f);
        PdfPCell right = new PdfPCell(new Phrase(value, font));
        right.setBorder(emphasis ? Rectangle.TOP : Rectangle.NO_BORDER);
        right.setHorizontalAlignment(Element.ALIGN_RIGHT);
        right.setPadding(3f);
        table.addCell(left);
        table.addCell(right);
    }

    private static Paragraph right(String text, Font font) {
        Paragraph paragraph = new Paragraph(text == null ? "" : text, font);
        paragraph.setAlignment(Element.ALIGN_RIGHT);
        return paragraph;
    }

    private static Paragraph spacer(float height) {
        Paragraph paragraph = new Paragraph(" ", SMALL);
        paragraph.setSpacingAfter(height);
        return paragraph;
    }

    private static void add(List<String> lines, String value) {
        if (notBlank(value)) {
            lines.add(value.trim());
        }
    }

    private static String join(String first, String second) {
        if (!notBlank(first)) {
            return second;
        }
        return notBlank(second) ? first + " " + second : first;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
