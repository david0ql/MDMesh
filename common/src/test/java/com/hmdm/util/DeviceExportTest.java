package com.hmdm.util;

import com.hmdm.persistence.domain.DeviceGroupView;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class DeviceExportTest {
    private static final long NOW = 1_790_700_000_000L;
    private static final long GIB = 1024L * 1024 * 1024;

    private static DeviceGroupView group(int id, Integer parent, String name, String cfg) {
        DeviceGroupView g = new DeviceGroupView();
        g.setId(id); g.setParentId(parent); g.setName(name); g.setConfigurationName(cfg); g.setEffectiveConfigurationName(cfg == null ? "Preventa" : cfg);
        return g;
    }

    /** A device as listDeviceExportRows returns it, with the census a current agent reports. */
    private static Map<String, Object> device(String number, String name, Integer group, long last) {
        Map<String, Object> d = new HashMap<>();
        d.put("number", number); d.put("description", name); d.put("groupid", group); d.put("lastupdate", last);
        d.put("stateat", last);
        d.put("configurationname", "Preventa"); d.put("battery", 80); d.put("charging", false);
        d.put("kioskactive", true); d.put("powermode", "alwaysOn"); d.put("agentversion", "0.9.8");
        d.put("telemetry", "{\"hardware\":{\"model\":\"moto g31\",\"manufacturer\":\"motorola\",\"osRelease\":\"11\","
                + "\"securityPatch\":\"2026-08-01\",\"totalStorageBytes\":" + (64 * GIB) + "},"
                + "\"identity\":{\"serial\":\"ZY22H4\",\"imei\":[\"356938035643809\"],\"wifiMac\":\"AA:BB:CC:DD:EE:FF\"},"
                + "\"dynamic\":{\"batteryPct\":80,\"batteryHealth\":\"good\",\"batteryTempC\":31.26,"
                + "\"freeStorageBytes\":" + (16 * GIB) + ",\"networkType\":\"wifi\",\"wifiSsid\":\"\\\"Bodega-5G\\\"\","
                + "\"wifiRssi\":-60,\"ipAddress\":\"10.0.0.7\","
                + "\"sim\":{\"state\":\"ready\",\"slots\":[{\"slot\":0,\"carrier\":\"Claro\",\"number\":\"+573001112233\"}]},"
                + "\"location\":{\"lat\":4.6,\"lon\":-74.08,\"capturedAt\":1790000000000}}}");
        return d;
    }

    private static DeviceExport.Input input() {
        DeviceExport.Input in = new DeviceExport.Input();
        in.baseUrl = "https://mdm.example.com";
        in.now = NOW;
        in.from = NOW - 86_400_000L;
        in.to = NOW;
        in.groups.add(group(1, null, "Colombia", "Preventa"));
        in.groups.add(group(2, 1, "Bogotá", null));
        in.groups.add(group(3, null, "Ecuador", null));
        in.devices.add(device("dev-1", "ZY22H4", 2, NOW - 60_000));
        in.devices.add(device("dev-2", "Quito-1", 3, NOW - 3_600_000));
        Map<String, Object> s1 = new HashMap<>();
        s1.put("devicenumber", "dev-1"); s1.put("connectedat", NOW - 7_200_000L); s1.put("lastseenat", NOW - 60_000L);
        Map<String, Object> s2 = new HashMap<>();
        s2.put("devicenumber", "dev-1"); s2.put("connectedat", NOW - 20_000_000L); s2.put("lastseenat", NOW - 10_000_000L);
        in.connections.add(s2); in.connections.add(s1);
        return in;
    }

    private static XSSFWorkbook workbook(DeviceExport.Input in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DeviceExport.write(in, out);
        return new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()));
    }

    /** Header label → column index of the Dispositivos sheet. */
    private static Map<String, Integer> headers(Sheet d) {
        Map<String, Integer> h = new LinkedHashMap<>();
        for (Cell c : d.getRow(0)) h.put(c.getStringCellValue(), c.getColumnIndex());
        return h;
    }

    private static Cell cell(Sheet d, int row, String header) {
        Integer i = headers(d).get(header);
        assertNotNull("column " + header, i);
        return d.getRow(row).getCell(i);
    }

    /** Label → value of the Resumen sheet's two columns. */
    private static Map<String, String> resumen(XSSFWorkbook wb) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Row r : wb.getSheet("Resumen")) {
            Cell k = r.getCell(0), v = r.getCell(1);
            if (k == null || v == null) continue;
            m.putIfAbsent(k.getStringCellValue(), v.getCellType() == CellType.NUMERIC
                    ? String.valueOf((long) v.getNumericCellValue()) : v.getStringCellValue());
        }
        return m;
    }

    @Test
    public void workbook_has_four_sheets_links_and_a_folder_filter() throws Exception {
        DeviceExport.Input in = input();
        in.onlyGroups = DeviceExport.branch(1, in.groups);
        try (XSSFWorkbook wb = workbook(in)) {
            assertEquals("Resumen", wb.getSheetName(0));
            assertEquals("Dispositivos", wb.getSheetName(1));
            assertEquals("Carpetas", wb.getSheetName(2));
            assertEquals("Conexiones", wb.getSheetName(3));
            Sheet d = wb.getSheet("Dispositivos");
            assertEquals("only Colombia's branch", 1, d.getLastRowNum());
            assertEquals("ZY22H4", cell(d, 1, "Nombre").getStringCellValue());
            assertEquals("https://mdm.example.com/devices/dev-1", cell(d, 1, "Nombre").getHyperlink().getAddress());
            assertEquals("Colombia / Bogotá", cell(d, 1, "Carpeta").getStringCellValue());
            assertEquals("En línea", cell(d, 1, "Estado").getStringCellValue());
            assertEquals("356938035643809", cell(d, 1, "IMEI").getStringCellValue());
            assertEquals("AA:BB:CC:DD:EE:FF", cell(d, 1, "MAC Wi-Fi").getStringCellValue());
            assertEquals("+573001112233", cell(d, 1, "Teléfono").getStringCellValue());
            assertEquals("Claro", cell(d, 1, "SIM").getStringCellValue());
            assertTrue(cell(d, 1, "Ubicación").getHyperlink().getAddress().startsWith("https://www.google.com/maps?q=4.600000,-74.080000"));
            Sheet f = wb.getSheet("Carpetas");
            assertEquals("Colombia", f.getRow(1).getCell(0).getStringCellValue());
            assertEquals("device counted with sub-folders", 1.0, f.getRow(1).getCell(4).getNumericCellValue(), 0);
            Sheet c = wb.getSheet("Conexiones");
            assertEquals(2, c.getLastRowNum());
            assertEquals("Cerrada", c.getRow(1).getCell(6).getStringCellValue());
            assertEquals("Conectado ahora", c.getRow(2).getCell(6).getStringCellValue());
            assertEquals("2 h session", 2.0, c.getRow(2).getCell(5).getNumericCellValue(), 0.02);
            boolean linked = false;
            for (Row r : wb.getSheet("Resumen")) {
                Cell k = r.getCell(0);
                if (k != null && "Conexiones".equals(k.getStringCellValue()) && k.getHyperlink() != null) linked = true;
            }
            assertTrue("summary links to its sheets", linked);
        }
    }

    @Test
    public void devices_sheet_has_every_fleet_inventory_column() throws Exception {
        try (XSSFWorkbook wb = workbook(input())) {
            List<String> wanted = Arrays.asList("Nombre", "Número", "Carpeta", "Política", "Estado",
                    "Min. desde el último reporte", "Última conexión", "Última muestra", "Modelo", "Fabricante", "Android",
                    "Parche de seguridad", "Agente", "Serie", "IMEI", "MAC Wi-Fi", "Batería %", "Cargando", "Salud batería",
                    "Temperatura °C", "Libre GB", "Total GB", "% usado", "Red", "Señal", "SSID/Operador", "Quiosco",
                    "Modo de conexión", "Inscrito", "Alertas");
            assertTrue("missing " + wanted + " in " + headers(wb.getSheet("Dispositivos")).keySet(),
                    headers(wb.getSheet("Dispositivos")).keySet().containsAll(wanted));
        }
    }

    @Test
    public void battery_storage_android_connection_and_network_carry_the_reported_values() throws Exception {
        try (XSSFWorkbook wb = workbook(input())) {
            Sheet d = wb.getSheet("Dispositivos");
            // dev-2 "Quito-1" sorts as given (input order): row 1 = dev-1, row 2 = dev-2
            assertEquals(80.0, cell(d, 1, "Batería %").getNumericCellValue(), 0);
            assertEquals("No", cell(d, 1, "Cargando").getStringCellValue());
            assertEquals("Buena", cell(d, 1, "Salud batería").getStringCellValue());
            assertEquals(31.3, cell(d, 1, "Temperatura °C").getNumericCellValue(), 0.001);
            assertEquals(16.0, cell(d, 1, "Libre GB").getNumericCellValue(), 0.001);
            assertEquals(64.0, cell(d, 1, "Total GB").getNumericCellValue(), 0.001);
            assertEquals(75.0, cell(d, 1, "% usado").getNumericCellValue(), 0);
            assertEquals("11", cell(d, 1, "Android").getStringCellValue());
            assertEquals("2026-08-01", cell(d, 1, "Parche de seguridad").getStringCellValue());
            assertEquals("0.9.8", cell(d, 1, "Agente").getStringCellValue());
            assertEquals("Wi-Fi", cell(d, 1, "Red").getStringCellValue());
            assertEquals("Buena (3/4) -60 dBm", cell(d, 1, "Señal").getStringCellValue());
            assertEquals("the SSID without Android's quotes", "Bodega-5G", cell(d, 1, "SSID/Operador").getStringCellValue());
            assertEquals("Sí", cell(d, 1, "Quiosco").getStringCellValue());
            assertEquals("Preventa", cell(d, 1, "Política").getStringCellValue());
            assertEquals("En línea", cell(d, 1, "Estado").getStringCellValue());
            assertEquals(1.0, cell(d, 1, "Min. desde el último reporte").getNumericCellValue(), 0);
            assertEquals("Sin conexión", cell(d, 2, "Estado").getStringCellValue());
            assertEquals(60.0, cell(d, 2, "Min. desde el último reporte").getNumericCellValue(), 0);
            assertNotNull("a real Excel date", cell(d, 1, "Última muestra").getDateCellValue());
        }
    }

    @Test
    public void summary_names_the_real_online_window_and_counts_the_alerts() throws Exception {
        DeviceExport.Input in = input();
        Map<String, Object> low = device("dev-3", "Bajo", 3, NOW - 10 * 86_400_000L);
        low.put("battery", 9);
        low.put("agentversion", "0.8.2");
        low.put("telemetry", "{\"hardware\":{\"model\":\"A04\",\"manufacturer\":\"samsung\",\"osRelease\":\"13\",\"totalStorageBytes\":"
                + (32 * GIB) + "},\"dynamic\":{\"freeStorageBytes\":" + GIB + ",\"networkType\":\"cellular\","
                + "\"cellularOperator\":\"Tigo\",\"cellularSignalLevel\":1}}");
        in.devices.add(low);
        try (XSSFWorkbook wb = workbook(in)) {
            Map<String, String> r = resumen(wb);
            assertEquals("the label matches the 20-minute window the count uses", "1", r.get("En línea (últimos 20 min)"));
            assertFalse(r.keySet().toString(), r.keySet().stream().anyMatch(k -> k.contains("10 min")));
            assertEquals("3", r.get("Dispositivos"));
            assertEquals("2", r.get("Sin conexión"));
            assertEquals("1", r.get("Sin conexión > 7 días"));
            assertEquals("1", r.get("Batería baja (≤ 20 %, sin cargar)"));
            assertEquals("1", r.get("Almacenamiento casi lleno (≥ 90 % usado)"));
            assertEquals("1", r.get("Agente desactualizado (última: 0.9.8)"));
            assertEquals("2", r.get("11"));
            assertEquals("1", r.get("13"));
            assertEquals("1", r.get("samsung A04"));
            Sheet d = wb.getSheet("Dispositivos");
            assertEquals("Datos móviles", cell(d, 3, "Red").getStringCellValue());
            assertEquals("Mala (1/4)", cell(d, 3, "Señal").getStringCellValue());
            assertEquals("Tigo", cell(d, 3, "SSID/Operador").getStringCellValue());
            assertEquals("Batería baja; Almacenamiento casi lleno; Sin conexión > 7 d; Agente desactualizado",
                    cell(d, 3, "Alertas").getStringCellValue());
        }
    }

    @Test
    public void only_the_given_device_numbers_are_exported() throws Exception {
        DeviceExport.Input in = input();
        in.onlyNumbers = new HashSet<>(Arrays.asList("dev-2", "unknown"));
        try (XSSFWorkbook wb = workbook(in)) {
            Sheet d = wb.getSheet("Dispositivos");
            assertEquals(1, d.getLastRowNum());
            assertEquals("Quito-1", cell(d, 1, "Nombre").getStringCellValue());
            assertEquals("its connections only", 0, wb.getSheet("Conexiones").getLastRowNum());
            assertEquals("1", resumen(wb).get("Dispositivos"));
        }
    }

    @Test
    public void a_device_with_its_live_channel_open_is_online() throws Exception {
        DeviceExport.Input in = input();
        in.live = new HashSet<>(Arrays.asList("dev-2"));
        try (XSSFWorkbook wb = workbook(in)) {
            assertEquals("En línea", cell(wb.getSheet("Dispositivos"), 2, "Estado").getStringCellValue());
            assertEquals("2", resumen(wb).get("En línea (últimos 20 min)"));
        }
    }

    @Test
    public void an_agent_that_reports_no_census_leaves_cells_empty() throws Exception {
        DeviceExport.Input in = input();
        Map<String, Object> old = new HashMap<>();
        old.put("number", "old-1"); old.put("groupid", null); old.put("lastupdate", 0L);
        in.devices.add(old);
        try (XSSFWorkbook wb = workbook(in)) {
            Sheet d = wb.getSheet("Dispositivos");
            assertEquals("old-1", cell(d, 3, "Nombre").getStringCellValue());
            assertEquals("Sin carpeta", cell(d, 3, "Carpeta").getStringCellValue());
            assertEquals("Sin conexión", cell(d, 3, "Estado").getStringCellValue());
            assertNull(cell(d, 3, "Batería %"));
            assertNull(cell(d, 3, "Libre GB"));
            assertNull(cell(d, 3, "Min. desde el último reporte"));
            assertEquals("Nunca ha reportado", cell(d, 3, "Alertas").getStringCellValue());
        }
    }

    // --- CSV ---------------------------------------------------------------------------------------------------

    private static String[] csvLines(DeviceExport.Input in, char sep) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DeviceExport.writeCsv(in, sep, out);
        byte[] b = out.toByteArray();
        assertEquals("UTF-8 BOM", (byte) 0xEF, b[0]);
        assertEquals((byte) 0xBB, b[1]);
        assertEquals((byte) 0xBF, b[2]);
        String s = new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
        assertTrue("CRLF rows", s.endsWith("\r\n"));
        return s.split("\r\n");
    }

    @Test
    public void csv_is_utf8_with_bom_and_has_the_sheet_columns_and_values() throws Exception {
        String[] lines = csvLines(input(), ';');
        assertEquals("header + 2 devices", 3, lines.length);
        List<String> head = Arrays.asList(lines[0].split(";", -1));
        List<String> row = Arrays.asList(lines[1].split(";", -1));
        assertEquals(FleetInventory.columns().size(), head.size());
        assertEquals(head.size(), row.size());
        assertEquals("ZY22H4", row.get(head.indexOf("Nombre")));
        assertEquals("Colombia / Bogotá", row.get(head.indexOf("Carpeta")));
        assertEquals("En línea", row.get(head.indexOf("Estado")));
        assertEquals("80", row.get(head.indexOf("Batería %")));
        assertEquals("16.0", row.get(head.indexOf("Libre GB")));
        assertEquals("64.0", row.get(head.indexOf("Total GB")));
        assertEquals("75", row.get(head.indexOf("% usado")));
        assertEquals("11", row.get(head.indexOf("Android")));
        assertEquals("Wi-Fi", row.get(head.indexOf("Red")));
        assertEquals("Bodega-5G", row.get(head.indexOf("SSID/Operador")));
        assertEquals("Buena", row.get(head.indexOf("Salud batería")));
        assertEquals("+573001112233", row.get(head.indexOf("Teléfono")));
        assertTrue("dates in the export's zone", row.get(head.indexOf("Última muestra")).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"));
    }

    @Test
    public void csv_separator_is_configurable_and_fields_containing_it_are_quoted() throws Exception {
        String[] lines = csvLines(input(), ',');
        assertTrue(lines[0].startsWith("Nombre,Número,Carpeta,"));
        assertTrue("the location has a comma, so it is quoted", lines[1].contains("\"4.600000,-74.080000\""));
        assertTrue(csvLines(input(), '\t')[0].startsWith("Nombre\tNúmero\t"));
        assertEquals(',', ReportExport.separator("comma"));
        assertEquals('\t', ReportExport.separator("tab"));
        assertEquals('|', ReportExport.separator("|"));
        assertEquals(';', ReportExport.separator(null));
        assertEquals(';', ReportExport.separator("x"));
    }

    @Test
    public void csv_text_that_a_spreadsheet_would_run_as_a_formula_is_neutralised() throws Exception {
        DeviceExport.Input in = input();
        in.devices.get(0).put("description", "=HYPERLINK(\"http://x\")");
        String[] lines = csvLines(in, ';');
        assertTrue(lines[1], lines[1].startsWith("\"'=HYPERLINK(\"\"http://x\"\")\";"));
        assertEquals("+573001112233", ReportExport.inert("+573001112233"));
        assertEquals("-74.08", ReportExport.inert("-74.08"));
        assertEquals("'+cmd|x", ReportExport.inert("+cmd|x"));
    }

    @Test
    public void versions_compare_numerically() {
        assertTrue(FleetInventory.compareVersions("0.9.8", "0.10.0") < 0);
        assertTrue(FleetInventory.compareVersions("0.9.8", "0.9.8") == 0);
        assertTrue(FleetInventory.compareVersions("1.0", "0.9.8") > 0);
    }

    /** A discarding stream that counts, so the test measures the export and not a byte array of its output. */
    private static final class Count extends java.io.OutputStream {
        long n;
        @Override public void write(int b) { n++; }
        @Override public void write(byte[] b, int off, int len) { n += len; }
    }

    @Test
    public void five_thousand_devices_with_a_month_of_sessions_export_in_seconds() throws Exception {
        DeviceExport.Input in = new DeviceExport.Input();
        in.baseUrl = "https://mdm.example.com";
        in.now = NOW;
        in.from = NOW - 30 * 86_400_000L;
        in.to = NOW;
        for (int g = 1; g <= 50; g++) in.groups.add(group(g, g > 10 ? (g % 10) + 1 : null, "Carpeta " + g, null));
        for (int i = 0; i < 5000; i++) {
            in.devices.add(device("dev-" + i, "Equipo " + i, (i % 50) + 1, NOW - (i % 400) * 60_000L));
            for (int k = 0; k < 20; k++) {
                Map<String, Object> c = new HashMap<>();
                c.put("devicenumber", "dev-" + i);
                c.put("connectedat", NOW - (k + 1) * 86_400_000L);
                c.put("lastseenat", NOW - (k + 1) * 86_400_000L + 3_600_000L);
                in.connections.add(c);
            }
        }
        Count xlsx = new Count();
        long t0 = System.nanoTime();
        DeviceExport.write(in, xlsx);
        long xlsxMs = (System.nanoTime() - t0) / 1_000_000;
        Count csv = new Count();
        t0 = System.nanoTime();
        DeviceExport.writeCsv(in, ';', csv);
        long csvMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("5000 devices, 100000 sessions: xlsx " + xlsxMs + " ms (" + xlsx.n / 1024 + " KB), csv "
                + csvMs + " ms (" + csv.n / 1024 + " KB)");
        assertTrue("xlsx took " + xlsxMs + " ms", xlsxMs < 30_000);
        assertTrue("csv took " + csvMs + " ms", csvMs < 10_000);
        assertTrue(xlsx.n > 100_000 && csv.n > 100_000);
    }

    @Test
    public void a_long_sheet_keeps_every_row_and_its_column_widths() throws Exception {
        DeviceExport.Input in = input();
        for (int i = 0; i < 600; i++) in.devices.add(device("n-" + i, "Equipo con un nombre largo " + i, 3, NOW - 60_000));
        try (XSSFWorkbook wb = workbook(in)) {
            Sheet d = wb.getSheet("Dispositivos");
            assertEquals(602, d.getLastRowNum());
            assertEquals("Equipo con un nombre largo 599", cell(d, 602, "Nombre").getStringCellValue());
            assertEquals("80.0", String.valueOf(cell(d, 602, "Batería %").getNumericCellValue()));
            assertTrue("width from the first rows", d.getColumnWidth(0) > 20 * 256);
            assertEquals("602", resumen(wb).get("Dispositivos"));
        }
    }
}
