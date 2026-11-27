/*
 *
 * DallyControl — Alertas (new console module, not part of upstream Headwind).
 *
 * Fleet alerts for three conditions the operator must notice and acknowledge:
 *   (1) DESCONEXIÓN   — a device that has not reported for longer than the "offline" threshold.
 *   (2) SALIDA DE KIOSKO — the on-device kiosk-exit lifecycle event.
 *   (3) CAMBIO DE SIM  — any SIM lifecycle event (removed / changed / inserted).
 *
 * Approach (documented): alerts are *derived and persisted*, not computed fresh each time. Every read first runs
 * an idempotent generation step (see {@link com.hmdm.persistence.mapper.AlertsMapper}) that inserts any new alert
 * rows — event-derived ones dedupe on the source event's timestamp, disconnection keeps one open alert per offline
 * episode — then the persisted rows are listed. Persisting is what lets an operator mark a specific alert
 * "revisada" (who / when): the review flag lives on the row, so it survives regeneration.
 *
 * Gating mirrors the Reportes module: any signed-in user of the customer may read, and a folder administrator is
 * narrowed to their own devices — list/export rows are dropped to the user's device scope, and a review is refused
 * (403) unless the alert's device is in that scope. The web hides the module behind the same `manage_fleet`
 * permission used for Reportes.
 *
 */

package com.hmdm.rest.resource;

import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.AlertsDAO;
import com.hmdm.persistence.domain.DeviceGroupView;
import com.hmdm.persistence.domain.User;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.resource.support.UserScope;
import com.hmdm.security.SecurityContext;
import com.hmdm.util.ReportExport;
import com.hmdm.util.ReportExport.Column;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.StreamingOutput;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Singleton
@Path("/private/alerts/v1")
@Api(tags = {"Alertas: alertas de flota"})
public class AlertsResource {

    private static final Logger logger = LoggerFactory.getLogger(AlertsResource.class);
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("America/Bogota");

    /** Default "offline" threshold, minutes (matches the console's online window, DeviceExport.ONLINE_WINDOW_MS). */
    private static final int DEFAULT_OFFLINE_MINUTES = 20;
    /** How far back events are scanned when generating alerts (dedup makes this safe to keep generous). */
    private static final long EVENT_LOOKBACK_MS = 90L * 24 * 3600_000L;
    /** Cap on rows returned/exported, to bound a single read. */
    private static final int MAX_ROWS = 5000;

    private AlertsDAO alertsDAO;
    private AgentCommandDAO commandDAO;
    private UserScope scope;
    /** Holds the customer's saved "offline" threshold (shared with the alert e-mails). */
    private com.hmdm.persistence.GeofenceDAO settingsDAO;

    /** A constructor required by Swagger. */
    public AlertsResource() {
    }

    @Inject
    public AlertsResource(AlertsDAO alertsDAO, AgentCommandDAO commandDAO, UserScope scope,
                          com.hmdm.persistence.GeofenceDAO settingsDAO) {
        this.settingsDAO = settingsDAO;
        this.alertsDAO = alertsDAO;
        this.commandDAO = commandDAO;
        this.scope = scope;
    }

    // --- alert type ----------------------------------------------------------------------------------------------

    private static final String[] TYPES = {"disconnected", "kioskExit", "simChange", "geofenceExit", "geofenceEnter",
            "dataWarning", "dataLimit"};

    private static String typeLabel(String type) {
        if (type == null) return "";
        switch (type) {
            case "disconnected": return "Desconexión";
            case "kioskExit": return "Salida de kiosko";
            case "simChange": return "Cambio de SIM";
            case "geofenceExit": return "Salida de zona";
            case "geofenceEnter": return "Entrada a zona";
            case "dataWarning": return "Datos móviles: cerca del límite";
            case "dataLimit": return "Datos móviles: límite superado";
            default: return type;
        }
    }

    /** Normalise a requested type to one of the known alert types, or null (all). */
    private static String normalizeType(String type) {
        if (type == null || type.trim().isEmpty()) return null;
        String t = type.trim();
        for (String known : TYPES) if (known.equalsIgnoreCase(t)) return known;
        return "\u0000"; // an unknown type: matches nothing
    }

    private static String normalizeStatus(String status) {
        if (status == null) return null;
        String s = status.trim().toLowerCase();
        return ("reviewed".equals(s) || "pending".equals(s)) ? s : null;
    }

    private List<Column> columns() {
        return Arrays.asList(
                col("tipo", "Tipo", "text"),
                col("name", "Equipo", "text"),
                col("number", "Número", "text"),
                col("group", "Carpeta", "text"),
                col("createdat", "Fecha", "datetime"),
                col("detail", "Detalle", "text"),
                col("estado", "Estado", "text"),
                col("reviewedbyname", "Revisada por", "text"),
                col("reviewedat", "Revisada el", "datetime"));
    }

    // --- read ----------------------------------------------------------------------------------------------------

    @ApiOperation(value = "Listar alertas",
            notes = "?type=disconnected|kioskExit|simChange|geofenceExit|geofenceEnter &from=&to= (epoch ms) &status=reviewed|pending &minutes=<umbral de desconexión>")
    @GET
    @Path("/list")
    @Produces(MediaType.APPLICATION_JSON)
    public Response list(@QueryParam("type") String type, @QueryParam("from") Long from,
                         @QueryParam("to") Long to, @QueryParam("status") String status,
                         @QueryParam("minutes") Integer minutes) {
        Optional<Integer> customerId = reader();
        if (!customerId.isPresent()) return Response.PERMISSION_DENIED();
        try {
            List<Map<String, Object>> rows = loadRows(customerId.get(), type, from, to, status, minutes);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("report", "alerts");
            out.put("title", "Alertas de flota");
            out.put("generatedAt", System.currentTimeMillis());
            out.put("columns", columnsAsMaps());
            out.put("rows", rows);
            return Response.OK(out);
        } catch (Exception e) {
            logger.warn("Alerts list failed for customer {}", customerId.get(), e);
            return Response.ERROR("error.alerts.failed");
        }
    }

    // --- review --------------------------------------------------------------------------------------------------

    /** Body of POST /review. */
    public static final class ReviewRequest {
        public Long id;
        public Boolean reviewed;
    }

    @ApiOperation(value = "Marcar una alerta como revisada",
            notes = "{ id, reviewed } — sella quién/cuándo al revisar; limpia ambos al desmarcar.")
    @POST
    @Path("/review")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response review(ReviewRequest body) {
        Optional<Integer> customerId = reader();
        if (!customerId.isPresent()) return Response.PERMISSION_DENIED();
        if (body == null || body.id == null || body.reviewed == null) {
            return Response.ERROR("error.alerts.badRequest");
        }
        final int c = customerId.get();
        try {
            String deviceNumber = alertsDAO.deviceNumberOf(body.id, c);
            if (deviceNumber == null) return Response.OBJECT_NOT_FOUND_ERROR();
            // Folder administrators may only review alerts for their own devices.
            if (scope != null && scope.restricted() && !scope.canDeviceNumber(c, deviceNumber)) {
                return Response.PERMISSION_DENIED();
            }
            Integer userId = SecurityContext.get() == null ? null
                    : SecurityContext.get().getCurrentUser().map(User::getId).orElse(null);
            long now = System.currentTimeMillis();
            alertsDAO.setReviewed(body.id, body.reviewed, userId, now, c);
            return Response.OK();
        } catch (Exception e) {
            logger.warn("Alert review {} failed for customer {}", body.id, c, e);
            return Response.ERROR("error.alerts.failed");
        }
    }

    // --- export (CSV / Excel) ------------------------------------------------------------------------------------

    @ApiOperation(value = "Exportar alertas", notes = "?format=csv|xlsx + los mismos filtros que la lista.")
    @GET
    @Path("/export")
    @Produces({"text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"})
    public javax.ws.rs.core.Response export(@QueryParam("format") String format,
                                            @QueryParam("type") String type,
                                            @QueryParam("from") Long from,
                                            @QueryParam("to") Long to,
                                            @QueryParam("status") String status,
                                            @QueryParam("minutes") Integer minutes,
                                            @QueryParam("tz") String tz) {
        Optional<Integer> customerId = reader();
        if (!customerId.isPresent()) {
            return javax.ws.rs.core.Response.status(403).build();
        }
        final int c = customerId.get();
        final List<Map<String, Object>> rows;
        try {
            rows = loadRows(c, type, from, to, status, minutes);
        } catch (Exception e) {
            logger.warn("Alerts export failed for customer {}", c, e);
            return javax.ws.rs.core.Response.status(500).build();
        }
        final ZoneId zone = zoneOf(tz);
        final List<Column> cols = columns();
        boolean xlsx = !"csv".equalsIgnoreCase(format);
        final long now = System.currentTimeMillis();
        String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
                .format(java.time.Instant.ofEpochMilli(now).atZone(zone));
        String fileName = "alertas-" + stamp + (xlsx ? ".xlsx" : ".csv");

        StreamingOutput out = os -> {
            if (xlsx) {
                ReportExport.writeXlsx("Alertas de flota", cols, rows, zone, now, os);
            } else {
                ReportExport.writeCsv(cols, rows, zone, os);
            }
        };
        String ctype = xlsx ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                : "text/csv; charset=utf-8";
        return javax.ws.rs.core.Response.ok(out)
                .header("Content-Type", ctype)
                .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .header("Cache-Control", "no-store")
                .build();
    }

    // --- shared --------------------------------------------------------------------------------------------------

    /** Generate (idempotent), load, enrich, decorate and scope the alert rows for the current filters. */
    private List<Map<String, Object>> loadRows(int c, String type, Long from, Long to, String status, Integer minutes) {
        // No explicit threshold = the one saved for the customer (Geocercas settings: offlineMinutes), which is also
        // what the alert e-mails use.
        int saved = DEFAULT_OFFLINE_MINUTES;
        try {
            if (settingsDAO != null) saved = settingsDAO.getSettings(c).offlineMinutes;
        } catch (Exception e) {
            logger.warn("Offline threshold of customer {} not read: {}", c, e.getMessage());
        }
        int m = clamp(minutes, saved, 1, 60 * 24 * 30);
        long now = System.currentTimeMillis();
        long cutoff = now - m * 60_000L;
        alertsDAO.generate(c, cutoff, now, DEFAULT_ZONE.getId(), now - EVENT_LOOKBACK_MS);

        List<Map<String, Object>> rows = alertsDAO.list(c, normalizeType(type), from, to, normalizeStatus(status), MAX_ROWS);
        enrich(c, rows);
        for (Map<String, Object> row : rows) {
            row.put("tipo", typeLabel(str(row.get("type"))));
            Object reviewed = row.get("reviewed");
            row.put("estado", (reviewed instanceof Boolean && (Boolean) reviewed) ? "Revisada" : "Pendiente");
            // The reviewer's display name (name, falling back to login).
            row.put("reviewedbyname", nonBlank(str(row.get("reviewedbyname")), str(row.get("reviewedbylogin"))));
        }
        return scoped(rows);
    }

    /** Reader = any signed-in user of a customer (folder administrators are then narrowed by {@link #scoped}). */
    private Optional<Integer> reader() {
        // Same gate as the console (Alertas sits with Reportes behind manage_fleet): the API refuses without it.
        if (SecurityContext.get() == null || !SecurityContext.get().hasPermission("manage_fleet")) return Optional.empty();
        return SecurityContext.get().getCurrentCustomerId();
    }

    /** Narrow the rows to the devices a folder administrator may reach (every alert carries a device number). */
    private List<Map<String, Object>> scoped(List<Map<String, Object>> rows) {
        if (scope == null || !scope.restricted()) return rows;
        java.util.Set<String> visible = scope.deviceNumbers();
        if (visible == null) return rows;
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object num = row.get("number");
            if (num != null && visible.contains(String.valueOf(num))) kept.add(row);
        }
        return kept;
    }

    /** Add a display name and a folder-path to every row (same convention as the Reportes module). */
    private void enrich(int customerId, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return;
        Map<Integer, String> paths = groupPaths(customerId);
        for (Map<String, Object> row : rows) {
            row.put("name", nonBlank(str(row.get("description")), str(row.get("number"))));
            if (row.containsKey("groupid")) {
                Integer gid = intOf(row.get("groupid"));
                row.put("group", gid == null ? "Sin carpeta" : paths.getOrDefault(gid, "Sin carpeta"));
            }
        }
    }

    private Map<Integer, String> groupPaths(int customerId) {
        List<DeviceGroupView> groups = commandDAO.listGroups(customerId);
        Map<Integer, DeviceGroupView> byId = new HashMap<>();
        for (DeviceGroupView g : groups) byId.put(g.getId(), g);
        Map<Integer, String> paths = new HashMap<>();
        for (DeviceGroupView g : groups) {
            StringBuilder sb = new StringBuilder(g.getName());
            DeviceGroupView p = g.getParentId() == null ? null : byId.get(g.getParentId());
            for (int i = 0; p != null && i < 32; i++) {
                sb.insert(0, p.getName() + " / ");
                p = p.getParentId() == null ? null : byId.get(p.getParentId());
            }
            paths.put(g.getId(), sb.toString());
        }
        return paths;
    }

    private List<Map<String, Object>> columnsAsMaps() {
        List<Map<String, Object>> cols = new ArrayList<>();
        for (Column c : columns()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", c.key);
            m.put("label", c.label);
            m.put("type", c.type);
            cols.add(m);
        }
        return cols;
    }

    private static Column col(String key, String label, String type) {
        return new Column(key, label, type);
    }

    private ZoneId zoneOf(String tz) {
        if (tz != null && !tz.trim().isEmpty()) {
            try {
                return ZoneId.of(tz.trim());
            } catch (Exception ignored) {
                // keep default
            }
        }
        return DEFAULT_ZONE;
    }

    private static int clamp(Integer v, int def, int min, int max) {
        int x = v == null ? def : v;
        return Math.max(min, Math.min(max, x));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String nonBlank(String a, String b) {
        return a != null && !a.trim().isEmpty() ? a : (b == null ? "" : b);
    }

    private static Integer intOf(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : null;
    }
}
