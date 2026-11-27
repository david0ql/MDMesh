package com.hmdm.rest.resource.support;

import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.AlertsDAO;
import com.hmdm.persistence.GeofenceDAO;
import com.hmdm.persistence.domain.DeviceGroupView;
import com.hmdm.service.EmailService;
import com.hmdm.util.AlertDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DallyControl — e-mails fleet alerts (geofences and the existing kinds: disconnection, kiosk exit, SIM change).
 *
 * Runs every minute. For each customer with someone to notify it derives the pending alerts (the same idempotent
 * generation the Alerts page runs on read), works out who receives each one — recipient rules by folder (a rule on
 * a folder covers the folders below it; a rule without folder covers the whole fleet), plus the addresses on the
 * zone itself — and sends at most one message per recipient per interval ({@link AlertDigest}). Without SMTP it
 * sends nothing and never fails; the console shows that state.
 */
@Singleton
public class AlertNotifier implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(AlertNotifier.class);

    private static final ZoneId ZONE = ZoneId.of("America/Bogota");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /** Alerts older than this are never mailed (a recipient added today does not get last week's). */
    private static final long MAX_AGE_MS = 24L * 3600_000L;
    private static final int MAX_ALERTS = 2000;
    private static final int MAX_LINES = 50;

    private final GeofenceDAO dao;
    private final AlertsDAO alertsDAO;
    private final AgentCommandDAO commandDAO;
    private final EmailService email;
    private final String baseUrl;
    private final String appName;

    /** An alert row with what the mail shows. */
    static final class Alert extends AlertDigest.Item {
        final String type;
        final String number;
        final String device;
        final String folder;
        final String zone;
        final String detail;
        final long at;

        Alert(long id, String key, Set<String> to, String type, String number, String device, String folder,
              String zone, String detail, long at) {
            super(id, key, to);
            this.type = type;
            this.number = number;
            this.device = device;
            this.folder = folder;
            this.zone = zone;
            this.detail = detail;
            this.at = at;
        }
    }

    @Inject
    public AlertNotifier(GeofenceDAO dao, AlertsDAO alertsDAO, AgentCommandDAO commandDAO, EmailService email,
                         @Named("base.url") String baseUrl, @Named("rebranding.name") String appName) {
        this.dao = dao;
        this.alertsDAO = alertsDAO;
        this.commandDAO = commandDAO;
        this.email = email;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.appName = appName == null || appName.trim().isEmpty() ? "DallyControl" : appName.trim();
    }

    public boolean mailConfigured() {
        return email.isConfigured();
    }

    /** Send one test message. @return false when SMTP is not configured or the server refused it. */
    public boolean sendTest(String to) {
        return email.sendEmail(to, "[" + appName + "] Prueba de notificación de alertas",
                "<p>Este es un mensaje de prueba de las alertas de <strong>" + esc(appName) + "</strong>.</p>"
                        + "<p>Si lo recibiste, el correo de alertas está funcionando.</p>");
    }

    @Override
    public void run() {
        try {
            for (Integer customerId : dao.customersToNotify()) {
                try {
                    notifyCustomer(customerId, System.currentTimeMillis());
                } catch (Exception e) {
                    logger.warn("Alert e-mails of customer {} failed: {}", customerId, e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.warn("Alert e-mail run failed: {}", e.getMessage());
        }
    }

    private void generate(int customerId, long now) {
        // The "disconnected" threshold is the one the administrator set in the Alerts page.
        long offlineMs = dao.getSettings(customerId).offlineMinutes * 60_000L;
        alertsDAO.generate(customerId, now - offlineMs, now, ZONE.getId(), now - MAX_AGE_MS);
    }

    /**
     * Give every configured address a cursor at the newest existing alert, so only alerts raised from now on are
     * mailed to it. Called when recipients are saved, and again on every run for addresses that still lack one.
     */
    public void prime(int customerId) {
        try {
            long now = System.currentTimeMillis();
            generate(customerId, now);
            ensureStates(customerId, recipientRules(customerId), zoneAddresses(customerId));
        } catch (Exception e) {
            logger.warn("Could not prepare alert e-mails of customer {}: {}", customerId, e.getMessage());
        }
    }

    private static final class Rule {
        Integer groupId;
        List<String> emails;
        Set<String> types;
    }

    private List<Rule> recipientRules(int customerId) {
        List<Rule> rules = new ArrayList<>();
        for (Map<String, Object> r : dao.listRecipients(customerId)) {
            if (Boolean.FALSE.equals(r.get("active"))) continue;
            Rule rule = new Rule();
            rule.groupId = GeofenceDAO.intOf(r.get("groupid"));
            rule.emails = AlertDigest.parseEmails((String) r.get("emails"));
            String types = (String) r.get("types");
            if (types != null && !types.trim().isEmpty()) {
                rule.types = new HashSet<>();
                for (String t : types.split(",")) if (!t.trim().isEmpty()) rule.types.add(t.trim());
            }
            if (!rule.emails.isEmpty()) rules.add(rule);
        }
        return rules;
    }

    private Set<String> zoneAddresses(int customerId) {
        Set<String> out = new LinkedHashSet<>();
        for (com.hmdm.persistence.domain.Geofence z : dao.listZones(customerId)) {
            if (z.active) out.addAll(AlertDigest.parseEmails(z.emails));
        }
        return out;
    }

    private Map<String, AlertDigest.RecipientState> ensureStates(int customerId, List<Rule> rules, Set<String> zoneEmails) {
        Map<String, AlertDigest.RecipientState> states = new HashMap<>();
        for (Map<String, Object> r : dao.listEmailStates(customerId)) {
            states.put((String) r.get("email"),
                    new AlertDigest.RecipientState(GeofenceDAO.longOf(r.get("lastsentat")), GeofenceDAO.longOf(r.get("lastalertid"))));
        }
        Set<String> all = new LinkedHashSet<>(zoneEmails);
        for (Rule r : rules) all.addAll(r.emails);
        Long max = null;
        for (String to : all) {
            if (states.containsKey(to)) continue;
            if (max == null) max = dao.maxAlertId(customerId);
            dao.saveEmailState(customerId, to, 0, max);
            states.put(to, new AlertDigest.RecipientState(0, max));
        }
        return states;
    }

    void notifyCustomer(int customerId, long now) {
        List<Rule> rules = recipientRules(customerId);
        // Cursors first, alerts after: an address that was just added starts at what existed before this run.
        Map<String, AlertDigest.RecipientState> states = ensureStates(customerId, rules, zoneAddresses(customerId));
        if (states.isEmpty()) return;
        generate(customerId, now);

        long minCursor = Long.MAX_VALUE;
        for (AlertDigest.RecipientState s : states.values()) minCursor = Math.min(minCursor, s.lastAlertId);
        List<Map<String, Object>> rows = dao.alertsAfter(customerId, minCursor, now - MAX_AGE_MS, MAX_ALERTS);
        if (rows.isEmpty()) return;

        Map<Integer, DeviceGroupView> groups = new HashMap<>();
        for (DeviceGroupView g : commandDAO.listGroups(customerId)) groups.put(g.getId(), g);

        List<Alert> alerts = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String type = (String) r.get("type");
            Integer groupId = GeofenceDAO.intOf(r.get("groupid"));
            Set<Integer> chain = chain(groupId, groups);
            Set<String> to = new LinkedHashSet<>(AlertDigest.parseEmails((String) r.get("zoneemails")));
            for (Rule rule : rules) {
                if (rule.types != null && !rule.types.contains(type)) continue;
                if (rule.groupId != null && !chain.contains(rule.groupId)) continue;
                to.addAll(rule.emails);
            }
            if (to.isEmpty()) continue;
            String number = (String) r.get("number");
            String description = (String) r.get("description");
            Integer zoneId = GeofenceDAO.intOf(r.get("zoneid"));
            alerts.add(new Alert(GeofenceDAO.longOf(r.get("id")), number + "|" + type + "|" + (zoneId == null ? "" : zoneId), to, type,
                    number, description == null || description.trim().isEmpty() ? number : description,
                    path(groupId, groups), (String) r.get("zonename"), (String) r.get("detail"), GeofenceDAO.longOf(r.get("createdat"))));
        }
        if (alerts.isEmpty()) return;
        if (!email.isConfigured()) return; // nothing is lost: the cursors stay, the console says SMTP is missing

        long intervalMs = dao.getSettings(customerId).emailIntervalMin * 60_000L;
        for (AlertDigest.Mail mail : AlertDigest.plan(now, intervalMs, states, alerts)) {
            List<AlertDigest.Line> lines = AlertDigest.collapse(mail.items, MAX_LINES);
            boolean sent = email.sendEmail(mail.to, subject(mail.items.size(), lines), body(mail.items.size(), lines));
            // A refused message keeps its alerts for the next attempt, one interval later.
            dao.saveEmailState(customerId, mail.to, now, sent ? mail.cursor : states.get(mail.to).lastAlertId);
            if (sent) {
                logger.info("Alert e-mail to {} with {} alert(s) of customer {}", mail.to, mail.items.size(), customerId);
            } else {
                logger.warn("Alert e-mail to {} was not accepted by the SMTP server", mail.to);
            }
        }
    }

    private static Set<Integer> chain(Integer groupId, Map<Integer, DeviceGroupView> groups) {
        Set<Integer> out = new HashSet<>();
        DeviceGroupView g = groupId == null ? null : groups.get(groupId);
        for (int i = 0; g != null && i < 32; i++) {
            out.add(g.getId());
            g = g.getParentId() == null ? null : groups.get(g.getParentId());
        }
        return out;
    }

    private static String path(Integer groupId, Map<Integer, DeviceGroupView> groups) {
        DeviceGroupView g = groupId == null ? null : groups.get(groupId);
        if (g == null) return "Sin carpeta";
        StringBuilder sb = new StringBuilder(g.getName());
        DeviceGroupView p = g.getParentId() == null ? null : groups.get(g.getParentId());
        for (int i = 0; p != null && i < 32; i++) {
            sb.insert(0, p.getName() + " / ");
            p = p.getParentId() == null ? null : groups.get(p.getParentId());
        }
        return sb.toString();
    }

    static String typeLabel(String type) {
        if (type == null) return "";
        switch (type) {
            case "disconnected": return "Desconexión";
            case "kioskExit": return "Salida de kiosko";
            case "simChange": return "Cambio de SIM";
            case "geofenceExit": return "Salida de zona";
            case "geofenceEnter": return "Entrada a zona";
            default: return type;
        }
    }

    String subject(int total, List<AlertDigest.Line> lines) {
        if (total == 1) {
            Alert a = (Alert) lines.get(0).item;
            return "[" + appName + "] " + typeLabel(a.type) + ": " + a.device + (a.zone == null ? "" : " — " + a.zone);
        }
        return "[" + appName + "] " + total + " alertas nuevas de la flota";
    }

    String body(int total, List<AlertDigest.Line> lines) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#111\">");
        sb.append("<p>").append(total == 1 ? "Hay una alerta nueva" : "Hay " + total + " alertas nuevas")
                .append(" en <strong>").append(esc(appName)).append("</strong>:</p>");
        sb.append("<table cellpadding=\"6\" cellspacing=\"0\" style=\"border-collapse:collapse;border:1px solid #ddd\">");
        sb.append("<tr style=\"background:#f3f4f6;text-align:left\"><th>Alerta</th><th>Equipo</th><th>Carpeta</th>"
                + "<th>Fecha</th><th>Detalle</th><th></th></tr>");
        for (AlertDigest.Line line : lines) {
            Alert a = (Alert) line.item;
            String device = baseUrl + "/devices/" + urlPart(a.number);
            sb.append("<tr style=\"border-top:1px solid #ddd\"><td><strong>").append(esc(typeLabel(a.type))).append("</strong>");
            if (line.count > 1) sb.append(" (").append(line.count).append(" veces)");
            sb.append("</td><td><a href=\"").append(esc(device)).append("\">").append(esc(a.device)).append("</a></td><td>")
                    .append(esc(a.folder)).append("</td><td>").append(WHEN.format(Instant.ofEpochMilli(a.at).atZone(ZONE)))
                    .append("</td><td>").append(esc(a.detail == null ? "" : a.detail)).append("</td><td><a href=\"")
                    .append(esc(device + "?tab=location")).append("\">Ver en el mapa</a></td></tr>");
        }
        sb.append("</table>");
        if (total > lines.size()) {
            int shown = 0;
            for (AlertDigest.Line l : lines) shown += l.count;
            if (total > shown) sb.append("<p>… y ").append(total - shown).append(" más.</p>");
        }
        sb.append("<p><a href=\"").append(esc(baseUrl + "/alerts")).append("\">Abrir las alertas en la consola</a> para revisarlas.</p>");
        sb.append("<p style=\"color:#666;font-size:12px\">Horas en hora de Colombia (America/Bogota). Recibes este correo porque tu dirección "
                + "está en las notificaciones de alertas; como máximo llega un mensaje por intervalo, con todo lo ocurrido.</p></div>");
        return sb.toString();
    }

    private static String urlPart(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
