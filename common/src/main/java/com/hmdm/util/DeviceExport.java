package com.hmdm.util;

import com.hmdm.persistence.domain.DeviceGroupView;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Hyperlink;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The fleet as an Excel workbook (Resumen, Dispositivos, Carpetas, Conexiones): the device inventory
 * ({@link FleetInventory}: connection, battery, storage, Android, network, identifiers) with links to the console
 * and to the map, folders with their policy, and the connection history (periods each device kept checking in).
 * Dates are real Excel dates in the given time zone. {@link #writeCsv} gives the Dispositivos sheet as CSV.
 */
public final class DeviceExport {
    /** Same as the console's "online" (ui/status.ts ONLINE_WINDOW_MS). */
    public static final long ONLINE_WINDOW_MS = FleetInventory.ONLINE_WINDOW_MS;

    public static final class Input {
        public String baseUrl = "";
        public ZoneId zone = ZoneId.of("America/Bogota");
        public long now = System.currentTimeMillis();
        /** A session whose last check-in is older than this has ended. */
        public long connectionGapMs = 20 * 60_000L;
        public List<Map<String, Object>> devices = new ArrayList<>();
        public List<DeviceGroupView> groups = new ArrayList<>();
        public List<Map<String, Object>> connections = new ArrayList<>();
        /** Only devices whose folder is in this set (a folder and its sub-folders); null = all. */
        public Set<Integer> onlyGroups;
        public String scopeLabel = "Todos los dispositivos";
        public long from;
        public long to;
        /** Only these device numbers (the console's current filter or selection); null = all. */
        public Set<String> onlyNumbers;
        /** Devices whose live channel is open now: online whatever their last report says; may be null. */
        public Set<String> live;
        /** The agent version this server serves (for "agente desactualizado"); null = the newest seen. */
        public String latestAgent;
        public int lowBatteryPct = 20;
        public int storageUsedPct = 90;
        public int offlineDays = 7;
    }

    /** The inventory of an input: its columns, one row per exported device, and the fleet summary. */
    public static final class Inventory {
        public final List<ReportExport.Column> columns = FleetInventory.columns();
        public List<Map<String, Object>> rows = new ArrayList<>();
        public Map<String, Object> summary = new LinkedHashMap<>();
        /** The exported devices' raw rows (after the folder and number filters). */
        List<Map<String, Object>> devices = new ArrayList<>();
        Map<Integer, String> paths = new HashMap<>();
    }

    /** Filter the input (folder branch, device numbers) and build its inventory rows and summary. */
    public static Inventory inventory(Input in) {
        Inventory inv = new Inventory();
        Map<Integer, DeviceGroupView> byId = new HashMap<>();
        for (DeviceGroupView g : in.groups) byId.put(g.getId(), g);
        for (DeviceGroupView g : in.groups) inv.paths.put(g.getId(), path(g, byId));
        for (Map<String, Object> d : in.devices) {
            Integer gid = intOf(d.get("groupid"));
            if (in.onlyGroups != null && (gid == null || !in.onlyGroups.contains(gid))) continue;
            if (in.onlyNumbers != null && !in.onlyNumbers.contains(str(d.get("number")))) continue;
            inv.devices.add(d);
        }
        Map<String, Long> openSince = new HashMap<>();
        for (Map<String, Object> c : in.connections) {
            long last = lng(c.get("lastseenat"));
            if (in.now - last <= in.connectionGapMs) openSince.put(str(c.get("devicenumber")), lng(c.get("connectedat")));
        }
        FleetInventory.Options o = new FleetInventory.Options();
        o.now = in.now;
        o.live = in.live;
        o.latestAgent = in.latestAgent;
        o.lowBatteryPct = in.lowBatteryPct;
        o.storageUsedPct = in.storageUsedPct;
        o.offlineDays = in.offlineDays;
        inv.rows = FleetInventory.rows(inv.devices, inv.paths, openSince, o);
        inv.summary = FleetInventory.summary(inv.rows, o);
        return inv;
    }

    /** The Dispositivos sheet as CSV (UTF-8 with BOM), fields separated by {@code separator}. */
    public static void writeCsv(Input in, char separator, OutputStream out) throws IOException {
        Inventory inv = inventory(in);
        ReportExport.writeCsv(inv.columns, inv.rows, in.zone, separator, out);
    }

    private DeviceExport() {}

    public static void write(Input in, OutputStream out) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles st = new Styles(wb);
            Inventory inv = inventory(in);
            Map<Integer, String> paths = inv.paths;
            List<Map<String, Object>> devices = inv.devices;
            Map<String, Map<String, Object>> byNumber = new LinkedHashMap<>();
            for (Map<String, Object> d : devices) byNumber.put(str(d.get("number")), d);

            Sheet summary = wb.createSheet("Resumen");
            Sheet sd = wb.createSheet("Dispositivos");
            Sheet sf = wb.createSheet("Carpetas");
            Sheet sc = wb.createSheet("Conexiones");

            // --- Dispositivos (the inventory: FleetInventory defines the columns and the values) -----------------
            List<ReportExport.Column> cols = inv.columns;
            String[] dh = new String[cols.size()];
            for (int i = 0; i < dh.length; i++) dh[i] = cols.get(i).label;
            header(sd, dh, st);
            int r = 1;
            for (Map<String, Object> v : inv.rows) {
                Row row = sd.createRow(r++);
                String number = str(v.get("number"));
                boolean on = Boolean.TRUE.equals(v.get("online"));
                for (int c = 0; c < cols.size(); c++) {
                    ReportExport.Column col = cols.get(c);
                    Object val = v.get(col.key);
                    switch (col.key) {
                        case "name":
                            link(row.createCell(c), str(val), in.baseUrl + "/devices/" + number, st);
                            continue;
                        case "group":
                            if (v.get("groupid") != null) link(row.createCell(c), str(val), in.baseUrl + "/devices?group=" + v.get("groupid"), st);
                            else text(row.createCell(c), str(val), st);
                            continue;
                        case "status":
                            text(row.createCell(c), str(val), on ? st.ok : st.warn);
                            continue;
                        case "location":
                            if (val != null && !str(val).isEmpty()) link(row.createCell(c), str(val), "https://www.google.com/maps?q=" + val, st);
                            continue;
                        case "osupdate":
                            text(row.createCell(c), str(val), Boolean.TRUE.equals(v.get("osupdatepending")) ? st.warn : st.plain);
                            continue;
                        case "alerts":
                            text(row.createCell(c), str(val), val == null || str(val).isEmpty() ? st.plain : st.warn);
                            continue;
                        default:
                    }
                    if ("datetime".equals(col.type)) date(row.createCell(c), lng(val), in.zone, st);
                    else if ("number".equals(col.type)) {
                        if (val instanceof Number) row.createCell(c).setCellValue(((Number) val).doubleValue());
                    } else text(row.createCell(c), str(val), st);
                }
            }
            finish(sd, dh.length, r);

            // --- Carpetas ------------------------------------------------------------------------------------------
            String[] fh = {"Carpeta", "Política propia", "Política que aplica", "Dispositivos", "Con subcarpetas", "En línea"};
            header(sf, fh, st);
            Map<Integer, List<Integer>> children = new HashMap<>();
            for (DeviceGroupView g : in.groups) children.computeIfAbsent(g.getParentId(), k -> new ArrayList<>()).add(g.getId());
            Map<Integer, int[]> counts = new HashMap<>(); // direct, online
            for (Map<String, Object> d : inv.rows) {
                Integer gid = intOf(d.get("groupid"));
                if (gid == null) continue;
                int[] cnt = counts.computeIfAbsent(gid, k -> new int[2]);
                cnt[0]++;
                if (Boolean.TRUE.equals(d.get("online"))) cnt[1]++;
            }
            List<DeviceGroupView> sorted = new ArrayList<>(in.groups);
            sorted.sort((a, b) -> paths.get(a.getId()).compareToIgnoreCase(paths.get(b.getId())));
            int fr = 1;
            for (DeviceGroupView g : sorted) {
                if (in.onlyGroups != null && !in.onlyGroups.contains(g.getId())) continue;
                int direct = counts.getOrDefault(g.getId(), new int[2])[0];
                int total = 0, on = 0;
                for (int id : subtree(g.getId(), children)) {
                    int[] cnt = counts.getOrDefault(id, new int[2]);
                    total += cnt[0];
                    on += cnt[1];
                }
                Row row = sf.createRow(fr++);
                link(row.createCell(0), paths.get(g.getId()), in.baseUrl + "/devices?group=" + g.getId(), st);
                text(row.createCell(1), nonBlank(g.getConfigurationName(), "(hereda)"), st);
                text(row.createCell(2), nonBlank(g.getEffectiveConfigurationName(), "Global"), st);
                row.createCell(3).setCellValue(direct);
                row.createCell(4).setCellValue(total);
                row.createCell(5).setCellValue(on);
            }
            finish(sf, fh.length, fr);

            // --- Conexiones ----------------------------------------------------------------------------------------
            String[] ch = {"Dispositivo", "Número", "Carpeta", "Conectado", "Desconectado", "Duración (h)", "Estado"};
            header(sc, ch, st);
            int cr = 1;
            for (Map<String, Object> c : in.connections) {
                String number = str(c.get("devicenumber"));
                Map<String, Object> d = byNumber.get(number);
                if (d == null) continue;
                long from = lng(c.get("connectedat")), last = lng(c.get("lastseenat"));
                boolean open = in.now - last <= in.connectionGapMs;
                Row row = sc.createRow(cr++);
                link(row.createCell(0), nonBlank(str(d.get("description")), number), in.baseUrl + "/devices/" + number, st);
                text(row.createCell(1), number, st);
                Integer gid = intOf(d.get("groupid"));
                text(row.createCell(2), gid == null ? "Sin carpeta" : paths.getOrDefault(gid, ""), st);
                date(row.createCell(3), from, in.zone, st);
                if (open) text(row.createCell(4), "", st); else date(row.createCell(4), last, in.zone, st);
                Cell dur = row.createCell(5);
                dur.setCellValue(Math.round(((open ? in.now : last) - from) / 36_000.0) / 100.0);
                text(row.createCell(6), open ? "Conectado ahora" : "Cerrada", open ? st.ok : st.plain);
            }
            finish(sc, ch.length, cr);

            // --- Resumen -------------------------------------------------------------------------------------------
            Row t = summary.createRow(0);
            Cell title = t.createCell(0);
            title.setCellValue("DallyControl — relación de dispositivos");
            title.setCellStyle(st.title);
            List<String[]> kvl = new ArrayList<>();
            kvl.add(new String[]{"Alcance", in.scopeLabel});
            kvl.addAll(FleetInventory.headline(inv.summary));
            kvl.add(new String[]{"Conexiones desde", fmt(in.from, in.zone)});
            kvl.add(new String[]{"Conexiones hasta", fmt(in.to, in.zone)});
            kvl.add(new String[]{"Generado", fmt(in.now, in.zone)});
            kvl.add(new String[]{"Consola", in.baseUrl});
            String[][] kv = kvl.toArray(new String[0][]);
            int sr = 2;
            for (String[] p : kv) {
                Row row = summary.createRow(sr++);
                Cell k = row.createCell(0);
                k.setCellValue(p[0]);
                k.setCellStyle(st.head);
                if ("Consola".equals(p[0])) link(row.createCell(1), p[1], p[1], st);
                else if (p[1] != null && p[1].matches("\\d+")) row.createCell(1).setCellValue(Double.parseDouble(p[1]));
                else text(row.createCell(1), p[1], st);
            }
            sr++;
            Row hr = summary.createRow(sr++);
            String[] sh = {"Hoja", "Contenido"};
            for (int i = 0; i < sh.length; i++) { Cell c = hr.createCell(i); c.setCellValue(sh[i]); c.setCellStyle(st.head); }
            String[][] sheets = {
                    {"Dispositivos", "Cada dispositivo con su carpeta, política, estado de conexión (y minutos desde su último reporte), batería, almacenamiento, red, identificadores y última ubicación. El nombre abre su página en la consola."},
                    {"Carpetas", "Cada carpeta con su política y cuántos dispositivos tiene (con y sin subcarpetas)."},
                    {"Conexiones", "Cada periodo en que un dispositivo estuvo reportándose; una pausa de más de " + in.connectionGapMs / 60_000L + " minutos lo cierra."},
            };
            for (String[] s : sheets) {
                Row row = summary.createRow(sr++);
                Hyperlink h = wb.getCreationHelper().createHyperlink(HyperlinkType.DOCUMENT);
                h.setAddress("'" + s[0] + "'!A1");
                Cell c = row.createCell(0);
                c.setCellValue(s[0]);
                c.setHyperlink(h);
                c.setCellStyle(st.link);
                text(row.createCell(1), s[1], st);
            }
            // Breakdowns: how the exported devices split by Android version, model and agent version.
            String[][] parts = {{"byAndroid", "Por versión de Android"}, {"byModel", "Por modelo"}, {"byAgent", "Por versión del agente"}};
            for (String[] part : parts) {
                sr++;
                Row bh = summary.createRow(sr++);
                Cell b0 = bh.createCell(0); b0.setCellValue(part[1]); b0.setCellStyle(st.head);
                Cell b1 = bh.createCell(1); b1.setCellValue("Dispositivos"); b1.setCellStyle(st.head);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> list = (List<Map<String, Object>>) inv.summary.get(part[0]);
                for (Map<String, Object> e : list) {
                    Row row = summary.createRow(sr++);
                    text(row.createCell(0), str(e.get("label")), st);
                    row.createCell(1).setCellValue(((Number) e.get("count")).doubleValue());
                }
            }
            summary.setColumnWidth(0, 46 * 256);
            summary.setColumnWidth(1, 110 * 256);
            wb.write(out);
        }
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private static final class Styles {
        final CellStyle head, plain, link, date, ok, warn, title;

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
            Font lf = wb.createFont();
            lf.setUnderline(Font.U_SINGLE);
            lf.setColor(IndexedColors.BLUE.getIndex());
            link = wb.createCellStyle();
            link.setFont(lf);
            CreationHelper ch = wb.getCreationHelper();
            date = wb.createCellStyle();
            date.setDataFormat(ch.createDataFormat().getFormat("yyyy-mm-dd hh:mm"));
            Font green = wb.createFont();
            green.setColor(IndexedColors.GREEN.getIndex());
            ok = wb.createCellStyle();
            ok.setFont(green);
            Font orange = wb.createFont();
            orange.setColor(IndexedColors.ORANGE.getIndex());
            warn = wb.createCellStyle();
            warn.setFont(orange);
            Font big = wb.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 14);
            title = wb.createCellStyle();
            title.setFont(big);
        }
    }

    private static void header(Sheet s, String[] names, Styles st) {
        Row h = s.createRow(0);
        for (int i = 0; i < names.length; i++) {
            Cell c = h.createCell(i);
            c.setCellValue(names[i]);
            c.setCellStyle(st.head);
        }
        s.createFreezePane(1, 1);
    }

    private static void finish(Sheet s, int cols, int rows) {
        if (rows > 1) s.setAutoFilter(new CellRangeAddress(0, rows - 1, 0, cols - 1));
        for (int i = 0; i < cols; i++) s.setColumnWidth(i, Math.min(60, Math.max(12, width(s, i, rows))) * 256);
    }

    private static int width(Sheet s, int col, int rows) {
        int w = 0;
        for (int r = 0; r < Math.min(rows, 200); r++) {
            Row row = s.getRow(r);
            Cell c = row == null ? null : row.getCell(col);
            if (c != null) w = Math.max(w, c.toString().length() + 2);
        }
        return w;
    }

    private static void text(Cell c, String v, Styles st) {
        text(c, v, st.plain);
    }

    private static void text(Cell c, String v, CellStyle style) {
        c.setCellValue(v == null ? "" : v);
        c.setCellStyle(style);
    }

    private static void link(Cell c, String label, String url, Styles st) {
        c.setCellValue(label == null ? "" : label);
        if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) {
            Hyperlink h = c.getSheet().getWorkbook().getCreationHelper().createHyperlink(HyperlinkType.URL);
            h.setAddress(url.replace(" ", "%20"));
            c.setHyperlink(h);
            c.setCellStyle(st.link);
        }
    }

    private static void date(Cell c, long epochMs, ZoneId zone, Styles st) {
        if (epochMs <= 0) return;
        c.setCellValue(Date.from(Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDateTime().atZone(ZoneId.systemDefault()).toInstant()));
        c.setCellStyle(st.date);
    }

    private static String fmt(long epochMs, ZoneId zone) {
        return epochMs <= 0 ? "" : java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(epochMs).atZone(zone));
    }

    private static String path(DeviceGroupView g, Map<Integer, DeviceGroupView> byId) {
        StringBuilder sb = new StringBuilder(g.getName());
        DeviceGroupView p = g.getParentId() == null ? null : byId.get(g.getParentId());
        for (int i = 0; p != null && i < 32; i++) {
            sb.insert(0, p.getName() + " / ");
            p = p.getParentId() == null ? null : byId.get(p.getParentId());
        }
        return sb.toString();
    }

    private static List<Integer> subtree(int id, Map<Integer, List<Integer>> children) {
        List<Integer> out = new ArrayList<>();
        List<Integer> stack = new ArrayList<>();
        stack.add(id);
        while (!stack.isEmpty() && out.size() < 10_000) {
            int x = stack.remove(stack.size() - 1);
            if (out.contains(x)) continue;
            out.add(x);
            stack.addAll(children.getOrDefault(x, new ArrayList<>()));
        }
        return out;
    }

    /** The set of a folder and all its descendants, for {@link Input#onlyGroups}. */
    public static Set<Integer> branch(int id, Collection<DeviceGroupView> groups) {
        Map<Integer, List<Integer>> children = new HashMap<>();
        for (DeviceGroupView g : groups) children.computeIfAbsent(g.getParentId(), k -> new ArrayList<>()).add(g.getId());
        return new java.util.HashSet<>(subtree(id, children));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String nonBlank(String a, String b) {
        return a != null && !a.trim().isEmpty() ? a : b == null ? "" : b;
    }

    private static long lng(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    private static Integer intOf(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : null;
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
