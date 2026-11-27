/*
 *
 * DallyControl — Auditoría (new console module, not part of upstream Headwind).
 *
 * Read-only API over the audit trail: the paginated search with its filters, the Excel / CSV export, the activity of
 * one device and of one user, the retention setting and the integrity check of the hash chain. There is no call that
 * edits or deletes a row — the table itself refuses it.
 *
 * Needs the {@code view_audit} permission. A folder administrator who holds it sees only the rows about their own
 * folders (devices in them, the folders themselves) and the rows of the users they manage (folder users whose folders
 * are all inside theirs, and themself); everything else is filtered in SQL, so counts and exports are scoped too.
 *
 */

package com.hmdm.rest.resource;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdm.audit.AuditCatalog;
import com.hmdm.audit.AuditTargets;
import com.hmdm.audit.AuditTrailService;
import com.hmdm.persistence.AuditTrailDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AuditEntry;
import com.hmdm.persistence.domain.AuditQuery;
import com.hmdm.persistence.domain.User;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.resource.support.UserScope;
import com.hmdm.security.SecurityContext;
import com.hmdm.util.AuditHash;
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
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.StreamingOutput;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Singleton
@Path("/private/audit/v1")
@Api(tags = {"DallyControl audit trail"})
public class AuditTrailResource {
    private static final Logger logger = LoggerFactory.getLogger(AuditTrailResource.class);

    public static final String PERMISSION = "view_audit";
    private static final int MAX_PAGE = 200;
    private static final int MAX_EXPORT = 50000;
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("America/Bogota");
    public static final int MIN_RETENTION_DAYS = 30;
    public static final int MAX_RETENTION_DAYS = 3650;

    private AuditTrailDAO dao;
    private UserScope scope;
    private UnsecureDAO unsecureDAO;
    private AuditTrailService service;

    /** A constructor required by Swagger. */
    public AuditTrailResource() {
    }

    @Inject
    public AuditTrailResource(AuditTrailDAO dao, UserScope scope, UnsecureDAO unsecureDAO, AuditTrailService service) {
        this.dao = dao;
        this.scope = scope;
        this.unsecureDAO = unsecureDAO;
        this.service = service;
    }

    // --- who may see what ----------------------------------------------------------------------------------------

    private Optional<User> viewer() {
        SecurityContext ctx = SecurityContext.get();
        if (ctx == null || !ctx.getCurrentUser().isPresent() || !ctx.hasPermission(PERMISSION)) return Optional.empty();
        return ctx.getCurrentUser();
    }

    /** A query limited to what this user may see; the caller adds the filters. */
    private AuditQuery scopedQuery(User me) {
        AuditQuery q = new AuditQuery();
        q.customerId = me.getCustomerId();
        if (!UserScope.isRestricted(me)) {
            // Rows without a customer (a failed sign-in with an unknown user name): shown where there is one organisation.
            q.includeNoCustomer = SecurityContext.get().isSuperAdmin() || unsecureDAO.isSingleCustomer();
            return q;
        }
        Set<Integer> groups = scope.groups();
        q.scopeGroups = groups == null || groups.isEmpty() ? "{}" : intArray(groups);
        q.scopeUsers = managedUsers(me, groups);
        return q;
    }

    /** The folder users whose folders are all inside {@code groups}, plus the viewer. */
    private List<Integer> managedUsers(User me, Set<Integer> groups) {
        List<Integer> out = new ArrayList<>();
        out.add(me.getId());
        if (groups == null || groups.isEmpty()) return out;
        Map<Integer, Boolean> inside = new LinkedHashMap<>();
        for (Map<String, Object> r : dao.lookups().folderUsers(me.getCustomerId())) {
            Integer userId = intOf(r.get("userid"));
            Integer groupId = intOf(r.get("groupid"));
            if (userId == null) continue;
            boolean ok = groupId != null && groups.contains(groupId);
            inside.merge(userId, ok, (a, b) -> a && b);
        }
        for (Map.Entry<Integer, Boolean> e : inside.entrySet()) {
            if (e.getValue() && !out.contains(e.getKey())) out.add(e.getKey());
        }
        return out;
    }

    private static String intArray(Set<Integer> values) {
        StringBuilder sb = new StringBuilder("{");
        int i = 0;
        for (Integer v : values) {
            if (v == null) continue;
            if (i++ > 0) sb.append(',');
            sb.append(v.intValue());
        }
        return sb.append('}').toString();
    }

    private static void filters(AuditQuery q, Long from, Long to, Integer userId, String category, String action,
                                String result, String targetType, String targetId, String device, Integer group, String text) {
        q.from = from;
        q.to = to;
        q.userId = userId;
        q.category = blank(category);
        q.action = blank(action);
        String r = blank(result);
        q.result = r == null ? null : ("ok".equals(r) || "denied".equals(r) || "error".equals(r) ? r : "none");
        q.targetType = blank(targetType);
        q.targetId = blank(targetId);
        q.deviceNumber = blank(device);
        q.groupId = group;
        String t = blank(text);
        if (t != null) {
            if (t.length() > 100) t = t.substring(0, 100);
            q.q = "%" + t.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        }
    }

    private static String blank(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private static Integer intOf(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).intValue();
        try {
            return Integer.valueOf(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // --- search --------------------------------------------------------------------------------------------------

    @ApiOperation(value = "Buscar en la auditoría", notes = "Filtros opcionales: from/to (epoch ms), user (id), category, "
            + "action, result (ok|denied|error), targetType, targetId, device (número), group (id de carpeta), q (texto). "
            + "Paginado en servidor: page (desde 1), pageSize (máx. 200).")
    @GET
    @Path("/log")
    @Produces(MediaType.APPLICATION_JSON)
    public Response log(@QueryParam("from") Long from, @QueryParam("to") Long to, @QueryParam("user") Integer userId,
                        @QueryParam("category") String category, @QueryParam("action") String action,
                        @QueryParam("result") String result, @QueryParam("targetType") String targetType,
                        @QueryParam("targetId") String targetId, @QueryParam("device") String device,
                        @QueryParam("group") Integer group, @QueryParam("q") String text,
                        @QueryParam("page") Integer page, @QueryParam("pageSize") Integer pageSize) {
        Optional<User> me = viewer();
        if (!me.isPresent()) return Response.PERMISSION_DENIED();
        try {
            AuditQuery q = scopedQuery(me.get());
            filters(q, from, to, userId, category, action, result, targetType, targetId, device, group, text);
            return Response.OK(page(q, page, pageSize));
        } catch (Exception e) {
            logger.error("Audit search failed", e);
            return Response.INTERNAL_ERROR();
        }
    }

    private Map<String, Object> page(AuditQuery q, Integer page, Integer pageSize) {
        int size = pageSize == null ? 50 : Math.max(1, Math.min(MAX_PAGE, pageSize));
        int p = page == null ? 1 : Math.max(1, page);
        q.limit = size;
        q.offset = (p - 1) * size;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", dao.search(q));
        out.put("total", dao.count(q));
        out.put("page", p);
        out.put("pageSize", size);
        return out;
    }

    @ApiOperation(value = "Valores de los filtros", notes = "Módulos, tipos de objetivo, y los técnicos y acciones que "
            + "aparecen en lo que el usuario puede ver.")
    @GET
    @Path("/facets")
    @Produces(MediaType.APPLICATION_JSON)
    public Response facets() {
        Optional<User> me = viewer();
        if (!me.isPresent()) return Response.PERMISSION_DENIED();
        try {
            AuditQuery q = scopedQuery(me.get());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("categories", AuditCatalog.CATEGORIES);
            Map<String, String> types = new LinkedHashMap<>();
            for (Map.Entry<String, AuditTargets.Type> t : AuditTargets.all().entrySet()) {
                if (!"deviceId".equals(t.getKey())) types.put(t.getKey(), t.getValue().label);
            }
            types.put("devices", "Varios dispositivos");
            out.put("targetTypes", types);
            out.put("users", dao.users(q));
            out.put("actions", dao.actions(q));
            out.put("retentionDays", dao.retentionDays(me.get().getCustomerId()));
            out.put("scoped", UserScope.isRestricted(me.get()));
            return Response.OK(out);
        } catch (Exception e) {
            logger.error("Audit facets failed", e);
            return Response.INTERNAL_ERROR();
        }
    }

    // --- activity of one device / one user -----------------------------------------------------------------------

    @ApiOperation(value = "Actividad sobre un dispositivo", notes = "Lo que cada técnico hizo sobre este equipo.")
    @GET
    @Path("/devices/{number}/activity")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deviceActivity(@PathParam("number") String number, @QueryParam("page") Integer page,
                                   @QueryParam("pageSize") Integer pageSize) {
        Optional<User> me = viewer();
        if (!me.isPresent()) return Response.PERMISSION_DENIED();
        try {
            Map<String, Object> device = dao.lookups().deviceByNumber(number);
            Integer owner = device == null ? null : intOf(device.get("customerid"));
            if (owner != null && owner != me.get().getCustomerId()) return Response.PERMISSION_DENIED();
            AuditQuery q = scopedQuery(me.get());
            q.includeNoCustomer = false;
            q.deviceNumber = number;
            return Response.OK(page(q, page, pageSize == null ? 30 : pageSize));
        } catch (Exception e) {
            logger.error("Audit device activity failed", e);
            return Response.INTERNAL_ERROR();
        }
    }

    @ApiOperation(value = "Actividad reciente de un usuario")
    @GET
    @Path("/users/{id}/activity")
    @Produces(MediaType.APPLICATION_JSON)
    public Response userActivity(@PathParam("id") int id, @QueryParam("page") Integer page,
                                 @QueryParam("pageSize") Integer pageSize) {
        Optional<User> me = viewer();
        if (!me.isPresent()) return Response.PERMISSION_DENIED();
        try {
            Map<String, Object> target = dao.lookups().userById(id);
            Integer owner = target == null ? null : intOf(target.get("customerid"));
            if (owner == null || owner != me.get().getCustomerId()) return Response.PERMISSION_DENIED();
            AuditQuery q = scopedQuery(me.get());
            // A folder administrator only opens the users they manage.
            if (q.scopeUsers != null && !q.scopeUsers.contains(id)) return Response.PERMISSION_DENIED();
            q.includeNoCustomer = false;
            q.userId = id;
            return Response.OK(page(q, page, pageSize == null ? 30 : pageSize));
        } catch (Exception e) {
            logger.error("Audit user activity failed", e);
            return Response.INTERNAL_ERROR();
        }
    }

    // --- export --------------------------------------------------------------------------------------------------

    private static List<Column> columns() {
        List<Column> c = new ArrayList<>();
        c.add(new Column("createdat", "Fecha y hora", "datetime"));
        c.add(new Column("tecnico", "Técnico", "text"));
        c.add(new Column("login", "Usuario", "text"));
        c.add(new Column("rol", "Rol", "text"));
        c.add(new Column("ip", "IP", "text"));
        c.add(new Column("modulo", "Módulo", "text"));
        c.add(new Column("accion", "Acción", "text"));
        c.add(new Column("tipo", "Tipo de objetivo", "text"));
        c.add(new Column("objetivo", "Objetivo", "text"));
        c.add(new Column("idobjetivo", "Id del objetivo", "text"));
        c.add(new Column("resultado", "Resultado", "text"));
        c.add(new Column("mensaje", "Mensaje", "text"));
        c.add(new Column("detalle", "Detalle del cambio", "text"));
        c.add(new Column("agente", "Navegador / agente de usuario", "text"));
        c.add(new Column("hash", "Huella (hash)", "text"));
        return c;
    }

    static String resultLabel(String r) {
        if ("ok".equals(r)) return "Éxito";
        if ("denied".equals(r)) return "Denegado";
        if ("error".equals(r)) return "Error";
        return r == null ? "" : r;
    }

    static String targetTypeLabel(String t) {
        if (t == null) return "";
        if ("devices".equals(t)) return "Varios dispositivos";
        AuditTargets.Type type = AuditTargets.of(t);
        return type == null ? t : type.label;
    }

    private static Map<String, Object> exportRow(AuditEntry e) {
        Map<String, Object> r = new HashMap<>();
        r.put("createdat", e.createdAt);
        r.put("tecnico", e.userName == null ? "" : e.userName);
        r.put("login", e.login == null ? "" : e.login);
        r.put("rol", e.roleName == null ? "" : e.roleName);
        r.put("ip", e.ip == null ? "" : e.ip);
        String module = AuditCatalog.CATEGORIES.get(e.category);
        r.put("modulo", module == null ? e.category : module);
        r.put("accion", e.label);
        r.put("tipo", targetTypeLabel(e.targetType));
        r.put("objetivo", e.targetName == null ? "" : e.targetName);
        r.put("idobjetivo", e.targetId == null ? "" : e.targetId);
        r.put("resultado", resultLabel(e.result));
        r.put("mensaje", e.message == null ? "" : e.message);
        r.put("detalle", e.detail == null ? "" : e.detail);
        r.put("agente", e.userAgent == null ? "" : e.userAgent);
        r.put("hash", e.hash == null ? "" : e.hash);
        return r;
    }

    @ApiOperation(value = "Exportar la auditoría", notes = "?format=xlsx|csv (+ sep para CSV) y los mismos filtros que la "
            + "búsqueda. Como máximo 50 000 filas, las más recientes. La exportación queda registrada en la auditoría.")
    @GET
    @Path("/export")
    @Produces({"text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"})
    public javax.ws.rs.core.Response export(@QueryParam("format") String format, @QueryParam("sep") String sep,
                                            @QueryParam("tz") String tz,
                                            @QueryParam("from") Long from, @QueryParam("to") Long to,
                                            @QueryParam("user") Integer userId, @QueryParam("category") String category,
                                            @QueryParam("action") String action, @QueryParam("result") String result,
                                            @QueryParam("targetType") String targetType,
                                            @QueryParam("targetId") String targetId, @QueryParam("device") String device,
                                            @QueryParam("group") Integer group, @QueryParam("q") String text) {
        Optional<User> me = viewer();
        if (!me.isPresent()) return javax.ws.rs.core.Response.status(403).build();
        final List<Map<String, Object>> rows = new ArrayList<>();
        try {
            AuditQuery q = scopedQuery(me.get());
            filters(q, from, to, userId, category, action, result, targetType, targetId, device, group, text);
            q.limit = 1000;
            for (q.offset = 0; q.offset < MAX_EXPORT; q.offset += q.limit) {
                List<AuditEntry> chunk = dao.search(q);
                for (AuditEntry e : chunk) rows.add(exportRow(e));
                if (chunk.size() < q.limit) break;
            }
        } catch (Exception e) {
            logger.error("Audit export failed", e);
            return javax.ws.rs.core.Response.status(500).build();
        }
        ZoneId z;
        try {
            z = tz == null || tz.trim().isEmpty() ? DEFAULT_ZONE : ZoneId.of(tz.trim());
        } catch (Exception e) {
            z = DEFAULT_ZONE;
        }
        final ZoneId zone = z;
        final List<Column> cols = columns();
        final boolean xlsx = !"csv".equalsIgnoreCase(format);
        final char separator = ReportExport.separator(sep);
        final long now = System.currentTimeMillis();
        String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
                .format(java.time.Instant.ofEpochMilli(now).atZone(zone));
        String fileName = "auditoria-" + stamp + (xlsx ? ".xlsx" : ".csv");
        StreamingOutput out = os -> {
            if (xlsx) ReportExport.writeXlsx("Auditoría de acciones", cols, rows, zone, now, os);
            else ReportExport.writeCsv(cols, rows, zone, separator, os);
        };
        return javax.ws.rs.core.Response.ok(out)
                .header("Content-Type", xlsx ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        : "text/csv; charset=utf-8")
                .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .header("Cache-Control", "no-store")
                .build();
    }

    // --- retention -----------------------------------------------------------------------------------------------

    public static class SettingsBody {
        public Integer retentionDays;
    }

    @ApiOperation(value = "Retención de la auditoría")
    @GET
    @Path("/settings")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSettings() {
        Optional<User> me = viewer();
        if (!me.isPresent()) return Response.PERMISSION_DENIED();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("retentionDays", dao.retentionDays(me.get().getCustomerId()));
        out.put("minDays", MIN_RETENTION_DAYS);
        out.put("maxDays", MAX_RETENTION_DAYS);
        out.put("canEdit", canConfigure(me.get()));
        return Response.OK(out);
    }

    /** Only a full administrator who may change the system settings decides how long the trail is kept. */
    private static boolean canConfigure(User me) {
        return !UserScope.isRestricted(me) && SecurityContext.get().hasPermission("settings");
    }

    @ApiOperation(value = "Cambiar la retención", notes = "Body: { retentionDays } entre 30 y 3650. Los registros más "
            + "antiguos se purgan una vez al día; cada purga queda registrada.")
    @PUT
    @Path("/settings")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response setSettings(SettingsBody body) {
        Optional<User> me = viewer();
        if (!me.isPresent() || !canConfigure(me.get())) return Response.PERMISSION_DENIED();
        if (body == null || body.retentionDays == null || body.retentionDays < MIN_RETENTION_DAYS
                || body.retentionDays > MAX_RETENTION_DAYS) {
            return Response.ERROR("La retención debe estar entre " + MIN_RETENTION_DAYS + " y " + MAX_RETENTION_DAYS + " días.");
        }
        int before = dao.retentionDays(me.get().getCustomerId());
        dao.setRetentionDays(me.get().getCustomerId(), body.retentionDays);
        logger.info("Audit retention set to {} days (was {}) by {}", body.retentionDays, before, me.get().getLogin());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("retentionDays", body.retentionDays);
        out.put("previous", before);
        return Response.OK(out);
    }

    // --- integrity -----------------------------------------------------------------------------------------------

    @ApiOperation(value = "Verificar la integridad", notes = "Recorre la cadena de hashes de todos los registros: cada uno "
            + "guarda la huella del anterior, así que un registro editado o quitado del medio rompe la cadena desde ahí.")
    @GET
    @Path("/verify")
    @Produces(MediaType.APPLICATION_JSON)
    public Response verify() {
        Optional<User> me = viewer();
        if (!me.isPresent() || UserScope.isRestricted(me.get())) return Response.PERMISSION_DENIED();
        try {
            service.flush();
            long checked = 0;
            long afterId = 0;
            String prev = null;
            boolean first = true;
            Long broken = null;
            while (broken == null) {
                List<AuditEntry> rows = dao.chain(afterId, 2000);
                if (rows.isEmpty()) break;
                // The oldest row kept points at a row the retention purge removed: its link cannot be checked.
                broken = AuditHash.verify(rows, prev, !first);
                checked += rows.size();
                AuditEntry last = rows.get(rows.size() - 1);
                afterId = last.id;
                prev = last.hash;
                first = false;
            }
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            out.put("ok", broken == null);
            out.put("checked", checked);
            if (broken != null) out.put("brokenAtId", broken);
            return Response.OK(out);
        } catch (Exception e) {
            logger.error("Audit verification failed", e);
            return Response.INTERNAL_ERROR();
        }
    }
}
