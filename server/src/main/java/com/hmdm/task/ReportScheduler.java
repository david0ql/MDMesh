/*
 * DallyControl — scheduled fleet reports (new module, not part of upstream Headwind).
 */
package com.hmdm.task;

import com.hmdm.persistence.domain.ReportSchedule;
import com.hmdm.util.ReportScheduleCalc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The scheduled-report loop, free of the database, SMTP and the report builders (they come in as {@link Store},
 * {@link Mailer} and {@link Generator}), so its rules are tested on their own:
 *
 * <ul>
 *     <li>a schedule runs when its {@code nextRunAt} has passed; it is first <i>claimed</i> by moving
 *     {@code nextRunAt} to its next slot, so a second server (or an overlapping tick) never sends the same run;</li>
 *     <li>a server that was down sends a missed run once, not once per missed slot;</li>
 *     <li>without SMTP nothing is generated and the schedule records "smtp" — it does not fail or retry in a loop;</li>
 *     <li>one schedule failing (report or mail) never stops the others.</li>
 * </ul>
 */
public class ReportScheduler {
    private static final Logger log = LoggerFactory.getLogger(ReportScheduler.class);

    public static final String OK = "ok";
    public static final String ERROR = "error";
    public static final String NO_SMTP = "smtp";
    public static final String NO_SMTP_MESSAGE = "El servidor no tiene SMTP configurado: el reporte no se envió.";

    /** report_schedule. */
    public interface Store {
        List<ReportSchedule> due(long now);

        /** True when this caller moved nextRunAt from {@code prev} to {@code next} (it owns this run). */
        boolean claim(int id, long prev, long next);

        void result(int id, long at, String status, String error);
    }

    /** What is sent: the message and its attachment. */
    public static final class Mail {
        public String subject;
        public String html;
        public String fileName;
        public String contentType;
        public byte[] data;
    }

    public interface Generator {
        Mail generate(ReportSchedule s, long now) throws Exception;
    }

    public interface Mailer {
        boolean configured();

        /** Null when sent, else why not. */
        String send(String to, Mail mail);
    }

    /** How one run ended. */
    public static final class Outcome {
        public final String status;
        public final String error;

        Outcome(String status, String error) {
            this.status = status;
            this.error = error;
        }

        public boolean sent() {
            return OK.equals(status);
        }
    }

    private final Store store;
    private final Generator generator;
    private final Mailer mailer;

    public ReportScheduler(Store store, Generator generator, Mailer mailer) {
        this.store = store;
        this.generator = generator;
        this.mailer = mailer;
    }

    /** Send every schedule that is due at {@code now}. Returns how many were sent. */
    public int tick(long now) {
        List<ReportSchedule> due;
        try {
            due = store.due(now);
        } catch (Exception e) {
            log.warn("Could not read the due report schedules: {}", e.toString());
            return 0;
        }
        int sent = 0;
        for (ReportSchedule s : due) {
            try {
                if (!s.isEnabled() || s.getNextRunAt() == null || s.getNextRunAt() > now) continue;
                if (!store.claim(s.getId(), s.getNextRunAt(), ReportScheduleCalc.nextRun(s, now))) continue;
                if (run(s, now).sent()) sent++;
            } catch (Exception e) {
                log.warn("Report schedule {} failed: {}", s.getId(), e.toString());
            }
        }
        return sent;
    }

    /** Generate and send one schedule now ("Enviar ahora" and each due run), and record how it went. */
    public Outcome run(ReportSchedule s, long now) {
        Outcome o = send(s, now);
        try {
            if (s.getId() != null) store.result(s.getId(), now, o.status, o.error);
        } catch (Exception e) {
            log.warn("Could not record the result of report schedule {}: {}", s.getId(), e.toString());
        }
        if (o.sent()) log.info("Report schedule {} '{}' sent to {}", s.getId(), s.getName(), s.getRecipients());
        else log.warn("Report schedule {} '{}' not sent: {}", s.getId(), s.getName(), o.error);
        return o;
    }

    private Outcome send(ReportSchedule s, long now) {
        if (!mailer.configured()) return new Outcome(NO_SMTP, NO_SMTP_MESSAGE);
        List<String> to = ReportScheduleCalc.recipients(s.getRecipients());
        if (to.isEmpty()) return new Outcome(ERROR, "El envío no tiene destinatarios.");
        Mail mail;
        try {
            mail = generator.generate(s, now);
        } catch (Exception e) {
            return new Outcome(ERROR, "No se pudo generar el reporte: " + message(e));
        }
        if (mail == null || mail.data == null) return new Outcome(ERROR, "No se pudo generar el reporte.");
        String err;
        try {
            err = mailer.send(String.join(",", to), mail);
        } catch (Exception e) {
            err = message(e);
        }
        return err == null ? new Outcome(OK, null) : new Outcome(ERROR, "No se pudo enviar el correo: " + err);
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        m = m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m.trim();
        return m.length() > 500 ? m.substring(0, 500) : m;
    }
}
