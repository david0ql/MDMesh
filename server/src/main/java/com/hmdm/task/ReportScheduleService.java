/*
 * DallyControl — scheduled fleet reports (new module, not part of upstream Headwind).
 */
package com.hmdm.task;

import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.domain.DeviceGroupView;
import com.hmdm.persistence.domain.DeviceScopeRow;
import com.hmdm.persistence.domain.ReportSchedule;
import com.hmdm.persistence.mapper.ReportScheduleMapper;
import com.hmdm.rest.resource.ReportsResource;
import com.hmdm.rest.resource.support.FleetExportService;
import com.hmdm.service.EmailService;
import com.hmdm.util.DeviceExport;
import com.hmdm.util.FleetInventory;
import com.hmdm.util.ReportExport;
import com.hmdm.util.ReportScheduleCalc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scheduled reports against the real database, report builders and SMTP: the once-a-minute task
 * ({@link #run()}, started by StartupTaskModule) and what the console's schedule screen calls. The rules live in
 * {@link ReportScheduler}; this class only plugs the real collaborators into it.
 */
@Singleton
public class ReportScheduleService implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(ReportScheduleService.class);
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** The reports that can be scheduled, with the name shown in the console and in the e-mail. */
    public static final Map<String, String> REPORTS = new LinkedHashMap<>();

    static {
        REPORTS.put("inventory", "Inventario de la flota");
        REPORTS.put("offline", "Equipos sin conexión");
        REPORTS.put("outdated-agents", "Agentes desactualizados");
        REPORTS.put("outdated-apps", "Apps desactualizadas");
        REPORTS.put("non-compliance", "Incumplimiento de políticas");
        REPORTS.put("no-commands", "Equipos que no reciben comandos");
        REPORTS.put("data-usage", "Consumo de datos móviles");
        REPORTS.put("sim", "Retiro/cambio de SIM");
        REPORTS.put("decommissioned", "Equipos dados de baja");
    }

    private final ReportScheduleMapper mapper;
    private final AgentCommandDAO commandDAO;
    private final FleetExportService exportService;
    private final ReportsResource reports;
    private final EmailService email;
    private final ReportScheduler scheduler;

    @Inject
    public ReportScheduleService(ReportScheduleMapper mapper, AgentCommandDAO commandDAO, FleetExportService exportService,
                                 ReportsResource reports, EmailService email) {
        this.mapper = mapper;
        this.commandDAO = commandDAO;
        this.exportService = exportService;
        this.reports = reports;
        this.email = email;
        this.scheduler = new ReportScheduler(new ReportScheduler.Store() {
            @Override
            public List<ReportSchedule> due(long now) {
                return mapper.due(now);
            }

            @Override
            public boolean claim(int id, long prev, long next) {
                return mapper.claim(id, prev, next) == 1;
            }

            @Override
            public void result(int id, long at, String status, String error) {
                mapper.result(id, at, status, error == null ? null : error.length() > 1000 ? error.substring(0, 1000) : error);
            }
        }, this::generate, new ReportScheduler.Mailer() {
            @Override
            public boolean configured() {
                return email.isConfigured();
            }

            @Override
            public String send(String to, ReportScheduler.Mail mail) {
                return email.sendEmailWithAttachment(to, mail.subject, mail.html, mail.fileName, mail.data, mail.contentType);
            }
        });
    }

    /** One scheduler tick (every minute). Never throws: a failing tick must not cancel the repeating task. */
    @Override
    public void run() {
        try {
            scheduler.tick(System.currentTimeMillis());
        } catch (Throwable t) {
            log.warn("Report schedule tick failed: {}", t.toString());
        }
    }

    public boolean smtpConfigured() {
        return email.isConfigured();
    }

    public List<ReportSchedule> list(int customerId) {
        return mapper.list(customerId);
    }

    public ReportSchedule find(int customerId, int id) {
        return mapper.find(customerId, id);
    }

    /** Insert or update a schedule that {@link ReportScheduleCalc#normalize} accepted; sets its next run. */
    public ReportSchedule save(ReportSchedule s) {
        long now = System.currentTimeMillis();
        s.setUpdatedAt(now);
        s.setNextRunAt(s.isEnabled() ? ReportScheduleCalc.nextRun(s, now) : null);
        if (s.getId() == null) {
            s.setCreatedAt(now);
            mapper.insert(s);
        } else {
            mapper.update(s);
        }
        return mapper.find(s.getCustomerId(), s.getId());
    }

    public boolean groupExists(int customerId, int groupId) {
        return commandDAO.findGroup(customerId, groupId) != null;
    }

    public boolean delete(int customerId, int id) {
        return mapper.delete(customerId, id) > 0;
    }

    /** "Enviar ahora": generate and send at once, whatever the schedule's time or pause. */
    public ReportScheduler.Outcome sendNow(ReportSchedule s) {
        return scheduler.run(s, System.currentTimeMillis());
    }

    // --- what is sent ----------------------------------------------------------------------------------------------

    ReportScheduler.Mail generate(ReportSchedule s, long now) throws Exception {
        ZoneId zone = ReportScheduleCalc.zone(s.getTimezone());
        boolean csv = "csv".equals(s.getFormat());
        char sep = ReportExport.separator(s.getCsvSeparator());
        String title = REPORTS.getOrDefault(s.getReport(), s.getReport());
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").format(Instant.ofEpochMilli(now).atZone(zone));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<String[]> figures = new ArrayList<>();
        String scopeLabel;
        String fileStem;

        if ("inventory".equals(s.getReport())) {
            FleetExportService.Request rq = new FleetExportService.Request();
            rq.groupId = s.getGroupId();
            rq.tz = zone.getId();
            FleetExportService.Built built = exportService.build(s.getCustomerId(), rq, null, null);
            if (built == null) throw new IllegalStateException("la carpeta del envío ya no existe");
            DeviceExport.Input in = built.input;
            in.now = now;
            scopeLabel = in.scopeLabel;
            figures = FleetInventory.headline(DeviceExport.inventory(in).summary);
            if (csv) DeviceExport.writeCsv(in, sep, out);
            else DeviceExport.write(in, out);
            fileStem = "inventario-flota";
        } else {
            ReportsResource.ReportData data = reports.scheduled(s.getReport(), s.getCustomerId());
            if (data == null) throw new IllegalStateException("ese reporte no se puede programar");
            List<Map<String, Object>> rows = data.rows;
            scopeLabel = "Todos los dispositivos";
            if (s.getGroupId() != null) {
                DeviceGroupView g = commandDAO.findGroup(s.getCustomerId(), s.getGroupId());
                if (g == null) throw new IllegalStateException("la carpeta del envío ya no existe");
                Set<Integer> branch = new HashSet<>(commandDAO.groupSubtree(s.getCustomerId(), s.getGroupId()));
                Set<String> numbers = new HashSet<>();
                for (DeviceScopeRow r : commandDAO.listDeviceScopes(s.getCustomerId())) {
                    if (r.getGroupId() != null && branch.contains(r.getGroupId())) numbers.add(r.getNumber());
                }
                rows = new ArrayList<>();
                for (Map<String, Object> row : data.rows) {
                    if (numbers.contains(String.valueOf(row.get("number")))) rows.add(row);
                }
                scopeLabel = "Carpeta " + g.getName() + " y sus subcarpetas";
            }
            title = data.title;
            figures.add(new String[]{"Filas", String.valueOf(rows.size())});
            if (csv) ReportExport.writeCsv(data.columns, rows, zone, sep, out);
            else ReportExport.writeXlsx(data.title, data.columns, rows, zone, now, out);
            fileStem = "reporte-" + s.getReport();
        }

        ReportScheduler.Mail m = new ReportScheduler.Mail();
        m.fileName = fileStem + "-" + stamp + (csv ? ".csv" : ".xlsx");
        m.contentType = csv ? "text/csv; charset=utf-8" : XLSX;
        m.data = out.toByteArray();
        String when = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(now).atZone(zone));
        m.subject = "DallyControl · " + title + " · " + s.getName() + " · " + when.substring(0, 10);
        StringBuilder h = new StringBuilder();
        h.append("<div style=\"font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#1f2933\">");
        h.append("<h2 style=\"margin:0 0 4px;font-size:18px\">").append(esc(title)).append("</h2>");
        h.append("<p style=\"margin:0 0 12px;color:#52606d\">").append(esc(s.getName())).append(" · ").append(esc(scopeLabel))
                .append(" · generado ").append(esc(when)).append(" (").append(esc(zone.getId())).append(")</p>");
        h.append("<table cellpadding=\"6\" cellspacing=\"0\" style=\"border-collapse:collapse;font-size:14px\">");
        for (String[] kv : figures) {
            h.append("<tr><td style=\"border:1px solid #d9e2ec\">").append(esc(kv[0]))
                    .append("</td><td style=\"border:1px solid #d9e2ec;text-align:right\"><b>").append(esc(kv[1])).append("</b></td></tr>");
        }
        h.append("</table>");
        h.append("<p style=\"margin:12px 0 0\">El detalle va en el archivo adjunto <b>").append(esc(m.fileName)).append("</b>.</p>");
        h.append("<p style=\"margin:12px 0 0;color:#829ab1;font-size:12px\">Envío programado desde la consola DallyControl (Reportes → Envíos programados).</p>");
        h.append("</div>");
        m.html = h.toString();
        return m;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
