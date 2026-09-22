package com.sujula.service.vendorcatalogue.impl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import com.sujula.exceptions.BadRequestException;

/**
 * Turns a seller's spreadsheet into rows, and nothing more.
 *
 * <p>Separate from the import itself because the two fail differently and a
 * seller needs to be told which happened. "This is not a spreadsheet" is a
 * problem with the file; "row 37 has no price" is a problem with a row, and a
 * class that did both would report the second as the first the moment a single
 * cell was odd.
 *
 * <p>CSV is parsed here rather than pulled in as a dependency: it is small,
 * specified by RFC 4180, and the only parts that catch people out - quoted
 * commas, embedded newlines, doubled quotes, a BOM - are handled below and
 * tested. XLSX is not, because it is a zip of XML schemas and hand-rolling it
 * would be a mistake.
 */
@Component
public class SpreadsheetReader {

    /** A guard against a file that would exhaust memory before it is rejected. */
    private static final int MAX_ROWS = 20_000;

    /** What a parsed file looks like: a header, then rows keyed by it. */
    public record Sheet(List<String> headers, List<Map<String, String>> rows) {

        public int size() {
            return rows.size();
        }
    }

    /**
     * Detects the format from the bytes rather than the filename.
     *
     * <p>A filename is a claim. A seller who renamed report.xlsx to report.csv
     * because an upload form asked for CSV deserves a better answer than a
     * parser choking on binary, and someone doing it deliberately deserves no
     * benefit from it.
     */
    public String detectFormat(byte[] content) {
        if (content != null && content.length >= 4
                && content[0] == 'P' && content[1] == 'K'
                && content[2] == 3 && content[3] == 4) {
            // A zip header. Every xlsx is one; no csv is.
            return "xlsx";
        }
        return "csv";
    }

    public Sheet read(byte[] content, String format) {
        if (content == null || content.length == 0) {
            throw new BadRequestException("That file is empty.");
        }
        return "xlsx".equalsIgnoreCase(format) ? readXlsx(content) : readCsv(content);
    }

    // -- CSV -----------------------------------------------------------------

    private Sheet readCsv(byte[] content) {
        try (Reader reader = new InputStreamReader(
                new ByteArrayInputStream(content), StandardCharsets.UTF_8)) {

            List<List<String>> table = parse(reader);
            if (table.isEmpty()) {
                throw new BadRequestException("That file has no rows in it.");
            }

            List<String> headers = normaliseHeaders(table.get(0));
            List<Map<String, String>> rows = new ArrayList<>();
            for (int i = 1; i < table.size(); i++) {
                Map<String, String> row = toRow(headers, table.get(i));
                if (!row.isEmpty()) {
                    rows.add(row);
                }
            }
            return new Sheet(headers, rows);

        } catch (IOException unreadable) {
            throw new BadRequestException("That file could not be read: " + unreadable.getMessage());
        }
    }

    /**
     * RFC 4180, with the parts that actually bite.
     *
     * <p>A quoted field may contain commas, newlines and doubled quotes, and a
     * file written by Excel on Windows ends its lines with CRLF and may begin
     * with a byte-order mark. Every one of those turns a naive
     * {@code split(",")} into a silently wrong import, which is worse than a
     * refused one.
     */
    private static List<List<String>> parse(Reader reader) throws IOException {
        List<List<String>> table = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();

        boolean inQuotes = false;
        boolean rowHasContent = false;
        boolean first = true;
        int read;

        while ((read = reader.read()) != -1) {
            char ch = (char) read;

            if (first) {
                first = false;
                if (ch == '﻿') {
                    continue;   // Excel's byte-order mark, which is not data
                }
            }

            if (inQuotes) {
                if (ch == '"') {
                    int next = reader.read();
                    if (next == '"') {
                        field.append('"');   // an escaped quote inside a quoted field
                    } else {
                        inQuotes = false;
                        if (next == -1) {
                            break;
                        }
                        // Re-handle the character that ended the quoted section.
                        if (next == ',') {
                            row.add(field.toString());
                            field.setLength(0);
                            rowHasContent = true;
                        } else if (next == '\n' || next == '\r') {
                            row.add(field.toString());
                            field.setLength(0);
                            table.add(new ArrayList<>(row));
                            row.clear();
                            rowHasContent = false;
                        } else {
                            field.append((char) next);
                        }
                    }
                } else {
                    field.append(ch);   // a comma or newline inside quotes is content
                }
                continue;
            }

            switch (ch) {
                case '"' -> inQuotes = true;
                case ',' -> {
                    row.add(field.toString());
                    field.setLength(0);
                    rowHasContent = true;
                }
                case '\r' -> { /* CRLF: the newline does the work */ }
                case '\n' -> {
                    row.add(field.toString());
                    field.setLength(0);
                    table.add(new ArrayList<>(row));
                    row.clear();
                    rowHasContent = false;
                }
                default -> field.append(ch);
            }

            if (table.size() > MAX_ROWS) {
                throw new BadRequestException(
                        "That file has more than " + MAX_ROWS + " rows. Split it into smaller files.");
            }
        }

        if (field.length() > 0 || rowHasContent || !row.isEmpty()) {
            row.add(field.toString());
            table.add(row);
        }
        return table;
    }

    // -- XLSX ----------------------------------------------------------------

    private Sheet readXlsx(byte[] content) {
        try (InputStream in = new ByteArrayInputStream(content);
             Workbook workbook = new XSSFWorkbook(in)) {

            if (workbook.getNumberOfSheets() == 0) {
                throw new BadRequestException("That workbook has no sheets in it.");
            }
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) {
                throw new BadRequestException("That sheet has no header row.");
            }

            // A formatter rather than getStringCellValue: a SKU typed as a
            // number comes back as 1.2345E7 otherwise, and a price formatted as
            // currency comes back with a symbol in it.
            DataFormatter formatter = new DataFormatter(Locale.ROOT);

            List<String> headers = new ArrayList<>();
            for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                headers.add(formatter.formatCellValue(headerRow.getCell(c)));
            }
            headers = normaliseHeaders(headers);

            List<Map<String, String>> rows = new ArrayList<>();
            for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < headers.size(); c++) {
                    Cell cell = row.getCell(c);
                    cells.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
                }
                Map<String, String> mapped = toRow(headers, cells);
                if (!mapped.isEmpty()) {
                    rows.add(mapped);
                }
                if (rows.size() > MAX_ROWS) {
                    throw new BadRequestException(
                            "That file has more than " + MAX_ROWS + " rows. Split it up.");
                }
            }
            return new Sheet(headers, rows);

        } catch (IOException | RuntimeException unreadable) {
            if (unreadable instanceof BadRequestException refused) {
                throw refused;
            }
            throw new BadRequestException(
                    "That file is not a spreadsheet we can read. Save it as .xlsx or .csv.");
        }
    }

    // -- Shared --------------------------------------------------------------

    /**
     * Headers as keys: trimmed, lower-cased, spaces and hyphens flattened.
     *
     * <p>So "Short Description", "short_description" and "short-description" are
     * one column. A seller's spreadsheet is theirs and they will not have used
     * our spelling.
     */
    private static List<String> normaliseHeaders(List<String> raw) {
        List<String> headers = new ArrayList<>(raw.size());
        for (String header : raw) {
            headers.add(header == null ? ""
                    : header.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_'));
        }
        return headers;
    }

    /** Returns an empty map for a blank row, so trailing blanks are not errors. */
    private static Map<String, String> toRow(List<String> headers, List<String> cells) {
        Map<String, String> row = new LinkedHashMap<>();
        boolean anything = false;
        for (int i = 0; i < headers.size(); i++) {
            String header = headers.get(i);
            if (header == null || header.isBlank()) {
                continue;
            }
            String value = i < cells.size() && cells.get(i) != null ? cells.get(i).trim() : "";
            row.put(header, value);
            anything |= !value.isEmpty();
        }
        return anything ? row : Map.of();
    }

    /**
     * Quotes one value for a CSV this server writes.
     *
     * <p>Here rather than in the exporter because it is the mirror of the parser
     * above, and the two have to agree: a file this quotes must be a file that
     * reads back identically. A description with a comma in it, which every
     * seller has, shifts every column after it without this.
     */
    public static String csvCell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0
                && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    /**
     * Parses a money column out of whatever the seller typed.
     *
     * <p>The hard part is that "1,200" is twelve hundred to a Gambian seller
     * writing in English and one-point-two to a Senegalese one writing in
     * French, and the file says nothing about which. Guessing wrongly by a
     * factor of a thousand is the single most expensive mistake this whole
     * import can make.
     *
     * <p>So, the rule that is right most often:
     *
     * <ul>
     *   <li>Both separators present - the later one is the decimal point and
     *       the other groups thousands. "1.200,50" and "1,200.50" are both
     *       1200.50, which is unambiguous and needs no guessing.</li>
     *   <li>One separator with exactly three digits after it - a thousands
     *       group. No currency this platform supports has three minor units, so
     *       "1,200" and "1.200" are both 1200.</li>
     *   <li>One separator with one or two digits after it - a decimal point.
     *       "450.00" and "1200,5" mean what they look like.</li>
     * </ul>
     *
     * <p>Returns null rather than zero when it cannot tell. Zero would be a free
     * product; null is a row error with the seller's own text quoted back.
     */
    public static BigDecimal money(String value) {
        String cleaned = value == null ? "" : value.replaceAll("[^0-9.,-]", "").trim();
        if (cleaned.isEmpty()) {
            return null;
        }

        int lastComma = cleaned.lastIndexOf(',');
        int lastDot = cleaned.lastIndexOf('.');

        if (lastComma >= 0 && lastDot >= 0) {
            // Both. Whichever comes last is the decimal point.
            cleaned = lastComma > lastDot
                    ? cleaned.replace(".", "").replace(',', '.')
                    : cleaned.replace(",", "");
        } else if (lastComma >= 0 || lastDot >= 0) {
            int separator = Math.max(lastComma, lastDot);
            int digitsAfter = cleaned.length() - separator - 1;
            cleaned = digitsAfter == 3
                    ? cleaned.substring(0, separator) + cleaned.substring(separator + 1)
                    : cleaned.substring(0, separator) + "." + cleaned.substring(separator + 1);
        }

        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
