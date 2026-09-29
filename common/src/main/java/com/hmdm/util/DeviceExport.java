package com.hmdm.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * The fleet as an Excel workbook (Resumen, Dispositivos, Carpetas, Conexiones): device detail with links to the
 * console and to the map, folders with their policy, and the connection history (periods each device kept checking
 * in). Dates are real Excel dates in the given time zone.
 */
public final class DeviceExport {
    /** Same as the console's "online" (ui/status.ts ONLINE_WINDOW_MS). */
    public static final long ONLINE_WINDOW_MS = 10 * 60_000L;

    private static final ObjectMapper JSON = new ObjectMapper();

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
    }

    private DeviceExport() {}

    public static void write(Input in, OutputStream out) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles st = new Styles(wb);
            Map<Integer, DeviceGroupView> byId = new HashMap<>();
            for (DeviceGroupView g : in.groups) byId.put(g.getId(), g);
            Map<Integer, String> paths = new HashMap<>();
            for (DeviceGroupView g : in.groups) paths.put(g.getId(), path(g, byId));

            List<Map<String, Object>> devices = new ArrayList<>();
            for (Map<String, Object> d : in.devices) {
                Integer gid = intOf(d.get("groupid"));
                if (in.onlyGroups == null || gid != null && in.onlyGroups.contains(gid)) devices.add(d);
            }
            Map<String, Map<String, Object>> byNumber = new LinkedHashMap<>();
            for (Map<String, Object> d : devices) byNumber.put(str(d.get("number")), d);

            Sheet summary = wb.createSheet("Resumen");
            Sheet sd = wb.createSheet("Dispositivos");
            Sheet sf = wb.createSheet("Carpetas");
            Sheet sc = wb.createSheet("Conexiones");

            // --- Dispositivos -------------------------------------------------------------------------------------
            String[] dh = {"Nombre", "Número", "Carpeta", "Política", "Estado", "Última conexión", "Conectado desde",
                    "Modelo", "Fabricante", "Android", "Agente", "Serie", "IMEI", "MAC Wi-Fi", "Teléfono", "SIM",
                    "Batería %", "Cargando", "Quiosco", "Modo de conexión", "Ubicación", "Hora de la ubicación", "Inscrito"};
            header(sd, dh, st);
            Map<String, Long> openSince = new HashMap<>();
            for (Map<String, Object> c : in.connections) {
                long last = lng(c.get("lastseenat"));
                if (in.now - last <= in.connectionGapMs) openSince.put(str(c.get("devicenumber")), lng(c.get("connectedat")));
            }
            int r = 1, online = 0;
            for (Map<String, Object> d : devices) {
                JsonNode tel = parse(str(d.get("telemetry")));
                JsonNode hw = tel.path("hardware"), idn = tel.path("identity"), dyn = tel.path("dynamic");
                String number = str(d.get("number"));
                String name = nonBlank(str(d.get("description")), number);
                long last = lng(d.get("lastupdate"));
                boolean on = last > 0 && in.now - last <= ONLINE_WINDOW_MS;
                if (on) online++;
                Row row = sd.createRow(r++);
                int c = 0;
                link(row.createCell(c++), name, in.baseUrl + "/devices/" + number, st);
                text(row.createCell(c++), number, st);
                Integer gid = intOf(d.get("groupid"));
                if (gid != null) link(row.createCell(c++), paths.getOrDefault(gid, ""), in.baseUrl + "/devices?group=" + gid, st);
                else text(row.createCell(c++), "Sin carpeta", st);
                text(row.createCell(c++), str(d.get("configurationname")), st);
                text(row.createCell(c++), on ? "En línea" : "Sin conexión", on ? st.ok : st.warn);
                date(row.createCell(c++), last, in.zone, st);
                date(row.createCell(c++), openSince.getOrDefault(number, 0L), in.zone, st);
                text(row.createCell(c++), hw.path("model").asText(""), st);
                text(row.createCell(c++), hw.path("manufacturer").asText(""), st);
                text(row.createCell(c++), nonBlank(hw.path("osRelease").asText(""), str(d.get("androidrelease"))), st);
                text(row.createCell(c++), str(d.get("agentversion")), st);
                text(row.createCell(c++), idn.path("serial").asText(""), st);
                text(row.createCell(c++), join(idn.path("imei")), st);
                text(row.createCell(c++), idn.path("wifiMac").asText(""), st);
                text(row.createCell(c++), phones(dyn.path("sim"), idn.path("phoneNumber")), st);
                text(row.createCell(c++), sim(dyn.path("sim")), st);
                Object battery = d.get("battery");
                if (battery instanceof Number && ((Number) battery).intValue() >= 0) row.createCell(c++).setCellValue(((Number) battery).intValue());
                else c++;
                text(row.createCell(c++), yesNo(d.get("charging")), st);
                text(row.createCell(c++), yesNo(d.get("kioskactive")), st);
                text(row.createCell(c++), "alwaysOn".equals(str(d.get("powermode"))) ? "Siempre conectado"
                        : "adaptive".equals(str(d.get("powermode"))) ? "Ahorro de batería" : "", st);
                JsonNode loc = dyn.path("location");
                if (loc.hasNonNull("lat") && loc.hasNonNull("lon")) {
                    String ll = String.format(java.util.Locale.ROOT, "%.6f,%.6f", loc.get("lat").asDouble(), loc.get("lon").asDouble());
                    link(row.createCell(c++), ll, "https://www.google.com/maps?q=" + ll, st);
                    date(row.createCell(c++), loc.path("capturedAt").asLong(0), in.zone, st);
                } else {
                    c += 2;
                }
                date(row.createCell(c), lng(d.get("enrolltime")), in.zone, st);
            }
            finish(sd, dh.length, r);

            // --- Carpetas ------------------------------------------------------------------------------------------
            String[] fh = {"Carpeta", "Política propia", "Política que aplica", "Dispositivos", "Con subcarpetas", "En línea"};
            header(sf, fh, st);
            Map<Integer, List<Integer>> children = new HashMap<>();
            for (DeviceGroupView g : in.groups) children.computeIfAbsent(g.getParentId(), k -> new ArrayList<>()).add(g.getId());
            Map<Integer, int[]> counts = new HashMap<>(); // direct, online
            for (Map<String, Object> d : devices) {
                Integer gid = intOf(d.get("groupid"));
                if (gid == null) continue;
                int[] cnt = counts.computeIfAbsent(gid, k -> new int[2]);
                cnt[0]++;
                long last = lng(d.get("lastupdate"));
                if (last > 0 && in.now - last <= ONLINE_WINDOW_MS) cnt[1]++;
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
            String[][] kv = {
                    {"Alcance", in.scopeLabel},
                    {"Dispositivos", String.valueOf(devices.size())},
                    {"En línea (últimos 10 min)", String.valueOf(online)},
                    {"Sin conexión", String.valueOf(devices.size() - online)},
                    {"Conexiones desde", fmt(in.from, in.zone)},
                    {"Conexiones hasta", fmt(in.to, in.zone)},
                    {"Generado", fmt(in.now, in.zone)},
                    {"Consola", in.baseUrl},
            };
            int sr = 2;
            for (String[] p : kv) {
                Row row = summary.createRow(sr++);
                Cell k = row.createCell(0);
                k.setCellValue(p[0]);
                k.setCellStyle(st.head);
                if ("Consola".equals(p[0])) link(row.createCell(1), p[1], p[1], st);
                else text(row.createCell(1), p[1], st);
            }
            sr++;
            Row hr = summary.createRow(sr++);
            String[] sh = {"Hoja", "Contenido"};
            for (int i = 0; i < sh.length; i++) { Cell c = hr.createCell(i); c.setCellValue(sh[i]); c.setCellStyle(st.head); }
            String[][] sheets = {
                    {"Dispositivos", "Cada dispositivo con su carpeta, política, estado, identificadores y última ubicación. El nombre abre su página en la consola."},
                    {"Carpetas", "Cada carpeta con su política y cuántos dispositivos tiene (con y sin subcarpetas)."},
                    {"Conexiones", "Cada periodo en que un dispositivo estuvo reportándose; una pausa de más de 20 minutos lo cierra."},
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
            summary.setColumnWidth(0, 30 * 256);
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

    private static JsonNode parse(String json) {
        try {
            return json == null ? JSON.createObjectNode() : JSON.readTree(json);
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }

    private static String join(JsonNode arr) {
        if (!arr.isArray()) return arr.asText("");
        List<String> v = new ArrayList<>();
        for (JsonNode n : arr) v.add(n.asText());
        return String.join(", ", v);
    }

    private static String phones(JsonNode sim, JsonNode identityPhones) {
        List<String> v = new ArrayList<>();
        for (JsonNode s : sim.path("slots")) if (s.hasNonNull("number")) v.add(s.get("number").asText());
        if (v.isEmpty()) for (JsonNode n : identityPhones) v.add(n.asText());
        return String.join(", ", v);
    }

    private static String sim(JsonNode sim) {
        String state = sim.path("state").asText("");
        if (state.isEmpty()) return "";
        if ("absent".equals(state)) return "Sin SIM";
        if ("locked".equals(state)) return "Bloqueada (PIN/PUK)";
        List<String> v = new ArrayList<>();
        for (JsonNode s : sim.path("slots")) if (s.hasNonNull("carrier")) v.add(s.get("carrier").asText());
        return v.isEmpty() ? ("ready".equals(state) ? "Lista" : state) : String.join(", ", v);
    }

    private static String yesNo(Object o) {
        return o instanceof Boolean ? ((Boolean) o ? "Sí" : "No") : "";
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
