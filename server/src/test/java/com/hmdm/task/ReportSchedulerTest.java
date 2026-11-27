package com.hmdm.task;

import com.hmdm.persistence.domain.ReportSchedule;
import org.junit.Before;
import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class ReportSchedulerTest {
    private static final ZoneId BOG = ZoneId.of("America/Bogota");

    private static long at(int d, int h, int mi) {
        return ZonedDateTime.of(2026, 11, d, h, mi, 0, 0, BOG).toInstant().toEpochMilli();
    }

    /** report_schedule in memory, with the same claim rule as the SQL (compare-and-set on nextRunAt). */
    private static final class MemStore implements ReportScheduler.Store {
        final Map<Integer, ReportSchedule> rows = new LinkedHashMap<>();
        boolean failDue;

        @Override
        public List<ReportSchedule> due(long now) {
            if (failDue) throw new IllegalStateException("db down");
            List<ReportSchedule> out = new ArrayList<>();
            for (ReportSchedule s : rows.values()) {
                if (s.isEnabled() && s.getNextRunAt() != null && s.getNextRunAt() <= now) out.add(copy(s));
            }
            return out;
        }

        @Override
        public boolean claim(int id, long prev, long next) {
            ReportSchedule s = rows.get(id);
            if (s == null || !s.isEnabled() || s.getNextRunAt() == null || s.getNextRunAt() != prev) return false;
            s.setNextRunAt(next);
            return true;
        }

        @Override
        public void result(int id, long at, String status, String error) {
            ReportSchedule s = rows.get(id);
            s.setLastRunAt(at);
            s.setLastStatus(status);
            s.setLastError(error);
        }

        private static ReportSchedule copy(ReportSchedule s) {
            ReportSchedule c = new ReportSchedule();
            c.setId(s.getId()); c.setName(s.getName()); c.setFrequency(s.getFrequency()); c.setRunHour(s.getRunHour());
            c.setRunMinute(s.getRunMinute()); c.setDayOfWeek(s.getDayOfWeek()); c.setDayOfMonth(s.getDayOfMonth());
            c.setTimezone(s.getTimezone()); c.setRecipients(s.getRecipients()); c.setEnabled(s.isEnabled());
            c.setNextRunAt(s.getNextRunAt()); c.setFormat(s.getFormat()); c.setReport(s.getReport());
            return c;
        }
    }

    private static final class Sent {
        final String to;
        final ReportScheduler.Mail mail;

        Sent(String to, ReportScheduler.Mail mail) {
            this.to = to;
            this.mail = mail;
        }
    }

    private MemStore store;
    private List<Sent> outbox;
    private boolean smtp;
    private String smtpError;
    private int generated;
    private Integer failGenerationFor;
    private ReportScheduler scheduler;

    @Before
    public void setUp() {
        store = new MemStore();
        outbox = new ArrayList<>();
        smtp = true;
        smtpError = null;
        generated = 0;
        failGenerationFor = null;
        scheduler = new ReportScheduler(store, (s, now) -> {
            if (failGenerationFor != null && failGenerationFor.equals(s.getId())) throw new IllegalStateException("sin datos");
            generated++;
            ReportScheduler.Mail m = new ReportScheduler.Mail();
            m.subject = "Reporte " + s.getName();
            m.html = "<p>hola</p>";
            m.fileName = "inventario." + s.getFormat();
            m.contentType = "text/csv";
            m.data = new byte[]{1, 2, 3};
            return m;
        }, new ReportScheduler.Mailer() {
            @Override
            public boolean configured() {
                return smtp;
            }

            @Override
            public String send(String to, ReportScheduler.Mail mail) {
                if (smtpError != null) return smtpError;
                outbox.add(new Sent(to, mail));
                return null;
            }
        });
    }

    private ReportSchedule daily(int id, int hour, long nextRunAt) {
        ReportSchedule s = new ReportSchedule();
        s.setId(id);
        s.setName("Inventario " + id);
        s.setFrequency("daily");
        s.setRunHour(hour);
        s.setTimezone("America/Bogota");
        s.setRecipients("ops@example.com, jefe@example.com");
        s.setFormat("csv");
        s.setNextRunAt(nextRunAt);
        store.rows.put(id, s);
        return s;
    }

    @Test
    public void a_due_schedule_is_sent_once_and_moves_to_its_next_slot() {
        daily(1, 7, at(27, 7, 0));
        assertEquals("not due yet", 0, scheduler.tick(at(27, 6, 59)));
        assertEquals(1, scheduler.tick(at(27, 7, 0)));
        assertEquals(1, outbox.size());
        assertEquals("ops@example.com,jefe@example.com", outbox.get(0).to);
        assertEquals("inventario.csv", outbox.get(0).mail.fileName);
        ReportSchedule s = store.rows.get(1);
        assertEquals(at(28, 7, 0), (long) s.getNextRunAt());
        assertEquals("ok", s.getLastStatus());
        assertNull(s.getLastError());
        assertEquals(at(27, 7, 0), (long) s.getLastRunAt());
        assertEquals("the next ticks of the same minute send nothing", 0, scheduler.tick(at(27, 7, 0) + 30_000));
        assertEquals(0, scheduler.tick(at(27, 12, 0)));
        assertEquals(1, outbox.size());
        assertEquals("and tomorrow it goes again", 1, scheduler.tick(at(28, 7, 1)));
        assertEquals(2, outbox.size());
    }

    @Test
    public void a_run_missed_while_the_server_was_down_is_sent_once() {
        daily(1, 7, at(24, 7, 0)); // three slots ago
        assertEquals(1, scheduler.tick(at(27, 9, 0)));
        assertEquals(1, outbox.size());
        assertEquals("next slot is in the future, not the missed ones", at(28, 7, 0), (long) store.rows.get(1).getNextRunAt());
        assertEquals(0, scheduler.tick(at(27, 9, 1)));
    }

    @Test
    public void a_schedule_claimed_by_another_server_is_not_sent_again() {
        ReportSchedule s = daily(1, 7, at(27, 7, 0));
        ReportScheduler.Store racing = new ReportScheduler.Store() {
            @Override
            public List<ReportSchedule> due(long now) {
                List<ReportSchedule> d = store.due(now);
                s.setNextRunAt(at(28, 7, 0)); // the other server took it between our read and our claim
                return d;
            }

            @Override
            public boolean claim(int id, long prev, long next) {
                return store.claim(id, prev, next);
            }

            @Override
            public void result(int id, long at, String status, String error) {
                store.result(id, at, status, error);
            }
        };
        ReportScheduler other = new ReportScheduler(racing, (x, now) -> {
            generated++;
            return new ReportScheduler.Mail();
        }, new ReportScheduler.Mailer() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public String send(String to, ReportScheduler.Mail mail) {
                outbox.add(new Sent(to, mail));
                return null;
            }
        });
        assertEquals(0, other.tick(at(27, 7, 0)));
        assertEquals(0, generated);
        assertTrue(outbox.isEmpty());
    }

    @Test
    public void paused_schedules_are_skipped() {
        daily(1, 7, at(27, 7, 0)).setEnabled(false);
        assertEquals(0, scheduler.tick(at(27, 8, 0)));
        assertTrue(outbox.isEmpty());
    }

    @Test
    public void without_smtp_nothing_is_generated_and_the_schedule_says_why() {
        smtp = false;
        daily(1, 7, at(27, 7, 0));
        assertEquals(0, scheduler.tick(at(27, 7, 0)));
        assertEquals(0, generated);
        ReportSchedule s = store.rows.get(1);
        assertEquals("smtp", s.getLastStatus());
        assertEquals(ReportScheduler.NO_SMTP_MESSAGE, s.getLastError());
        assertEquals("it does not retry every minute", at(28, 7, 0), (long) s.getNextRunAt());
        assertEquals(0, scheduler.tick(at(27, 7, 1)));
    }

    @Test
    public void a_failing_schedule_does_not_stop_the_others() {
        daily(1, 7, at(27, 7, 0));
        daily(2, 7, at(27, 7, 0));
        failGenerationFor = 1;
        assertEquals(1, scheduler.tick(at(27, 7, 0)));
        assertEquals("error", store.rows.get(1).getLastStatus());
        assertEquals("No se pudo generar el reporte: sin datos", store.rows.get(1).getLastError());
        assertEquals("ok", store.rows.get(2).getLastStatus());
        assertEquals(1, outbox.size());
    }

    @Test
    public void an_smtp_rejection_is_recorded() {
        smtpError = "535 Authentication failed";
        daily(1, 7, at(27, 7, 0));
        assertEquals(0, scheduler.tick(at(27, 7, 0)));
        assertEquals("error", store.rows.get(1).getLastStatus());
        assertEquals("No se pudo enviar el correo: 535 Authentication failed", store.rows.get(1).getLastError());
    }

    @Test
    public void send_now_runs_a_schedule_whatever_its_time_and_keeps_its_slot() {
        ReportSchedule s = daily(1, 7, at(28, 7, 0));
        s.setEnabled(false); // even paused: the operator asked for it
        ReportScheduler.Outcome o = scheduler.run(s, at(27, 15, 0));
        assertTrue(o.sent());
        assertEquals(1, outbox.size());
        assertEquals(at(28, 7, 0), (long) store.rows.get(1).getNextRunAt());
        assertEquals(at(27, 15, 0), (long) store.rows.get(1).getLastRunAt());
    }

    @Test
    public void a_database_error_while_reading_is_not_fatal() {
        store.failDue = true;
        assertEquals(0, scheduler.tick(at(27, 7, 0)));
    }
}
