package com.sujula.service.fulfilment;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.Map;

import javax.imageio.ImageIO;

import org.springframework.stereotype.Component;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.sujula.dto.response.fulfilment.FulfilmentResponses;
import com.sujula.model.order.OrderItem;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.user.Vendor;
import com.sujula.service.fulfilment.VendorFulfilmentService.ParcelLabel;
import com.sujula.service.security.SignedTokens;

import lombok.extern.slf4j.Slf4j;

/**
 * Draws the label that goes on the box.
 *
 * <p>A label is read by three different parties and has to be safe for all of
 * them, because a parcel on a bench is visible to everybody who walks past it.
 * So:
 *
 * <ul>
 *   <li><b>No address in print.</b> The town is there so the parcel can be
 *       sorted; the street is not. The QR resolves the full address for a
 *       driver who scans it, which means the address is available to somebody
 *       authorised and not to somebody with a camera.</li>
 *   <li><b>No collection code.</b> That code releases the parcel, and a code
 *       printed on the thing it protects protects nothing. It is read out, not
 *       written down.</li>
 *   <li><b>No prices, and no buyer.</b> A label that says what is in the box
 *       and what it cost is a shopping list for whoever is deciding which
 *       parcel to take.</li>
 * </ul>
 *
 * <p>The QR carries a signed token naming the vendor order, not the order's
 * details. Anyone can read the token; only the platform can turn it into an
 * address, and nobody can edit it to name a different parcel. It is signed with
 * its own purpose string, so an invoice link presented to the scanner does not
 * verify and a label token presented to the invoice endpoint does not either.
 */
@Slf4j
@Component
public class ParcelLabelRenderer {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm");

    private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 22f);
    private static final Font HUGE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 30f);
    private static final Font H2 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12f);
    private static final Font LABEL = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7.5f);
    private static final Font BODY = FontFactory.getFont(FontFactory.HELVETICA, 10f);
    private static final Font BODY_BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10f);
    private static final Font SMALL = FontFactory.getFont(FontFactory.HELVETICA, 7f);

    /** A6, which is the size of the label stock every courier in the region uses. */
    private static final Rectangle LABEL_SIZE = PageSize.A6;

    private final ParcelLabelProperties properties;
    private final SignedTokens tokens;

    public ParcelLabelRenderer(ParcelLabelProperties properties) {
        this.properties = properties;
        // Same signer as the invoice link, different purpose. Two private copies
        // of one HMAC drift, and the one that rots is the one nobody re-read.
        this.tokens = new SignedTokens(properties.getSigningSecret(), "parcel-label");
    }

    /** The scan target for a parcel, for whoever resolves one. */
    public String scanUrl(VendorOrder slice) {
        String token = tokens.mint(String.valueOf(slice.getId()),
                Instant.now().plus(properties.getQrTtl()).getEpochSecond());
        String base = properties.getBaseUrl() == null ? "" : properties.getBaseUrl().trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + properties.getScanPath() + "/" + token;
    }

    /**
     * Which vendor order a scanned token names.
     *
     * @throws com.sujula.exceptions.BadRequestException if it is malformed,
     *         has been tampered with, or has expired
     */
    public Long resolve(String token) {
        return Long.valueOf(tokens.verify(token, "parcel label"));
    }

    /** Renders the label. */
    public ParcelLabel render(VendorOrder slice, Vendor vendor, FulfilmentResponses.Shipping shipping) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(LABEL_SIZE, 18f, 18f, 16f, 16f);
        PdfWriter.getInstance(document, out);
        document.open();
        try {
            document.add(header(vendor));
            document.add(recipientBlock(shipping));
            document.add(contentsBlock(slice));
            document.add(qrBlock(slice));
            document.add(footer(slice));
        } finally {
            document.close();
        }

        String number = slice.getOrder() == null ? String.valueOf(slice.getId())
                : slice.getOrder().getOrderNumber();
        return new ParcelLabel(out.toByteArray(),
                "parcel-" + number + "-" + slice.getId() + ".pdf", "application/pdf");
    }

    // ── Blocks ───────────────────────────────────────────────────────────────

    private Element header(Vendor vendor) {
        PdfPTable table = full(new float[] {3f, 2f});

        Paragraph brand = new Paragraph(properties.getIssuerName(), TITLE);
        table.addCell(borderless(brand, Element.ALIGN_LEFT));

        String from = vendor == null || vendor.getStoreName() == null ? "" : vendor.getStoreName();
        Paragraph sender = new Paragraph();
        sender.add(new Phrase("FROM\n", LABEL));
        sender.add(new Phrase(from, BODY));
        table.addCell(borderless(sender, Element.ALIGN_RIGHT));

        table.setSpacingAfter(8f);
        return table;
    }

    /**
     * Who the parcel is for, and the town it is going to.
     *
     * <p>The recipient is often not the buyer — the buyer is in Madrid and this
     * parcel is for their sister in Serrekunda — so the name printed here is the
     * delivery name and never the payer's.
     */
    private Element recipientBlock(FulfilmentResponses.Shipping shipping) {
        PdfPTable table = full(new float[] {1f});
        Paragraph block = new Paragraph();
        block.add(new Phrase("DELIVER TO\n", LABEL));

        if (shipping == null) {
            block.add(new Phrase("Address held by the platform — scan the code\n", BODY));
        } else {
            if (shipping.recipientName() != null) {
                block.add(new Phrase(shipping.recipientName() + "\n", HUGE));
            }
            StringBuilder where = new StringBuilder();
            if (shipping.town() != null) {
                where.append(shipping.town());
            }
            if (shipping.country() != null) {
                where.append(where.isEmpty() ? "" : ", ").append(shipping.country());
            }
            if (!where.isEmpty()) {
                block.add(new Phrase(where + "\n", H2));
            }
            if (shipping.pickupPointName() != null) {
                block.add(new Phrase("Pickup point: " + shipping.pickupPointName() + "\n", BODY_BOLD));
            }
            if (shipping.phoneHint() != null) {
                block.add(new Phrase("Phone ending " + shipping.phoneHint() + "\n", BODY));
            }
            if (shipping.international()) {
                block.add(new Phrase("INTERNATIONAL — customs paperwork required\n", BODY_BOLD));
            }
        }

        // Said plainly, because a driver who cannot see a street will otherwise
        // assume the label is wrong and hand the parcel back.
        block.add(new Phrase(
                "Full address is held by the platform. Scan the code below to see it.", SMALL));

        PdfPCell cell = new PdfPCell(block);
        cell.setPadding(8f);
        cell.setBorderWidth(1.2f);
        table.addCell(cell);
        table.setSpacingAfter(8f);
        return table;
    }

    /**
     * How many parcels' worth of things are in the box, and nothing about what
     * they are.
     *
     * <p>A label listing "1 × iPhone 13" tells a thief which box to take. The
     * count is what a driver needs to check they have been handed everything.
     */
    private Element contentsBlock(VendorOrder slice) {
        int units = 0;
        for (OrderItem item : slice.getItems()) {
            units += item.getQuantity() == null ? 0 : item.getQuantity();
        }
        PdfPTable table = full(new float[] {1f, 1f});

        table.addCell(borderless(labelled("ORDER",
                slice.getOrder() == null ? "—" : slice.getOrder().getOrderNumber()), Element.ALIGN_LEFT));
        table.addCell(borderless(labelled("CONTENTS",
                slice.getItems().size() + " line(s), " + units + " item(s)"), Element.ALIGN_RIGHT));

        table.setSpacingAfter(6f);
        return table;
    }

    private Element qrBlock(VendorOrder slice) {
        PdfPTable table = full(new float[] {1f});
        PdfPCell cell;
        try {
            Image qr = Image.getInstance(qrPng(scanUrl(slice)));
            qr.scaleToFit(120f, 120f);
            cell = new PdfPCell(qr, false);
        } catch (Exception failed) {
            // A label without its code is still worth printing: it carries the
            // order number and the town, which is enough for a human to sort it.
            // Failing the whole request would leave the seller with no label at
            // all.
            log.warn("[Label] Could not render the QR for slice {}: {}",
                    slice.getId(), failed.toString());
            cell = new PdfPCell(new Paragraph(
                    "Code unavailable — quote the order number above", BODY));
        }
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setPaddingTop(4f);
        table.addCell(cell);
        return table;
    }

    private Element footer(VendorOrder slice) {
        Paragraph footer = new Paragraph();
        footer.setAlignment(Element.ALIGN_CENTER);
        footer.add(new Phrase(
                "Do not write the collection code on this parcel. Read it to the driver.\n",
                LABEL));
        footer.add(new Phrase("Printed " + LocalDateTime.now().format(STAMP)
                + " · parcel " + slice.getId(), SMALL));
        footer.setSpacingBefore(4f);
        return footer;
    }

    // ── Drawing helpers ──────────────────────────────────────────────────────

    private static byte[] qrPng(String content) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        // A label gets rained on, folded and scanned in poor light. Q corrects
        // about a quarter of the symbol, which is the difference between a
        // scuffed parcel being scannable and being opened by hand.
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.Q);
        hints.put(EncodeHintType.MARGIN, 1);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");

        BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 360, 360, hints);
        BufferedImage image = MatrixToImageWriter.toBufferedImage(matrix);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", png);
        return png.toByteArray();
    }

    private static Paragraph labelled(String caption, String value) {
        Paragraph paragraph = new Paragraph();
        paragraph.add(new Phrase(caption + "\n", LABEL));
        paragraph.add(new Phrase(value == null ? "—" : value, BODY_BOLD));
        return paragraph;
    }

    private static PdfPTable full(float[] widths) {
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100f);
        return table;
    }

    private static PdfPCell borderless(Element content, int alignment) {
        PdfPCell cell = new PdfPCell();
        cell.addElement(content);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setHorizontalAlignment(alignment);
        cell.setPadding(0f);
        return cell;
    }
}
