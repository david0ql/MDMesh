package com.hmdm.util;

import com.hmdm.persistence.domain.DeviceGroupView;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class DeviceExportTest {
    private static DeviceGroupView group(int id, Integer parent, String name, String cfg) {
        DeviceGroupView g = new DeviceGroupView();
        g.setId(id); g.setParentId(parent); g.setName(name); g.setConfigurationName(cfg); g.setEffectiveConfigurationName(cfg == null ? "Preventa" : cfg);
        return g;
    }

    private static Map<String, Object> device(String number, String name, Integer group, long last) {
        Map<String, Object> d = new HashMap<>();
        d.put("number", number); d.put("description", name); d.put("groupid", group); d.put("lastupdate", last);
        d.put("configurationname", "Preventa"); d.put("battery", 80); d.put("kioskactive", true); d.put("powermode", "alwaysOn");
        d.put("telemetry", "{\"hardware\":{\"model\":\"moto g31\",\"manufacturer\":\"motorola\",\"osRelease\":\"11\"},"
                + "\"identity\":{\"serial\":\"ZY22H4\",\"imei\":[\"356938035643809\"],\"wifiMac\":\"AA:BB:CC:DD:EE:FF\"},"
                + "\"dynamic\":{\"sim\":{\"state\":\"ready\",\"slots\":[{\"slot\":0,\"carrier\":\"Claro\",\"number\":\"+573001112233\"}]},"
                + "\"location\":{\"lat\":4.6,\"lon\":-74.08,\"capturedAt\":1790000000000}}}");
        return d;
    }

    @Test
    public void workbook_has_four_sheets_links_and_a_folder_filter() throws Exception {
        long now = 1_790_700_000_000L;
        DeviceExport.Input in = new DeviceExport.Input();
        in.baseUrl = "https://mdm.example.com";
        in.now = now;
        in.from = now - 86_400_000L;
        in.to = now;
        in.groups.add(group(1, null, "Colombia", "Preventa"));
        in.groups.add(group(2, 1, "Bogotá", null));
        in.groups.add(group(3, null, "Ecuador", null));
        in.devices.add(device("dev-1", "ZY22H4", 2, now - 60_000));
        in.devices.add(device("dev-2", "Quito-1", 3, now - 3_600_000));
        Map<String, Object> s1 = new HashMap<>();
        s1.put("devicenumber", "dev-1"); s1.put("connectedat", now - 7_200_000L); s1.put("lastseenat", now - 60_000L);
        Map<String, Object> s2 = new HashMap<>();
        s2.put("devicenumber", "dev-1"); s2.put("connectedat", now - 20_000_000L); s2.put("lastseenat", now - 10_000_000L);
        in.connections.add(s2); in.connections.add(s1);
        in.onlyGroups = DeviceExport.branch(1, in.groups);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DeviceExport.write(in, out);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            assertEquals("Resumen", wb.getSheetName(0));
            assertEquals("Dispositivos", wb.getSheetName(1));
            assertEquals("Carpetas", wb.getSheetName(2));
            assertEquals("Conexiones", wb.getSheetName(3));
            Sheet d = wb.getSheet("Dispositivos");
            assertEquals("only Colombia's branch", 1, d.getLastRowNum());
            Row r = d.getRow(1);
            assertEquals("ZY22H4", r.getCell(0).getStringCellValue());
            assertEquals("https://mdm.example.com/devices/dev-1", r.getCell(0).getHyperlink().getAddress());
            assertEquals("Colombia / Bogotá", r.getCell(2).getStringCellValue());
            assertEquals("En línea", r.getCell(4).getStringCellValue());
            assertEquals("356938035643809", r.getCell(12).getStringCellValue());
            assertEquals("AA:BB:CC:DD:EE:FF", r.getCell(13).getStringCellValue());
            assertEquals("+573001112233", r.getCell(14).getStringCellValue());
            assertEquals("Claro", r.getCell(15).getStringCellValue());
            assertTrue(r.getCell(20).getHyperlink().getAddress().startsWith("https://www.google.com/maps?q=4.600000,-74.080000"));
            Sheet f = wb.getSheet("Carpetas");
            assertEquals("Colombia", f.getRow(1).getCell(0).getStringCellValue());
            assertEquals("device counted with sub-folders", 1.0, f.getRow(1).getCell(4).getNumericCellValue(), 0);
            Sheet c = wb.getSheet("Conexiones");
            assertEquals(2, c.getLastRowNum());
            assertEquals("Cerrada", c.getRow(1).getCell(6).getStringCellValue());
            assertEquals("Conectado ahora", c.getRow(2).getCell(6).getStringCellValue());
            assertEquals("2 h session", 2.0, c.getRow(2).getCell(5).getNumericCellValue(), 0.02);
            assertNotNull("summary links to its sheets", wb.getSheet("Resumen").getRow(12).getCell(0).getHyperlink());
        }
    }
}
