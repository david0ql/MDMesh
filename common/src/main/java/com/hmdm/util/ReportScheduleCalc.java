/*
 * DallyControl — scheduled fleet reports (new module, not part of upstream Headwind).
 */
package com.hmdm.util;

import com.hmdm.persistence.domain.ReportSchedule;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** When a scheduled report runs next, and the validation of what a schedule may hold. Pure (no I/O). */
public final class ReportScheduleCalc {
    public static final int MAX_RECIPIENTS = 20;
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private ReportScheduleCalc() {}

    public static ZoneId zone(String tz) {
        try {
            return tz == null || tz.trim().isEmpty() ? ZoneId.of("America/Bogota") : ZoneId.of(tz.trim());
        } catch (Exception e) {
            return ZoneId.of("America/Bogota");
        }
    }

    /**
     * The first run strictly after {@code afterMs}: daily at runHour:runMinute, weekly on dayOfWeek (1 = Monday),
     * monthly on dayOfMonth (the month's last day when it has fewer), all in the schedule's time zone.
     */
    public static long nextRun(ReportSchedule s, long afterMs) {
        ZoneId zone = zone(s.getTimezone());
        ZonedDateTime after = Instant.ofEpochMilli(afterMs).atZone(zone);
        LocalTime at = LocalTime.of(clamp(s.getRunHour(), 0, 23), clamp(s.getRunMinute(), 0, 59));
        String f = s.getFrequency() == null ? "daily" : s.getFrequency().toLowerCase(Locale.ROOT);
        ZonedDateTime next;
        switch (f) {
            case "weekly": {
                DayOfWeek dow = DayOfWeek.of(clamp(s.getDayOfWeek() == null ? 1 : s.getDayOfWeek(), 1, 7));
                next = after.toLocalDate().with(TemporalAdjusters.nextOrSame(dow)).atTime(at).atZone(zone);
                if (!next.isAfter(after)) next = after.toLocalDate().with(TemporalAdjusters.next(dow)).atTime(at).atZone(zone);
                break;
            }
            case "monthly": {
                int dom = clamp(s.getDayOfMonth() == null ? 1 : s.getDayOfMonth(), 1, 31);
                YearMonth ym = YearMonth.from(after);
                next = monthDay(ym, dom).atTime(at).atZone(zone);
                if (!next.isAfter(after)) next = monthDay(ym.plusMonths(1), dom).atTime(at).atZone(zone);
                break;
            }
            default:
                next = after.toLocalDate().atTime(at).atZone(zone);
                if (!next.isAfter(after)) next = after.toLocalDate().plusDays(1).atTime(at).atZone(zone);
        }
        return next.toInstant().toEpochMilli();
    }

    private static LocalDate monthDay(YearMonth ym, int dom) {
        return ym.atDay(Math.min(dom, ym.lengthOfMonth()));
    }

    /** The addresses of a "a@x.co, b@y.co; c@z.co" list: trimmed, lower-cased, without duplicates. */
    public static List<String> recipients(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) {
            for (String p : raw.split("[,;\\s]+")) {
                String e = p.trim().toLowerCase(Locale.ROOT);
                if (!e.isEmpty()) out.add(e);
            }
        }
        return new ArrayList<>(out);
    }

    public static boolean validEmail(String e) {
        return e != null && e.length() <= 254 && EMAIL.matcher(e).matches();
    }

    /**
     * Normalise a schedule in place (trimmed name, known format/frequency/separator, clamped time, clean recipient
     * list) and say what is wrong with it in Spanish, or null when it can be saved.
     */
    public static String normalize(ReportSchedule s, Set<String> knownReports) {
        String name = s.getName() == null ? "" : s.getName().trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) return "Escribe un nombre para el envío.";
        if (name.length() > 120) return "El nombre es demasiado largo (máximo 120 caracteres).";
        s.setName(name);
        String report = s.getReport() == null ? "inventory" : s.getReport().trim().toLowerCase(Locale.ROOT);
        if (!knownReports.contains(report)) return "Ese reporte no se puede programar.";
        s.setReport(report);
        s.setFormat("csv".equalsIgnoreCase(s.getFormat()) ? "csv" : "xlsx");
        char sep = ReportExport.separator(s.getCsvSeparator());
        s.setCsvSeparator(sep == '\t' ? "tab" : String.valueOf(sep));
        String f = s.getFrequency() == null ? "" : s.getFrequency().trim().toLowerCase(Locale.ROOT);
        if (!f.equals("daily") && !f.equals("weekly") && !f.equals("monthly")) return "Elige la frecuencia: diaria, semanal o mensual.";
        s.setFrequency(f);
        if (s.getRunHour() < 0 || s.getRunHour() > 23 || s.getRunMinute() < 0 || s.getRunMinute() > 59) return "La hora no es válida.";
        if (f.equals("weekly")) {
            if (s.getDayOfWeek() == null || s.getDayOfWeek() < 1 || s.getDayOfWeek() > 7) return "Elige el día de la semana.";
            s.setDayOfMonth(null);
        } else if (f.equals("monthly")) {
            if (s.getDayOfMonth() == null || s.getDayOfMonth() < 1 || s.getDayOfMonth() > 31) return "Elige el día del mes (1 a 31).";
            s.setDayOfWeek(null);
        } else {
            s.setDayOfWeek(null);
            s.setDayOfMonth(null);
        }
        try {
            s.setTimezone(ZoneId.of(s.getTimezone() == null ? "" : s.getTimezone().trim()).getId());
        } catch (Exception e) {
            return "La zona horaria no es válida.";
        }
        List<String> to = recipients(s.getRecipients());
        if (to.isEmpty()) return "Agrega al menos un destinatario.";
        if (to.size() > MAX_RECIPIENTS) return "Máximo " + MAX_RECIPIENTS + " destinatarios por envío.";
        for (String e : to) if (!validEmail(e)) return "El correo \"" + e + "\" no es válido.";
        s.setRecipients(String.join(", ", to));
        return null;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
