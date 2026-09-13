package com.sujula.service.vendorcatalogue;

import com.sujula.exceptions.BadRequestException;
import com.sujula.service.vendorcatalogue.impl.SpreadsheetReader;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hand-rolled CSV parser, executed against the things that actually break
 * one.
 *
 * <p>Every case here is a real spreadsheet a seller sends: a description with a
 * comma in it, a quote inside a quoted field, a file saved on Windows, a file
 * Excel stamped with a byte-order mark, a price written with a thousands
 * separator. A naive {@code split(",")} passes none of them and fails silently
 * on all of them, which is worse than refusing the file - the seller ends up
 * with four hundred listings whose descriptions are cut in half.
 */
class SpreadsheetReaderTest {

    private SpreadsheetReader reader;

    @BeforeEach
    void setUp() {
        reader = new SpreadsheetReader();
    }

    private SpreadsheetReader.Sheet csv(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return reader.read(bytes, reader.detectFormat(bytes));
    }

    @Test
    void readsAPlainFile() {
        SpreadsheetReader.Sheet sheet = csv("""
                name,price,stock
                Kettle,450,12
                Speaker,1200,3
                """);

        assertEquals(List.of("name", "price", "stock"), sheet.headers());
        assertEquals(2, sheet.size());
        assertEquals("Kettle", sheet.rows().get(0).get("name"));
        assertEquals("1200", sheet.rows().get(1).get("price"));
    }

    @Test
    void aCommaInsideQuotesIsNotAColumnBreak() {
        SpreadsheetReader.Sheet sheet = csv("""
                name,description
                Wax print,"Six yards, cut to order"
                """);

        assertEquals(1, sheet.size());
        assertEquals("Six yards, cut to order", sheet.rows().get(0).get("description"));
    }

    @Test
    void aNewlineInsideQuotesIsNotARowBreak() {
        SpreadsheetReader.Sheet sheet = csv(
                "name,description\nKettle,\"1.7 litres\nStainless steel\"\nSpeaker,Loud\n");

        assertEquals(2, sheet.size());
        assertTrue(sheet.rows().get(0).get("description").contains("\n"),
                sheet.rows().get(0).get("description"));
        assertEquals("Speaker", sheet.rows().get(1).get("name"));
    }

    @Test
    void aDoubledQuoteIsOneQuote() {
        SpreadsheetReader.Sheet sheet = csv(
                "name,description\nTV,\"A 32\"\" screen\"\n");

        assertEquals("A 32\" screen", sheet.rows().get(0).get("description"));
    }

    @Test
    void windowsLineEndingsDoNotLeaveCarriageReturnsInTheData() {
        SpreadsheetReader.Sheet sheet = csv("name,price\r\nKettle,450\r\n");

        assertEquals("Kettle", sheet.rows().get(0).get("name"));
        assertEquals("450", sheet.rows().get(0).get("price"));
    }

    @Test
    void excelsByteOrderMarkIsNotPartOfTheFirstHeader() {
        SpreadsheetReader.Sheet sheet = csv("﻿name,price\nKettle,450\n");

        // Without this, the first column is called "﻿name" and every
        // lookup of "name" misses - on the one column that is always required.
        assertEquals("name", sheet.headers().get(0));
        assertEquals("Kettle", sheet.rows().get(0).get("name"));
    }

    @Test
    void headersAreMatchedHoweverTheSellerSpelledThem() {
        SpreadsheetReader.Sheet sheet = csv("""
                Short Description,Low-Stock Threshold
                Nice,2
                """);

        assertEquals(List.of("short_description", "low_stock_threshold"), sheet.headers());
    }

    @Test
    void aFinalRowWithNoTrailingNewlineIsStillARow() {
        SpreadsheetReader.Sheet sheet = csv("name,price\nKettle,450");
        assertEquals(1, sheet.size());
        assertEquals("450", sheet.rows().get(0).get("price"));
    }

    @Test
    void blankRowsAreSkippedRatherThanImportedAsEmptyListings() {
        SpreadsheetReader.Sheet sheet = csv("name,price\nKettle,450\n,\n\nSpeaker,1200\n");

        assertEquals(2, sheet.size());
    }

    @Test
    void anEmptyFileIsRefusedWithSomethingReadable() {
        assertThrows(BadRequestException.class, () -> csv(""));
    }

    // -- Money ---------------------------------------------------------------

    /**
     * Getting this wrong is wrong by a factor of a thousand.
     *
     * <p>"1,200" is twelve hundred to a Gambian seller writing in English and
     * one-point-two to a Senegalese one writing in French, and the file says
     * nothing about which. Both separators present settles it; a lone separator
     * is read by how many digits follow, since no currency here has three
     * minor units.
     */
    @Test
    void pricesSurviveTheWaySellersActuallyWriteThem() {
        // A lone separator with three digits after it groups thousands.
        assertEquals(new BigDecimal("1200"), SpreadsheetReader.money("1,200"));
        assertEquals(new BigDecimal("1200"), SpreadsheetReader.money("1.200"));

        // Both separators: the later one is the decimal point, whichever it is.
        assertEquals(new BigDecimal("1200.50"), SpreadsheetReader.money("1,200.50"));
        assertEquals(new BigDecimal("1200.50"), SpreadsheetReader.money("1.200,50"));

        // One or two digits after a lone separator: a decimal point.
        assertEquals(new BigDecimal("450.00"), SpreadsheetReader.money("  450.00  "));
        assertEquals(new BigDecimal("1200.5"), SpreadsheetReader.money("1200,5"));

        // A currency code or symbol typed into the cell is not part of the number.
        assertEquals(new BigDecimal("450"), SpreadsheetReader.money("GMD 450"));
        assertEquals(new BigDecimal("60000"), SpreadsheetReader.money("60 000 XOF"));
    }

    @Test
    void somethingThatIsNotAPriceComesBackNullRatherThanZero() {
        // Zero would be a free product. Null is a row error the seller can fix.
        assertNull(SpreadsheetReader.money("ask us"));
        assertNull(SpreadsheetReader.money(""));
        assertNull(SpreadsheetReader.money(null));
    }

    // -- XLSX ----------------------------------------------------------------

    @Test
    void readsARealWorkbookAndTellsItApartFromCsvByItsBytes() throws Exception {
        byte[] xlsx = workbook();

        // Detected from the zip header, not the filename. A seller who renamed
        // report.xlsx to report.csv still gets a working import.
        assertEquals("xlsx", reader.detectFormat(xlsx));

        SpreadsheetReader.Sheet sheet = reader.read(xlsx, reader.detectFormat(xlsx));
        assertEquals(List.of("name", "price", "sku"), sheet.headers());
        assertEquals(2, sheet.size());
        assertEquals("Kettle", sheet.rows().get(0).get("name"));
        // A SKU typed as a number must not come back as 1.2345E7.
        assertEquals("12345000", sheet.rows().get(1).get("sku"));
    }

    @Test
    void somethingThatIsNotASpreadsheetIsRefusedReadably() {
        byte[] notAWorkbook = new byte[] {'P', 'K', 3, 4, 9, 9, 9, 9};

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> reader.read(notAWorkbook, "xlsx"));
        assertTrue(refused.getMessage().contains(".xlsx"), refused.getMessage());
    }

    private static byte[] workbook() throws Exception {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("Products");
            org.apache.poi.ss.usermodel.Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Name");
            header.createCell(1).setCellValue("Price");
            header.createCell(2).setCellValue("SKU");

            org.apache.poi.ss.usermodel.Row first = sheet.createRow(1);
            first.createCell(0).setCellValue("Kettle");
            first.createCell(1).setCellValue(450);
            first.createCell(2).setCellValue("KET-01");

            org.apache.poi.ss.usermodel.Row second = sheet.createRow(2);
            second.createCell(0).setCellValue("Speaker");
            second.createCell(1).setCellValue(1200);
            // Typed as a number, which is how a numeric SKU arrives in practice.
            second.createCell(2).setCellValue(12345000d);

            workbook.write(out);
            return out.toByteArray();
        }
    }
}
