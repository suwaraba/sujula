package com.sujula.service.money;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.stereotype.Component;

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
import com.sujula.dto.response.money.MoneyResponses;
import com.sujula.model.user.Vendor;
import com.sujula.service.vendorcatalogue.impl.SpreadsheetReader;

/**
 * Draws a month's statement.
 *
 * <p>A statement is a document a seller may take to a bank or an accountant, so
 * it is built to be checkable rather than merely readable: an opening balance,
 * every movement in the month, and a closing balance that is the first plus the
 * rows. Somebody can add the column up and get the last line, which is the only
 * property that makes a statement worth anything.
 *
 * <p><strong>One currency per document.</strong> Opening and closing balances
 * are only meaningful within one, and a statement that mixed two would have a
 * closing figure that is not a quantity of anything (C2).
 */
@Component
public class StatementRenderer {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm 'UTC'");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy");

    private static final Font H1 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16f);
    private static final Font H2 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10f);
    private static final Font LABEL = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7.5f);
    private static final Font BODY = FontFactory.getFont(FontFactory.HELVETICA, 8.5f);
    private static final Font BODY_BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.5f);
    private static final Font SMALL = FontFactory.getFont(FontFactory.HELVETICA, 7f);

    /** The statement as a PDF. */
    public MoneyResponses.Statement pdf(Vendor vendor, MoneyResponses.StatementPeriod statement) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 36f, 36f, 36f, 40f);
        PdfWriter.getInstance(document, out);
        document.open();
        try {
            document.add(header(vendor, statement));
            document.add(balances(statement));
            document.add(summary(statement));
            document.add(movements(statement));
            document.add(footer(statement));
        } finally {
            document.close();
        }
        return new MoneyResponses.Statement(out.toByteArray(), filename(statement, "pdf"),
                "application/pdf");
    }

    /**
     * The same figures as a CSV.
     *
     * <p>Opening and closing balances are rows in the same column as the
     * movements, so the file adds up in a spreadsheet exactly as the PDF does on
     * paper. A CSV of only the movements would be a file whose total does not
     * match the document it came from.
     */
    public MoneyResponses.Statement csv(Vendor vendor, MoneyResponses.StatementPeriod statement) {
        StringBuilder csv = new StringBuilder();
        csv.append("Date,Type,Description,Reference,Order,Amount,Currency,Held in escrow\n");

        csv.append(row(statement.from().format(DAY), "OPENING_BALANCE",
                "Balance brought forward", "", "",
                statement.openingBalance(), statement.currency(), ""));

        for (MoneyResponses.Transaction entry : statement.entries()) {
            csv.append(row(
                    entry.occurredAt() == null ? "" : entry.occurredAt().toLocalDate().format(DAY),
                    entry.type().name(), entry.description(),
                    entry.reference() == null ? "" : entry.reference(),
                    entry.orderNumber() == null ? "" : entry.orderNumber(),
                    entry.amount(), entry.currency(),
                    entry.heldInEscrow() ? "yes" : "no"));
        }

        csv.append(row(statement.to().minusDays(1).format(DAY), "CLOSING_BALANCE",
                "Balance carried forward", "", "",
                statement.closingBalance(), statement.currency(), ""));

        return new MoneyResponses.Statement(csv.toString().getBytes(StandardCharsets.UTF_8),
                filename(statement, "csv"), "text/csv");
    }

    // ── PDF blocks ───────────────────────────────────────────────────────────

    private Element header(Vendor vendor, MoneyResponses.StatementPeriod statement) {
        PdfPTable table = full(new float[] {3f, 2f});

        Paragraph left = new Paragraph();
        left.add(new Phrase("Statement\n", H1));
        left.add(new Phrase(statement.from().format(MONTH) + "\n", H2));
        left.add(new Phrase(vendor.getStoreName() == null ? "" : vendor.getStoreName(), BODY));
        table.addCell(borderless(left, Element.ALIGN_LEFT));

        Paragraph right = new Paragraph();
        right.add(new Phrase("CURRENCY\n", LABEL));
        right.add(new Phrase(statement.currency() + "\n", H2));
        right.add(new Phrase("PERIOD\n", LABEL));
        right.add(new Phrase(statement.from().format(DAY) + " to "
                + statement.to().minusDays(1).format(DAY), BODY));
        table.addCell(borderless(right, Element.ALIGN_RIGHT));

        table.setSpacingAfter(12f);
        return table;
    }

    /** The two figures that make the rows below checkable. */
    private Element balances(MoneyResponses.StatementPeriod statement) {
        PdfPTable table = full(new float[] {1f, 1f});

        table.addCell(boxed("BROUGHT FORWARD",
                money(statement.openingBalance(), statement.currency())));
        table.addCell(boxed("CARRIED FORWARD",
                money(statement.closingBalance(), statement.currency())));

        table.setSpacingAfter(12f);
        return table;
    }

    private Element summary(MoneyResponses.StatementPeriod statement) {
        PdfPTable table = full(new float[] {3f, 1f, 2f});
        table.addCell(head("What moved"));
        table.addCell(head("Entries"));
        table.addCell(head("Total"));

        for (MoneyResponses.StatementLine line : statement.totals()) {
            table.addCell(cell(readable(line.type()), BODY, Element.ALIGN_LEFT));
            table.addCell(cell(String.valueOf(line.count()), BODY, Element.ALIGN_RIGHT));
            table.addCell(cell(money(line.total(), statement.currency()), BODY_BOLD,
                    Element.ALIGN_RIGHT));
        }
        if (statement.totals().isEmpty()) {
            PdfPCell empty = cell("Nothing moved in this period.", BODY, Element.ALIGN_LEFT);
            empty.setColspan(3);
            table.addCell(empty);
        }
        table.setSpacingAfter(12f);
        return table;
    }

    private Element movements(MoneyResponses.StatementPeriod statement) {
        PdfPTable table = full(new float[] {1.3f, 1.6f, 4f, 1.8f, 1.6f});
        table.addCell(head("Date"));
        table.addCell(head("Type"));
        table.addCell(head("Description"));
        table.addCell(head("Reference"));
        table.addCell(head("Amount"));

        for (MoneyResponses.Transaction entry : statement.entries()) {
            table.addCell(cell(entry.occurredAt() == null ? ""
                    : entry.occurredAt().toLocalDate().format(DAY), BODY, Element.ALIGN_LEFT));
            table.addCell(cell(readable(entry.type()), BODY, Element.ALIGN_LEFT));

            String description = entry.description()
                    // Escrow is why a seller's money is not where they expect it,
                    // so the statement says so on the row rather than in a legend.
                    + (entry.heldInEscrow() ? "  (held — parcel not yet confirmed)" : "");
            table.addCell(cell(description, BODY, Element.ALIGN_LEFT));
            table.addCell(cell(entry.reference() == null ? "" : entry.reference(),
                    BODY, Element.ALIGN_LEFT));
            table.addCell(cell(money(entry.amount(), statement.currency()), BODY_BOLD,
                    Element.ALIGN_RIGHT));
        }
        return table;
    }

    private Element footer(MoneyResponses.StatementPeriod statement) {
        Paragraph footer = new Paragraph();
        footer.setSpacingBefore(14f);
        footer.add(new Phrase(
                "Every figure is in " + statement.currency() + ", the currency these orders were "
                + "settled in, converted at the rate each order was placed at and never "
                + "recalculated since.\n", SMALL));
        footer.add(new Phrase(
                "Brought forward plus the movements above equals carried forward.\n", SMALL));
        footer.add(new Phrase("Produced "
                + java.time.LocalDateTime.now().format(STAMP), SMALL));
        return footer;
    }

    // ── Small helpers ────────────────────────────────────────────────────────

    private static String filename(MoneyResponses.StatementPeriod statement, String extension) {
        return "statement-" + statement.period() + "-" + statement.currency() + "." + extension;
    }

    /** Reuses the catalogue's own quoting, so the two cannot disagree. */
    private static String row(Object... values) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                line.append(',');
            }
            Object value = values[i];
            line.append(SpreadsheetReader.csvCell(value == null ? "" : String.valueOf(value)));
        }
        return line.append('\n').toString();
    }

    private static String money(BigDecimal amount, String currency) {
        return (amount == null ? BigDecimal.ZERO : amount).toPlainString() + " " + currency;
    }

    /** "COMMISSION_REVERSAL" is not a thing to put in front of a seller. */
    private static String readable(com.sujula.model.constant.LedgerEntryType type) {
        return switch (type) {
            case SALE -> "Sale";
            case COMMISSION -> "Platform commission";
            case REFUND -> "Refund to the buyer";
            case COMMISSION_REVERSAL -> "Commission returned";
            case PAYOUT -> "Paid out";
            case PAYOUT_REVERSAL -> "Payout returned";
            case ADJUSTMENT -> "Adjustment";
        };
    }

    private static PdfPTable full(float[] widths) {
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100f);
        return table;
    }

    private static PdfPCell head(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text.toUpperCase(java.util.Locale.ROOT), LABEL));
        cell.setPadding(4f);
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderWidth(0.8f);
        return cell;
    }

    private static PdfPCell cell(String text, Font font, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(text == null ? "" : text, font));
        cell.setPadding(4f);
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderWidth(0.2f);
        cell.setHorizontalAlignment(alignment);
        return cell;
    }

    private static PdfPCell boxed(String caption, String value) {
        Paragraph content = new Paragraph();
        content.add(new Phrase(caption + "\n", LABEL));
        content.add(new Phrase(value, H2));
        PdfPCell cell = new PdfPCell(content);
        cell.setPadding(8f);
        cell.setBorderWidth(1f);
        return cell;
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
