package com.hmdm.util;

import com.hmdm.persistence.domain.ReportSchedule;
import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class ReportScheduleCalcTest {
    private static final ZoneId BOG = ZoneId.of("America/Bogota");
    private static final Set<String> KNOWN = new HashSet<>(Arrays.asList("inventory", "offline"));

    private static long at(int y, int mo, int d, int h, int mi) {
        return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, BOG).toInstant().toEpochMilli();
    }

    private static ReportSchedule schedule(String frequency, int hour, int minute) {
        ReportSchedule s = new ReportSchedule();
        s.setName("Inventario diario");
        s.setFrequency(frequency);
        s.setRunHour(hour);
        s.setRunMinute(minute);
        s.setTimezone("America/Bogota");
        s.setRecipients("ops@example.com");
        return s;
    }

    @Test
    public void daily_runs_today_when_the_time_is_still_ahead_else_tomorrow() {
        ReportSchedule s = schedule("daily", 7, 30);
        assertEquals(at(2026, 11, 27, 7, 30), ReportScheduleCalc.nextRun(s, at(2026, 11, 27, 6, 0)));
        assertEquals(at(2026, 11, 28, 7, 30), ReportScheduleCalc.nextRun(s, at(2026, 11, 27, 7, 30)));
        assertEquals(at(2026, 11, 28, 7, 30), ReportScheduleCalc.nextRun(s, at(2026, 11, 27, 18, 0)));
    }

    @Test
    public void the_time_is_local_to_the_schedules_zone() {
        ReportSchedule s = schedule("daily", 7, 0);
        s.setTimezone("Europe/Madrid");
        long next = ReportScheduleCalc.nextRun(s, at(2026, 11, 27, 6, 0)); // 12:00 in Madrid
        assertEquals(ZonedDateTime.of(2026, 11, 28, 7, 0, 0, 0, ZoneId.of("Europe/Madrid")).toInstant().toEpochMilli(), next);
    }

    @Test
    public void weekly_runs_on_its_day_of_the_week() {
        ReportSchedule s = schedule("weekly", 8, 0);
        s.setDayOfWeek(1); // Monday; 2026-11-27 is a Friday
        assertEquals(at(2026, 11, 30, 8, 0), ReportScheduleCalc.nextRun(s, at(2026, 11, 27, 9, 0)));
        assertEquals("same day, later that morning", at(2026, 11, 30, 8, 0), ReportScheduleCalc.nextRun(s, at(2026, 11, 30, 7, 0)));
        assertEquals("already ran: next week", at(2026, 12, 7, 8, 0), ReportScheduleCalc.nextRun(s, at(2026, 11, 30, 8, 0)));
    }

    @Test
    public void monthly_uses_the_last_day_of_a_shorter_month() {
        ReportSchedule s = schedule("monthly", 6, 0);
        s.setDayOfMonth(31);
        assertEquals("November has 30 days", at(2026, 11, 30, 6, 0), ReportScheduleCalc.nextRun(s, at(2026, 11, 27, 9, 0)));
        assertEquals(at(2026, 12, 31, 6, 0), ReportScheduleCalc.nextRun(s, at(2026, 11, 30, 6, 0)));
        assertEquals("February", at(2027, 2, 28, 6, 0), ReportScheduleCalc.nextRun(s, at(2027, 1, 31, 6, 0)));
        s.setDayOfMonth(1);
        assertEquals(at(2026, 12, 1, 6, 0), ReportScheduleCalc.nextRun(s, at(2026, 11, 27, 9, 0)));
    }

    @Test
    public void normalize_cleans_a_valid_schedule() {
        ReportSchedule s = schedule("Weekly", 7, 0);
        s.setName("  Inventario   semanal ");
        s.setDayOfWeek(5);
        s.setDayOfMonth(12);
        s.setFormat("CSV");
        s.setCsvSeparator("tab");
        s.setRecipients("Ops@Example.com; jefe@example.com,ops@example.com");
        assertNull(ReportScheduleCalc.normalize(s, KNOWN));
        assertEquals("Inventario semanal", s.getName());
        assertEquals("weekly", s.getFrequency());
        assertEquals("csv", s.getFormat());
        assertEquals("tab", s.getCsvSeparator());
        assertNull("a weekly schedule keeps no day of the month", s.getDayOfMonth());
        assertEquals("ops@example.com, jefe@example.com", s.getRecipients());
        assertEquals("inventory", s.getReport());
    }

    @Test
    public void normalize_explains_what_is_wrong() {
        ReportSchedule s = schedule("daily", 7, 0);
        s.setRecipients("no-es-correo");
        assertEquals("El correo \"no-es-correo\" no es válido.", ReportScheduleCalc.normalize(s, KNOWN));
        s.setRecipients(" ");
        assertEquals("Agrega al menos un destinatario.", ReportScheduleCalc.normalize(s, KNOWN));
        s.setRecipients("a@b.co");
        s.setName(" ");
        assertNotNull(ReportScheduleCalc.normalize(s, KNOWN));
        s.setName("x");
        s.setFrequency("hourly");
        assertNotNull(ReportScheduleCalc.normalize(s, KNOWN));
        s.setFrequency("weekly");
        assertEquals("Elige el día de la semana.", ReportScheduleCalc.normalize(s, KNOWN));
        s.setFrequency("monthly");
        s.setDayOfMonth(32);
        assertNotNull(ReportScheduleCalc.normalize(s, KNOWN));
        s.setFrequency("daily");
        s.setRunHour(24);
        assertEquals("La hora no es válida.", ReportScheduleCalc.normalize(s, KNOWN));
        s.setRunHour(7);
        s.setTimezone("Marte/Olympus");
        assertEquals("La zona horaria no es válida.", ReportScheduleCalc.normalize(s, KNOWN));
        s.setTimezone("America/Bogota");
        s.setReport("apps-catalog");
        assertEquals("Ese reporte no se puede programar.", ReportScheduleCalc.normalize(s, KNOWN));
        s.setReport("offline");
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 21; i++) many.append("u").append(i).append("@example.com,");
        s.setRecipients(many.toString());
        assertEquals("Máximo 20 destinatarios por envío.", ReportScheduleCalc.normalize(s, KNOWN));
    }
}
