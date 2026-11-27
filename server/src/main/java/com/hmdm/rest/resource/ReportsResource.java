/*
 *
 * DallyControl — Reportes (new console module, not part of upstream Headwind).
 *
 * Read-only fleet reports built from the data the agent already reports (device_state, device_event,
 * device_app_usage, agentCommand, devices.infojson, device_tombstone). Every report is defined once
 * (title + typed columns + rows) and served both as JSON (for the table) and as CSV / Excel (export),
 * so a report never drifts between the screen and the file.
 *
 * Gating: a full administrator of the customer. Folder administrators are excluded (the module is
 * fleet-wide, like the rest of the Fleet tooling, and the web hides it behind AdminOnly). The scope
 * is the signed-in user's customer.
 *
 */

package com.hmdm.rest.resource;

import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.ReportsDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.domain.DeviceGroupView;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.resource.support.ConfigReconciler;
import com.hmdm.rest.resource.support.UserScope;
import com.hmdm.security.SecurityContext;
import com.hmdm.util.ReportExport;
import com.hmdm.util.ReportExport.Column;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.StreamingOutput;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Singleton
@Path("/private/reports/v1")
@Api(tags = {"Reportes: reportes de flota"})
public class ReportsResource {

    private static final Logger logger = LoggerFactory.getLogger(ReportsResource.class);
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("America/Bogota");

    private ReportsDAO reportsDAO;
    private AgentCommandDAO commandDAO;
    private UnsecureDAO unsecureDAO;
    private UserScope scope;
    private ConfigReconciler reconciler;
    private AgentPackageResource agentPackage;
    private String baseUrl = "";

    /** A constructor required by Swagger. */
    public ReportsResource() {
    }

    @Inject
    public ReportsResource(ReportsDAO reportsDAO, AgentCommandDAO commandDAO, UnsecureDAO unsecureDAO,
                           UserScope scope, ConfigReconciler reconciler, AgentPackageResource agentPackage,
                           @Named("base.url") String baseUrl) {
        this.reportsDAO = reportsDAO;
        this.commandDAO = commandDAO;
        this.unsecureDAO = unsecureDAO;
        this.scope = scope;
        this.reconciler = reconciler;
        this.agentPackage = agentPackage;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    }

    // --- report definition ---------------------------------------------------------------------------------------

    /** One report: its heading, its typed columns and its rows. Serialised to JSON and to CSV/Excel alike. */
    public static final class ReportData {
        public final String report;
        public final String title;
        public final List<Column> columns;
        public final List<Map<String, Object>> rows;
        public final long generatedAt = System.currentTimeMillis();
        /** Optional figures shown above the table (the fleet inventory's totals and breakdowns). */
        public Map<String, Object> summary;

        ReportData(String report, String title, List<Column> columns, List<Map<String, Object>> rows) {
            this.report = report;
            this.title = title;
            this.columns = columns;
            this.rows = rows;
        }
    }

    /** Known reports. {@code export} = can be downloaded as CSV/Excel. */
    public enum Report {
        OFFLINE, SIM, SIM_MISMATCH, APP_INSTALLS, APP_INSTALL_DEVICES, BY_APP, APPS_CATALOG,
        NO_COMMANDS, OUTDATED_AGENTS, OUTDATED_APPS, DATA_USAGE, NON_COMPLIANCE, DECOMMISSIONED,
        INVENTORY,
        COMMAND_LATENCY,
        DATA_LIMITS;

        static Report of(String name) {
            if (name == null) return null;
            try {
                return Report.valueOf(name.trim().toUpperCase().replace('-', '_'));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    // --- JSON endpoints ------------------------------------------------------------------------------------------

    @ApiOperation(value = "Equipos sin conexión", notes = "?days=N — equipos cuya última conexión es anterior a hoy − N días.")
    @GET
    @Path("/offline")
    @Produces(MediaType.APPLICATION_JSON)
    public Response offline(@QueryParam("days") Integer days) {
        return json(c -> offline(c, days));
    }

    @ApiOperation(value = "Retiro/cambio de SIM", notes = "?from=&to= (epoch ms) — eventos SIM en el rango.")
    @GET
    @Path("/sim")
    @Produces(MediaType.APPLICATION_JSON)
    public Response sim(@QueryParam("from") Long from, @QueryParam("to") Long to) {
        return json(c -> sim(c, from, to));
    }

    @ApiOperation(value = "Número de SIM no coincide", notes = "Equipos cuyo número real de la SIM difiere del número de celular esperado declarado en la inscripción.")
    @GET
    @Path("/sim-mismatch")
    @Produces(MediaType.APPLICATION_JSON)
    public Response simMismatch() {
        return json(this::simMismatch);
    }

    @ApiOperation(value = "Aplicaciones desplegadas", notes = "Agrega los comandos app.install por paquete.")
    @GET
    @Path("/app-installs")
    @Produces(MediaType.APPLICATION_JSON)
    public Response appInstalls() {
        return json(this::appInstalls);
    }

    @ApiOperation(value = "Despliegues de un paquete", notes = "?pkg= — equipos donde se desplegó esa app, con su estado.")
    @GET
    @Path("/app-install-devices")
    @Produces(MediaType.APPLICATION_JSON)
    public Response appInstallDevices(@QueryParam("pkg") String pkg) {
        return json(c -> appInstallDevices(c, pkg));
    }

    @ApiOperation(value = "Catálogo de apps instaladas", notes = "Paquetes que la flota reporta como instalados (para filtrar).")
    @GET
    @Path("/apps-catalog")
    @Produces(MediaType.APPLICATION_JSON)
    public Response appsCatalog() {
        return json(this::appsCatalog);
    }

    @ApiOperation(value = "Equipos por app instalada", notes = "?pkgs=a,b,c — equipos que tienen alguno de esos paquetes.")
    @GET
    @Path("/by-app")
    @Produces(MediaType.APPLICATION_JSON)
    public Response byApp(@QueryParam("pkgs") String pkgs) {
        return json(c -> byApp(c, pkgs));
    }

    @ApiOperation(value = "Equipos que no reciben comandos", notes = "?minutes=M — comando pendiente creado hace más de M minutos.")
    @GET
    @Path("/no-commands")
    @Produces(MediaType.APPLICATION_JSON)
    public Response noCommands(@QueryParam("minutes") Integer minutes) {
        return json(c -> noCommands(c, minutes));
    }

    @ApiOperation(value = "Agentes desactualizados", notes = "Equipos cuyo agente no es la última versión servida.")
    @GET
    @Path("/outdated-agents")
    @Produces(MediaType.APPLICATION_JSON)
    public Response outdatedAgents() {
        return json(this::outdatedAgents);
    }

    @ApiOperation(value = "Apps desactualizadas", notes = "Apps instaladas cuya versión difiere de la última de la biblioteca.")
    @GET
    @Path("/outdated-apps")
    @Produces(MediaType.APPLICATION_JSON)
    public Response outdatedApps() {
        return json(this::outdatedApps);
    }

    @ApiOperation(value = "Consumo de datos móviles", notes = "?days=N&limit=K — mayores consumidores por datos móviles.")
    @GET
    @Path("/data-usage")
    @Produces(MediaType.APPLICATION_JSON)
    public Response dataUsage(@QueryParam("days") Integer days, @QueryParam("limit") Integer limit) {
        return json(c -> dataUsage(c, days, limit));
    }

    @ApiOperation(value = "Datos móviles: uso vs. límite",
            notes = "Por equipo y por app con límite: consumo del periodo, límite de la política, porcentaje y estado.")
    @GET
    @Path("/data-limits")
    @Produces(MediaType.APPLICATION_JSON)
    public Response dataLimits() {
        return json(this::dataLimits);
    }

    @ApiOperation(value = "Incumplimiento de políticas", notes = "Equipos cuya revisión aplicada ≠ la revisión actual de su política.")
    @GET
    @Path("/non-compliance")
    @Produces(MediaType.APPLICATION_JSON)
    public Response nonCompliance() {
        return json(this::nonCompliance);
    }

    @ApiOperation(value = "Equipos dados de baja", notes = "?from=&to= (epoch ms) — equipos eliminados en el rango (device_tombstone).")
    @GET
    @Path("/decommissioned")
    @Produces(MediaType.APPLICATION_JSON)
    public Response decommissioned(@QueryParam("from") Long from, @QueryParam("to") Long to) {
        return json(c -> decommissioned(c, from, to));
    }

    @ApiOperation(value = "Latencia de comandos", notes = "?from=&to= (epoch ms)&groupId= — por tipo de comando: enviados, "
            + "% ejecutados y latencia p50/p90/máxima (segundos) de entrega y de ejecución, en el rango y la carpeta.")
    @GET
    @Path("/command-latency")
    @Produces(MediaType.APPLICATION_JSON)
    public Response commandLatency(@QueryParam("from") Long from, @QueryParam("to") Long to,
                                   @QueryParam("groupId") Integer groupId) {
        return json(c -> commandLatency(c, from, to, groupId));
    }

    // --- export (CSV / Excel) ------------------------------------------------------------------------------------

    @ApiOperation(value = "Exportar un reporte", notes = "?report=<nombre>&format=csv|xlsx + los parámetros del reporte.")
    @GET
    @Path("/export")
    @Produces({"text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"})
    public javax.ws.rs.core.Response export(@QueryParam("report") String reportName,
                                            @QueryParam("format") String format,
                                            @QueryParam("days") Integer days,
                                            @QueryParam("from") Long from,
                                            @QueryParam("to") Long to,
                                            @QueryParam("pkg") String pkg,
                                            @QueryParam("pkgs") String pkgs,
                                            @QueryParam("minutes") Integer minutes,
                                            @QueryParam("limit") Integer limit,
                                            @QueryParam("tz") String tz,
                                            @QueryParam("groupId") Integer groupId) {
        Optional<Integer> customerId = reader();
        if (!customerId.isPresent()) {
            return javax.ws.rs.core.Response.status(403).build();
        }
        Report rep = Report.of(reportName);
        if (rep == null || rep == Report.APPS_CATALOG) {
            return javax.ws.rs.core.Response.status(404).build();
        }
        final int c = customerId.get();
        final ReportData data;
        try {
            data = rep == Report.COMMAND_LATENCY ? commandLatency(c, from, to, groupId)
                    : scoped(build(rep, c, days, from, to, pkg, pkgs, minutes, limit));
        } catch (Exception e) {
            logger.warn("Report export {} failed for customer {}", reportName, c, e);
            return javax.ws.rs.core.Response.status(500).build();
        }
        final ZoneId zone = zoneOf(tz);
        boolean xlsx = !"csv".equalsIgnoreCase(format);
        final long now = System.currentTimeMillis();
        String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
                .format(java.time.Instant.ofEpochMilli(now).atZone(zone));
        String fileName = "reporte-" + rep.name().toLowerCase().replace('_', '-') + "-" + stamp
                + (xlsx ? ".xlsx" : ".csv");

        StreamingOutput body = out -> {
            if (xlsx) {
                ReportExport.writeXlsx(data.title, data.columns, data.rows, zone, now, out);
            } else {
                ReportExport.writeCsv(data.columns, data.rows, zone, out);
            }
        };
        String ctype = xlsx ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                : "text/csv; charset=utf-8";
        return javax.ws.rs.core.Response.ok(body)
                .header("Content-Type", ctype)
                .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .header("Cache-Control", "no-store")
                .build();
    }

    // --- dispatch ------------------------------------------------------------------------------------------------

    private ReportData build(Report rep, int c, Integer days, Long from, Long to, String pkg, String pkgs,
                             Integer minutes, Integer limit) {
        switch (rep) {
            case OFFLINE: return offline(c, days);
            case SIM: return sim(c, from, to);
            case SIM_MISMATCH: return simMismatch(c);
            case APP_INSTALLS: return appInstalls(c);
            case APP_INSTALL_DEVICES: return appInstallDevices(c, pkg);
            case BY_APP: return byApp(c, pkgs);
            case APPS_CATALOG: return appsCatalog(c);
            case NO_COMMANDS: return noCommands(c, minutes);
            case OUTDATED_AGENTS: return outdatedAgents(c);
            case OUTDATED_APPS: return outdatedApps(c);
            case DATA_USAGE: return dataUsage(c, days, limit);
            case NON_COMPLIANCE: return nonCompliance(c);
            case DECOMMISSIONED: return decommissioned(c, from, to);
            case INVENTORY: return inventory(c, null, null, null, days);
            case COMMAND_LATENCY: return commandLatency(c, from, to, null);
            case DATA_LIMITS: return dataLimits(c);
            default: throw new IllegalArgumentException("unknown report");
        }
    }

    // --- Command latency (evidence for the remote-command criteria) --------------------------------------------

    /**
     * Per command type, over the commands created in the range: how many were sent, how many ran, and the
     * p50/p90/maximum latency in seconds. The rows carry no device number, so the narrowing to a folder (and to a
     * folder administrator's own devices) happens here, before the figures are computed.
     */
    private ReportData commandLatency(int c, Long from, Long to, Integer groupId) {
        long[] r = range(from, to, 30);
        java.util.Set<String> visible = scope == null ? null : scope.deviceNumbers();
        java.util.Set<Integer> folder = null;
        String folderName = null;
        if (groupId != null) {
            folder = new java.util.HashSet<>(commandDAO.groupSubtree(c, groupId));
            folderName = groupPaths(c).get(groupId);
        }
        List<com.hmdm.util.CommandLatencyStats.Sample> samples = new ArrayList<>();
        for (Map<String, Object> row : reportsDAO.commandTimings(c, r[0], r[1])) {
            if (visible != null && !visible.contains(str(row.get("number")))) continue;
            if (folder != null && !folder.contains(intOf(row.get("groupid")))) continue;
            samples.add(new com.hmdm.util.CommandLatencyStats.Sample(str(row.get("type")), str(row.get("status")),
                    longOf(row.get("createdat")), longOf(row.get("deliveredat")), longOf(row.get("completedat"))));
        }
        List<Map<String, Object>> rows = com.hmdm.util.CommandLatencyStats.byType(samples);
        for (Map<String, Object> row : rows) {
            String type = str(row.get("type"));
            row.put("comando", com.hmdm.util.CommandLatencyStats.ALL.equals(type) ? "Todos los comandos"
                    : com.hmdm.util.CommandLabels.of(type));
        }
        List<Column> cols = Arrays.asList(
                col("comando", "Comando", "text"), col("type", "Tipo", "text"),
                col("total", "Enviados", "number"), col("done", "Ejecutados", "number"),
                col("donepct", "% ejecutados", "number"), col("failed", "Fallidos", "number"),
                col("expired", "Vencidos", "number"), col("cancelled", "Cancelados", "number"),
                col("open", "En curso", "number"),
                col("deliveryp50", "Entrega p50 (s)", "number"), col("deliveryp90", "Entrega p90 (s)", "number"),
                col("deliverymax", "Entrega máx. (s)", "number"),
                col("execp50", "Ejecución p50 (s)", "number"), col("execp90", "Ejecución p90 (s)", "number"),
                col("execmax", "Ejecución máx. (s)", "number"));
        String title = "Latencia de comandos" + (folderName == null ? "" : " — " + folderName);
        return new ReportData("command-latency", title, cols, rows);
    }

    private static Long longOf(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : null;
    }

    // --- 1. Offline --------------------------------------------------------------------------------------------

    private ReportData offline(int c, Integer days) {
        int d = clamp(days, 1, 1, 3660);
        long cutoff = System.currentTimeMillis() - d * 86_400_000L;
        List<Map<String, Object>> rows = reportsDAO.offlineDevices(c, cutoff);
        enrich(c, rows);
        List<Column> cols = Arrays.asList(
                col("name", "Equipo", "text"), col("number", "Número", "text"), col("group", "Carpeta", "text"),
                col("lastseen", "Última conexión", "datetime"), col("agentversion", "Agente", "text"),
                col("androidrelease", "Android", "text"));
        return new ReportData("offline", "Equipos sin conexión (≥ " + d + " días)", cols, rows);
    }

    // --- 2. SIM ------------------------------------------------------------------------------------------------

    private ReportData sim(int c, Long from, Long to) {
        long[] r = range(from, to, 30);
        List<Map<String, Object>> rows = reportsDAO.simEvents(c, r[0], r[1]);
        enrich(c, rows);
        for (Map<String, Object> row : rows) row.put("evento", simLabel(str(row.get("type"))));
        List<Column> cols = Arrays.asList(
                col("name", "Equipo", "text"), col("number", "Número", "text"), col("group", "Carpeta", "text"),
                col("evento", "Evento", "text"), col("ts", "Fecha", "datetime"), col("detail", "Detalle", "text"));
        return new ReportData("sim", "Retiro/cambio de SIM", cols, rows);
    }

    // --- 2b. SIM number does not match the expected one --------------------------------------------------------

    private ReportData simMismatch(int c) {
        List<Map<String, Object>> rows = reportsDAO.simMismatchDevices(c);
        enrich(c, rows);
        List<Column> cols = Arrays.asList(
                col("name", "Equipo", "text"), col("number", "Número", "text"), col("group", "Carpeta", "text"),
                col("expectedphone", "Celular esperado", "text"), col("simphone", "Celular de la SIM", "text"),
                col("simphoneat", "Verificado", "datetime"), col("lastseen", "Última conexión", "datetime"));
        return new ReportData("sim-mismatch", "Número de SIM no coincide", cols, rows);
    }

    // --- 3. App installs (aggregate + drill-down) --------------------------------------------------------------

    private ReportData appInstalls(int c) {
        List<Map<String, Object>> rows = scopedAggregate(reportsDAO::appInstallAggregate,
                reportsDAO::appInstallAggregateScoped, c);
        List<Column> cols = Arrays.asList(
                col("pkg", "Paquete", "text"), col("devices", "Equipos", "number"),
                col("commands", "Despliegues", "number"), col("done", "Exitosos", "number"),
                col("failed", "Fallidos", "number"), col("pending", "Pendientes", "number"),
                col("lastat", "Último", "datetime"));
        return new ReportData("app-installs", "Aplicaciones desplegadas (app.install)", cols, rows);
    }

    private ReportData appInstallDevices(int c, String pkg) {
        List<Map<String, Object>> rows = pkg == null || pkg.trim().isEmpty()
                ? new ArrayList<>() : reportsDAO.appInstallDevices(c, pkg.trim());
        enrich(c, rows);
        for (Map<String, Object> row : rows) row.put("estado", statusLabel(str(row.get("status"))));
        List<Column> cols = Arrays.asList(
                col("name", "Equipo", "text"), col("number", "Número", "text"), col("group", "Carpeta", "text"),
                col("estado", "Estado", "text"), col("createdat", "Creado", "datetime"),
                col("completedat", "Completado", "datetime"), col("detail", "Detalle", "text"));
        return new ReportData("app-install-devices", "Despliegues de " + (pkg == null ? "" : pkg), cols, rows);
    }

    // --- 4. By app (catalogue + devices) -----------------------------------------------------------------------

    private ReportData appsCatalog(int c) {
        List<Map<String, Object>> rows = scopedAggregate(reportsDAO::installedAppsCatalog,
                reportsDAO::installedAppsCatalogScoped, c);
        List<Column> cols = Arrays.asList(
                col("pkg", "Paquete", "text"), col("name", "Nombre", "text"), col("devices", "Equipos", "number"));
        return new ReportData("apps-catalog", "Catálogo de apps instaladas", cols, rows);
    }

    private ReportData byApp(int c, String pkgs) {
        List<Map<String, Object>> grouped = new ArrayList<>();
        if (pkgs != null && !pkgs.trim().isEmpty()) {
            String clean = Arrays.stream(pkgs.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.joining(","));
            List<Map<String, Object>> flat = clean.isEmpty() ? new ArrayList<>() : reportsDAO.devicesByApp(c, clean);
            Map<Object, Map<String, Object>> byDevice = new LinkedHashMap<>();
            for (Map<String, Object> row : flat) {
                Object id = row.get("id");
                Map<String, Object> agg = byDevice.computeIfAbsent(id, k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", row.get("id"));
                    m.put("number", row.get("number"));
                    m.put("description", row.get("description"));
                    m.put("groupid", row.get("groupid"));
                    m.put("_apps", new ArrayList<String>());
                    return m;
                });
                @SuppressWarnings("unchecked")
                List<String> apps = (List<String>) agg.get("_apps");
                String name = str(row.get("name"));
                String version = str(row.get("version"));
                String pkg = str(row.get("pkg"));
                String label = (name != null && !name.isEmpty() ? name : pkg) + (version != null && !version.isEmpty() ? " (" + version + ")" : "");
                apps.add(label);
            }
            for (Map<String, Object> agg : byDevice.values()) {
                @SuppressWarnings("unchecked")
                List<String> apps = (List<String>) agg.remove("_apps");
                agg.put("apps", String.join(", ", apps));
                agg.put("appcount", apps.size());
                grouped.add(agg);
            }
        }
        enrich(c, grouped);
        List<Column> cols = Arrays.asList(
                col("name", "Equipo", "text"), col("number", "Número", "text"), col("group", "Carpeta", "text"),
                col("apps", "Apps", "text"), col("appcount", "# apps", "number"));
        return new ReportData("by-app", "Equipos por app instalada", cols, grouped);
    }

    // --- 5. No commands ----------------------------------------------------------------------------------------

    private ReportData noCommands(int c, Integer minutes) {
        int m = clamp(minutes, 60, 1, 60 * 24 * 60);
        long cutoff = System.currentTimeMillis() - m * 60_000L;
        List<Map<String, Object>> rows = reportsDAO.noCommandDevices(c, cutoff);
        enrich(c, rows);
        for (Map<String, Object> row : rows) row.put("lastseen", maxLong(row.get("lastupdate"), row.get("stateat")));
        List<Column> cols = Arrays.asList(
                col("name", "Equipo", "text"), col("number", "Número", "text"), col("group", "Carpeta", "text"),
                col("pending", "Pendientes", "number"), col("oldestat", "Más antiguo", "datetime"),
                col("firsttype", "Comando", "text"), col("lastseen", "Última conexión", "datetime"));
        return new ReportData("no-commands", "Equipos que no reciben comandos (≥ " + m + " min)", cols, rows);
    }

    // --- 6a. Outdated agents -----------------------------------------------------------------------------------

    private ReportData outdatedAgents(int c) {
        String latest = agentPackage == null ? null : agentPackage.latestVersion();
        List<Map<String, Object>> rows = latest == null ? new ArrayList<>() : reportsDAO.outdatedAgents(c, latest);
        enrich(c, rows);
        for (Map<String, Object> row : rows) row.put("latest", latest);
        List<Column> cols = Arrays.asList(
                col("name", "Equipo", "text"), col("number", "Número", "text"), col("group", "Carpeta", "text"),
                col("agentversion", "Agente", "text"), col("latest", "Última", "text"),
                col("stateat", "Reportado", "datetime"));
        String title = latest == null ? "Agentes desactualizados (no hay APK de agente servido)"
                : "Agentes desactualizados (última: " + latest + ")";
        return new ReportData("outdated-agents", title, cols, rows);
    }

    // --- 6b. Outdated apps -------------------------------------------------------------------------------------

    private ReportData outdatedApps(int c) {
        List<Map<String, Object>> rows = reportsDAO.outdatedApps(c);
        enrich(c, rows);
        List<Column> cols = Arrays.asList(
                col("name", "Equipo", "text"), col("number", "Número", "text"), col("group", "Carpeta", "text"),
                col("appname", "App", "text"), col("pkg", "Paquete", "text"),
                col("installed", "Instalada", "text"), col("latest", "Biblioteca", "text"));
        return new ReportData("outdated-apps", "Apps desactualizadas", cols, rows);
    }

    // --- 7. Data usage -----------------------------------------------------------------------------------------

    private ReportData dataUsage(int c, Integer days, Integer limit) {
        int d = clamp(days, 30, 1, 90);
        int k = clamp(limit, 50, 1, 1000);
        String fromDay = LocalDate.now(DEFAULT_ZONE).minusDays(d).toString();
        List<Map<String, Object>> rows = reportsDAO.dataUsage(c, fromDay, k);
        enrich(c, rows);
        List<Column> cols = Arrays.asList(
                col("name", "Equipo", "text"), col("number", "Número", "text"), col("group", "Carpeta", "text"),
                col("mobilebytes", "Datos móviles", "bytes"), col("wifibytes", "Wi-Fi", "bytes"),
                col("lastday", "Último día", "text"));
        return new ReportData("data-usage", "Consumo de datos móviles (" + d + " días)", cols, rows);
    }

    // --- 7b. Mobile data: use against the policy's limits -------------------------------------------------------

    private ReportData dataLimits(int c) {
        com.hmdm.rest.resource.support.MobileDataService service = com.hmdm.rest.resource.support.MobileDataService.INSTANCE;
        List<Map<String, Object>> rows = service == null ? new ArrayList<>() : service.fleetRows(c);
        enrich(c, rows);
        List<Column> cols = Arrays.asList(
                col("name", "Equipo", "text"), col("number", "Número", "text"), col("group", "Carpeta", "text"),
                col("configurationname", "Política", "text"), col("ambito", "Ámbito", "text"), col("app", "App", "text"),
                col("pkg", "Paquete", "text"), col("usedbytes", "Consumo del periodo", "bytes"),
                col("limitbytes", "Límite", "bytes"), col("percent", "% usado", "number"), col("estado", "Estado", "text"),
                col("blocked", "Apps sin datos móviles", "number"), col("mechanism", "Mecanismo", "text"),
                col("periodstart", "Periodo desde", "text"), col("source", "Dato de", "text"));
        return new ReportData("data-limits", "Datos móviles: uso vs. límite", cols, rows);
    }

    // --- 8. Non-compliance -------------------------------------------------------------------------------------

    private ReportData nonCompliance(int c) {
        List<Map<String, Object>> all = reportsDAO.complianceRows(c);
        // Current revision per configuration: computed once from a representative device (bounded cost).
        // Kiosk policies whose branding varies per folder are an accepted approximation (same caveat noted in docs).
        Map<Object, String> revByConfig = new HashMap<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : all) {
            Object cfgId = row.get("configurationid");
            String current = revByConfig.get(cfgId);
            if (!revByConfig.containsKey(cfgId)) {
                current = null;
                try {
                    Device dev = unsecureDAO.getDeviceByNumber(str(row.get("number")));
                    if (dev != null) current = reconciler.currentRevision(dev);
                } catch (Exception e) {
                    logger.debug("currentRevision failed for config {}", cfgId, e);
                }
                revByConfig.put(cfgId, current);
            }
            String applied = str(row.get("appliedrevision"));
            boolean compliant = current != null && current.equals(applied);
            if (compliant) continue; // only the devices that are out of sync
            row.put("currentrevision", current == null ? "?" : current);
            row.put("appliedrevision", applied == null ? "(nunca)" : applied);
            row.put("estado", current == null ? "Sin determinar" : (applied == null ? "Nunca aplicada" : "Desfasada"));
            out.add(row);
        }
        enrich(c, out);
        List<Column> cols = Arrays.asList(
                col("name", "Equipo", "text"), col("number", "Número", "text"), col("group", "Carpeta", "text"),
                col("configurationname", "Política", "text"), col("estado", "Estado", "text"),
                col("appliedrevision", "Revisión aplicada", "text"), col("currentrevision", "Revisión actual", "text"),
                col("appliedat", "Aplicada", "datetime"));
        return new ReportData("non-compliance", "Incumplimiento de políticas", cols, out);
    }

    // --- 9. Decommissioned -------------------------------------------------------------------------------------

    private ReportData decommissioned(int c, Long from, Long to) {
        long[] r = range(from, to, 90);
        List<Map<String, Object>> rows = reportsDAO.decommissioned(c, r[0], r[1]);
        enrich(c, rows);
        for (Map<String, Object> row : rows) {
            Object cl = row.get("claimed");
            row.put("reclamado", (cl instanceof Boolean && (Boolean) cl) ? "Sí" : "No");
        }
        List<Column> cols = Arrays.asList(
                col("name", "Equipo", "text"), col("number", "Número", "text"), col("group", "Carpeta", "text"),
                col("deletedat", "Dado de baja", "datetime"), col("reclamado", "Reclamado", "text"));
        return new ReportData("decommissioned", "Equipos dados de baja", cols, rows);
    }

    // --- 10. Fleet inventory (battery, storage, Android, connection, network of every device) ---------------------

    /** Builds the inventory (shared with the device list's Excel/CSV and the scheduled e-mails). */
    @Inject
    private com.hmdm.rest.resource.support.FleetExportService exportService;

    @ApiOperation(value = "Inventario de la flota", notes = "Un equipo por fila con su último reporte (conexión, batería, "
            + "almacenamiento, Android, red, identificadores) y un resumen: por versión de Android, por modelo, batería "
            + "baja, almacenamiento casi lleno, sin conexión > N días y agente desactualizado. "
            + "?group=<carpeta>&lowBattery=20&storagePct=90&offlineDays=7")
    @GET
    @Path("/inventory")
    @Produces(MediaType.APPLICATION_JSON)
    public Response inventory(@QueryParam("group") Integer group, @QueryParam("lowBattery") Integer lowBattery,
                              @QueryParam("storagePct") Integer storagePct, @QueryParam("offlineDays") Integer offlineDays) {
        return json(c -> inventory(c, group, lowBattery, storagePct, offlineDays));
    }

    @ApiOperation(value = "Exportar el inventario de la flota", notes = "?format=xlsx (libro con Resumen, Dispositivos, "
            + "Carpetas y Conexiones) | csv (hoja Dispositivos; ?sep=; , tab pipe). Mismos filtros que /inventory.")
    @GET
    @Path("/inventory/export")
    @Produces({"text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"})
    public javax.ws.rs.core.Response inventoryExport(@QueryParam("format") String format, @QueryParam("sep") String sep,
                                                     @QueryParam("group") Integer group,
                                                     @QueryParam("lowBattery") Integer lowBattery,
                                                     @QueryParam("storagePct") Integer storagePct,
                                                     @QueryParam("offlineDays") Integer offlineDays,
                                                     @QueryParam("tz") String tz) {
        Optional<Integer> customerId = reader();
        if (!customerId.isPresent()) {
            return javax.ws.rs.core.Response.status(403).build();
        }
        com.hmdm.rest.resource.support.FleetExportService.Built built =
                inventoryInput(customerId.get(), group, lowBattery, storagePct, offlineDays, tz);
        if (built == null) {
            return javax.ws.rs.core.Response.status(404).build();
        }
        final com.hmdm.util.DeviceExport.Input input = built.input;
        final boolean csv = "csv".equalsIgnoreCase(format);
        final char separator = ReportExport.separator(sep);
        StreamingOutput body = csv ? o -> com.hmdm.util.DeviceExport.writeCsv(input, separator, o)
                : o -> com.hmdm.util.DeviceExport.write(input, o);
        return javax.ws.rs.core.Response.ok(body)
                .header("Content-Type", csv ? "text/csv; charset=utf-8"
                        : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .header("Content-Disposition", "attachment; filename=\"inventario-"
                        + built.fileBase.replaceFirst("^dispositivos-?", "") + (csv ? ".csv" : ".xlsx") + "\"")
                .header("Cache-Control", "no-store")
                .build();
    }

    private com.hmdm.rest.resource.support.FleetExportService.Built inventoryInput(
            int c, Integer group, Integer lowBattery, Integer storagePct, Integer offlineDays, String tz) {
        com.hmdm.rest.resource.support.FleetExportService.Request rq =
                new com.hmdm.rest.resource.support.FleetExportService.Request();
        rq.groupId = group;
        rq.tz = tz;
        rq.days = 1; // the inventory needs only the open sessions, not the month of history
        rq.lowBatteryPct = lowBattery;
        rq.storageUsedPct = storagePct;
        rq.offlineDays = offlineDays;
        // A folder administrator gets only their folders' devices (and the summary counts only those).
        return exportService.build(c, rq, scope == null ? null : scope.deviceNumbers(), scope == null ? null : scope.groups());
    }

    private ReportData inventory(int c, Integer group, Integer lowBattery, Integer storagePct, Integer offlineDays) {
        com.hmdm.rest.resource.support.FleetExportService.Built built =
                inventoryInput(c, group, lowBattery, storagePct, offlineDays, null);
        if (built == null) {
            return new ReportData("inventory", "Inventario de la flota", com.hmdm.util.FleetInventory.columns(), new ArrayList<>());
        }
        com.hmdm.util.DeviceExport.Inventory inv = com.hmdm.util.DeviceExport.inventory(built.input);
        ReportData d = new ReportData("inventory", "Inventario de la flota · " + built.input.scopeLabel, inv.columns, inv.rows);
        d.summary = inv.summary;
        return d;
    }

    /**
     * A report with its default filters for the scheduled e-mails (no signed-in user: the caller narrows the rows
     * to the schedule's folder). Null for a report that is not a per-device list.
     */
    public ReportData scheduled(String reportName, int customerId) {
        Report rep = Report.of(reportName);
        if (rep == null || rep == Report.APPS_CATALOG || rep == Report.APP_INSTALLS || rep == Report.APP_INSTALL_DEVICES
                || rep == Report.BY_APP || rep == Report.INVENTORY) {
            return null;
        }
        return build(rep, customerId, null, null, null, null, null, null, null);
    }

    // --- shared helpers ------------------------------------------------------------------------------------------

    /**
     * Reader = any signed-in user of a customer. Full administrators see the whole fleet; folder administrators
     * are allowed too, but every report they get is narrowed to their own devices by {@link #scoped} (a report row
     * that carries a device number is dropped unless that device is in {@link UserScope#deviceNumbers()}).
     */
    private Optional<Integer> reader() {
        return SecurityContext.get() == null ? Optional.empty()
                : SecurityContext.get().getCurrentCustomerId();
    }

    /**
     * Narrow a report to the devices the signed-in user may reach. For a folder administrator, keep only the rows
     * whose device number is in their scope; rows that carry no device number (package-level aggregates such as
     * app-installs or apps-catalog) are left as-is — those report fleet-wide counts, not other folders' devices.
     */
    private ReportData scoped(ReportData d) {
        if (scope == null || !scope.restricted()) return d;
        java.util.Set<String> visible = scope.deviceNumbers();
        if (visible == null) return d;
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> row : d.rows) {
            Object num = row.containsKey("number") ? row.get("number")
                    : (row.containsKey("devicenumber") ? row.get("devicenumber") : null);
            if (num == null) { kept.add(row); continue; } // aggregate row (no device): no device leak
            if (visible.contains(String.valueOf(num))) kept.add(row);
        }
        ReportData out = new ReportData(d.report, d.title, d.columns, kept);
        out.summary = d.summary;
        return out;
    }

    /**
     * Package-level aggregates (app-installs, apps-catalog) carry no device number, so {@link #scoped} can't
     * narrow them after the fact — a folder administrator would otherwise see fleet-wide counts. For a restricted
     * user, run the device-scoped query against their own device numbers (empty scope ⇒ empty report, never the
     * whole fleet). A full administrator keeps the plain fleet-wide query.
     */
    private List<Map<String, Object>> scopedAggregate(java.util.function.IntFunction<List<Map<String, Object>>> fleet,
                                                       java.util.function.BiFunction<Integer, String, List<Map<String, Object>>> scopedByDevs,
                                                       int c) {
        if (scope != null && scope.restricted()) {
            java.util.Set<String> devs = scope.deviceNumbers();
            if (devs == null || devs.isEmpty()) return new ArrayList<>();
            return scopedByDevs.apply(c, String.join(",", devs));
        }
        return fleet.apply(c);
    }

    private interface Builder {
        ReportData build(int customerId);
    }

    private Response json(Builder b) {
        Optional<Integer> customerId = reader();
        if (!customerId.isPresent()) return Response.PERMISSION_DENIED();
        try {
            return Response.OK(toMap(scoped(b.build(customerId.get()))));
        } catch (Exception e) {
            logger.warn("Report failed for customer {}", customerId.get(), e);
            return Response.ERROR("error.report.failed");
        }
    }

    /** Plain maps so JSON serialisation never depends on Jackson field visibility. */
    private static Map<String, Object> toMap(ReportData d) {
        List<Map<String, Object>> cols = new ArrayList<>();
        for (Column c : d.columns) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", c.key);
            m.put("label", c.label);
            m.put("type", c.type);
            cols.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("report", d.report);
        out.put("title", d.title);
        out.put("generatedAt", d.generatedAt);
        out.put("columns", cols);
        out.put("rows", d.rows);
        if (d.summary != null) out.put("summary", d.summary);
        return out;
    }

    /** Add a display name and a folder-path to every device row that carries {@code groupid}. */
    private void enrich(int customerId, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return;
        Map<Integer, String> paths = groupPaths(customerId);
        for (Map<String, Object> row : rows) {
            if (row.containsKey("number") || row.containsKey("description")) {
                row.put("name", nonBlank(str(row.get("description")), str(row.get("number"))));
            }
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

    /** [from, to] in epoch millis; defaults to the last {@code defaultDays} days up to now. */
    private static long[] range(Long from, Long to, int defaultDays) {
        long now = System.currentTimeMillis();
        long t = to == null ? now : to;
        long f = from == null ? t - defaultDays * 86_400_000L : from;
        if (f > t) {
            long tmp = f;
            f = t;
            t = tmp;
        }
        return new long[]{f, t};
    }

    private static int clamp(Integer v, int def, int min, int max) {
        int x = v == null ? def : v;
        return Math.max(min, Math.min(max, x));
    }

    private static long maxLong(Object a, Object b) {
        long x = a instanceof Number ? ((Number) a).longValue() : 0L;
        long y = b instanceof Number ? ((Number) b).longValue() : 0L;
        return Math.max(x, y);
    }

    private static String simLabel(String type) {
        if (type == null) return "";
        switch (type) {
            case "simRemoved": return "SIM retirada";
            case "simChanged": return "SIM cambiada";
            case "simInserted": return "SIM insertada";
            default: return type;
        }
    }

    private static String statusLabel(String status) {
        if (status == null) return "";
        switch (status) {
            case "pending": return "Pendiente";
            case "delivered": return "Entregado";
            case "done": return "Exitoso";
            case "failed": return "Fallido";
            case "unsupported": return "No soportado";
            case "expired": return "Expirado";
            case "cancelled": return "Cancelado";
            default: return status;
        }
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
