/*
 *
 * DallyControl — generic report export (new module, not part of upstream Headwind).
 *
 * Writes a single report (title + typed columns + rows) as CSV or as a one-sheet Excel workbook,
 * reusing Apache POI the same way {@link DeviceExport} does. The resource builds the columns and
 * rows once and hands them to both the JSON endpoint and this exporter, so a report is defined in
 * exactly one place.
 *
 */

package com.hmdm.util;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** CSV / Excel for one fleet report. Stateless; {@link Column} describes how to render each value. */
public final class ReportExport {

    /** A report column: the row key, the header label and a value type for formatting. */
    public static final class Column {
        public final String key;
        public final String label;
        /** text | number | bytes | datetime (epoch millis) | day (yyyy-MM-dd string). */
        public final String type;

        public Column(String key, String label, String type) {
            this.key = key;
            this.label = label;
            this.type = type == null ? "text" : type;
        }
    }

    private ReportExport() {}

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // --- CSV ---------------------------------------------------------------------------------------------------

    public static void writeCsv(List<Column> cols, List<Map<String, Object>> rows, ZoneId zone, OutputStream out)
            throws IOException {
        writeCsv(cols, rows, zone, ';', out);
    }

    /** The separators a CSV export accepts; anything else falls back to ';' (what Excel in Spanish expects). */
    public static char separator(String s) {
        if (s == null) return ';';
        switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
            case ",": case "comma": case "coma": return ',';
            case "\t": case "tab": case "\\t": return '\t';
            case "|": case "pipe": return '|';
            default: return ';';
        }
    }

    /** CSV in UTF-8 with BOM and CRLF rows, fields separated by {@code sep} (quoted when they contain it). */
    public static void writeCsv(List<Column> cols, List<Map<String, Object>> rows, ZoneId zone, char sep,
                                OutputStream out) throws IOException {
        Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        // UTF-8 BOM so Excel on Windows opens accented Spanish correctly.
        w.write('﻿');
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(csv(cols.get(i).label, sep));
        }
        sb.append("\r\n");
        for (Map<String, Object> row : rows) {
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) sb.append(sep);
                sb.append(csv(text(cols.get(i), row.get(cols.get(i).key), zone), sep));
            }
            sb.append("\r\n");
        }
        w.write(sb.toString());
        w.flush();
    }

    private static String csv(String v, char sep) {
        if (v == null) return "";
        boolean quote = v.indexOf(sep) >= 0 || v.contains(";") || v.contains("\"") || v.contains("\n") || v.contains("\r");
        String s = v.replace("\"", "\"\"");
        return quote ? "\"" + s + "\"" : s;
    }

    /** Plain display text for CSV (dates in the zone, bytes as a human size). */
    private static String text(Column c, Object v, ZoneId zone) {
        if (v == null) return "";
        switch (c.type) {
            case "datetime": {
                long ms = asLong(v);
                return ms <= 0 ? "" : DT.format(Instant.ofEpochMilli(ms).atZone(zone));
            }
            case "bytes":
                return humanBytes(asLong(v));
            default:
                return "text".equals(c.type) ? inert(String.valueOf(v)) : String.valueOf(v);
        }
    }

    /**
     * A text cell that a spreadsheet would run as a formula (a device name such as {@code =HYPERLINK(...)}) is
     * written with a leading apostrophe. Plain signed numbers and phone numbers ("+57 300…", "-74.08") are kept.
     */
    static String inert(String v) {
        if (v.isEmpty()) return v;
        char f = v.charAt(0);
        boolean formula = f == '=' || f == '@' || f == '\t' || f == '\r'
                || ((f == '+' || f == '-') && !v.matches("[+-][0-9 .,()\\-]*"));
        return formula ? "'" + v : v;
    }

    // --- Excel -------------------------------------------------------------------------------------------------

    public static void writeXlsx(String title, List<Column> cols, List<Map<String, Object>> rows,
                                 ZoneId zone, long now, OutputStream out) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles st = new Styles(wb);
            Sheet sh = wb.createSheet("Reporte");

            Row t = sh.createRow(0);
            Cell tc = t.createCell(0);
            tc.setCellValue(title == null ? "DallyControl — Reporte" : "DallyControl — " + title);
            tc.setCellStyle(st.title);
            Row meta = sh.createRow(1);
            Cell mc = meta.createCell(0);
            mc.setCellValue("Generado: " + DT.format(Instant.ofEpochMilli(now).atZone(zone))
                    + "   ·   Filas: " + rows.size());
            mc.setCellStyle(st.plain);

            int headerRow = 3;
            Row hr = sh.createRow(headerRow);
            for (int i = 0; i < cols.size(); i++) {
                Cell c = hr.createCell(i);
                c.setCellValue(cols.get(i).label);
                c.setCellStyle(st.head);
            }
            sh.createFreezePane(0, headerRow + 1);

            int r = headerRow + 1;
            for (Map<String, Object> row : rows) {
                Row xr = sh.createRow(r++);
                for (int i = 0; i < cols.size(); i++) {
                    Column col = cols.get(i);
                    Cell cell = xr.createCell(i);
                    Object v = row.get(col.key);
                    switch (col.type) {
                        case "datetime": {
                            long ms = asLong(v);
                            if (ms > 0) {
                                cell.setCellValue(Date.from(Instant.ofEpochMilli(ms).atZone(zone)
                                        .toLocalDateTime().atZone(ZoneId.systemDefault()).toInstant()));
                                cell.setCellStyle(st.date);
                            }
                            break;
                        }
                        case "number":
                        case "bytes": {
                            if (v instanceof Number) {
                                cell.setCellValue(((Number) v).doubleValue());
                                cell.setCellStyle(st.plain);
                            } else if (v != null) {
                                cell.setCellValue(String.valueOf(v));
                                cell.setCellStyle(st.plain);
                            }
                            break;
                        }
                        default:
                            cell.setCellValue(v == null ? "" : String.valueOf(v));
                            cell.setCellStyle(st.plain);
                    }
                }
            }

            int rows2 = r - headerRow;
            if (rows2 > 1) sh.setAutoFilter(new CellRangeAddress(headerRow, r - 1, 0, Math.max(0, cols.size() - 1)));
            for (int i = 0; i < cols.size(); i++) {
                int w = cols.get(i).label.length() + 2;
                for (int rr = headerRow + 1; rr < Math.min(r, headerRow + 200); rr++) {
                    Row rw = sh.getRow(rr);
                    Cell cc = rw == null ? null : rw.getCell(i);
                    if (cc != null) w = Math.max(w, cc.toString().length() + 2);
                }
                sh.setColumnWidth(i, Math.min(60, Math.max(12, w)) * 256);
            }
            wb.write(out);
        }
    }

    private static final class Styles {
        final CellStyle head, plain, date, title;

        Styles(XSSFWorkbook wb) {
            Font bold = wb.createFont();
            bold.setBold(true);
            bold.setColor(IndexedColors.WHITE.getIndex());
            head = wb.createCellStyle();
            head.setFont(bold);
            head.setFillForegroundColor(IndexedColors.GREY_80_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            head.setBorderBottom(BorderStyle.THIN);
            plain = wb.createCellStyle();
            CreationHelper ch = wb.getCreationHelper();
            date = wb.createCellStyle();
            date.setDataFormat(ch.createDataFormat().getFormat("yyyy-mm-dd hh:mm"));
            Font big = wb.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 14);
            title = wb.createCellStyle();
            title.setFont(big);
        }
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    private static long asLong(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    /** "1.2 GB" / "340 MB" — same unit ladder the console uses. */
    public static String humanBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        String[] u = {"B", "KB", "MB", "GB", "TB", "PB"};
        double v = bytes;
        int i = 0;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return (i == 0 ? String.format(java.util.Locale.ROOT, "%.0f", v)
                : String.format(java.util.Locale.ROOT, "%.1f", v)) + " " + u[i];
    }
}
