/*
 *
 * DallyControl — Geocercas (new console module, not part of upstream Headwind).
 *
 * Zones (circle / polygon) with a rule — "must stay inside" (alert on exit) or "must not enter" (alert on entry) —
 * assigned to folders (inherited by the folders below, like policies), to single devices or to the whole fleet.
 * The evaluation happens on the server when a position is stored (see GeofenceService), so it works with every
 * agent version. This resource is the console's API: zones, the inside/outside indicator, the thresholds, the
 * alert e-mail recipients, and the paginated / exportable location history.
 *
 * Permissions: any signed-in user of the customer may read; changing zones or recipients needs manage_fleet (the
 * same permission as folders and the Alerts/Reports modules); thresholds and fleet-wide items need a full
 * administrator. A folder administrator sees the zones that touch their folders or devices and may only create or
 * change zones assigned entirely inside them.
 *
 */

package com.hmdm.rest.resource;

import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.GeofenceDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.domain.DeviceGroupView;
import com.hmdm.persistence.domain.DeviceLocation;
import com.hmdm.persistence.domain.Geofence;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.resource.support.AlertNotifier;
import com.hmdm.rest.resource.support.GeofenceService;
import com.hmdm.rest.resource.support.GeofenceTasks;
import com.hmdm.rest.resource.support.UserScope;
import com.hmdm.security.SecurityContext;
import com.hmdm.util.AlertDigest;
import com.hmdm.util.GeoMath;
import com.hmdm.util.GeofenceEvaluator;
import com.hmdm.util.ReportExport;
import com.hmdm.util.ReportExport.Column;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.StreamingOutput;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Singleton
@Path("/private/geofences/v1")
@Api(tags = {"Geocercas: zonas, alertas por correo e historial de ubicación"})
public class GeofenceResource {

    private static final Logger logger = LoggerFactory.getLogger(GeofenceResource.class);
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("America/Bogota");
    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final Set<String> ALERT_TYPES = new HashSet<>(Arrays.asList(
            "disconnected", "kioskExit", "simChange", "geofenceExit", "geofenceEnter", "dataWarning", "dataLimit"));
    private static final int MAX_NAME = 100;
    private static final double MIN_RADIUS_M = 20;
    private static final double MAX_RADIUS_M = 500_000;
    private static final int MAX_PAGE = 2000;
    /** Rows one export may carry (a position every minute for two months). */
    private static final int MAX_EXPORT = 100_000;
    private static final long MAX_RANGE_MS = 366L * 24 * 3600_000L;

    private GeofenceDAO dao;
    private AgentCommandDAO commandDAO;
    private UnsecureDAO unsecureDAO;
    private UserScope scope;
    private GeofenceService service;
    private AlertNotifier notifier;

    /** A constructor required by Swagger. */
    public GeofenceResource() {
    }

    @Inject
    public GeofenceResource(GeofenceDAO dao, AgentCommandDAO commandDAO, UnsecureDAO unsecureDAO, UserScope scope,
                            GeofenceService service, AlertNotifier notifier, GeofenceTasks tasks) {
        this.dao = dao;
        this.commandDAO = commandDAO;
        this.unsecureDAO = unsecureDAO;
        this.scope = scope;
        this.service = service;
        this.notifier = notifier;
    }

    // --- access --------------------------------------------------------------------------------------------------

    private static Optional<Integer> reader() {
        return SecurityContext.get() == null ? Optional.empty() : SecurityContext.get().getCurrentCustomerId();
    }

    private static Optional<Integer> editor(String action) {
        if (SecurityContext.get() == null || !SecurityContext.get().hasPermission("manage_fleet")) {
            logger.warn("Permission denied: {} requires manage_fleet", action);
            return Optional.empty();
        }
        return SecurityContext.get().getCurrentCustomerId();
    }

    /** What a folder administrator reaches (null sets = everything). */
    private final class Reach {
        final Set<Integer> groups;
        /** Their folders plus every folder above them: zones there are inherited by their devices (read only). */
        final Set<Integer> groupsAndAncestors;
        final Set<String> deviceNumbers;
        final int customerId;

        Reach(int customerId) {
            this.customerId = customerId;
            this.groups = scope.groups();
            this.deviceNumbers = scope.deviceNumbers();
            if (groups == null) {
                groupsAndAncestors = null;
            } else {
                Map<Integer, DeviceGroupView> byId = new HashMap<>();
                for (DeviceGroupView g : commandDAO.listGroups(customerId)) byId.put(g.getId(), g);
                groupsAndAncestors = new HashSet<>(groups);
                for (Integer id : groups) {
                    DeviceGroupView g = byId.get(id);
                    for (int i = 0; g != null && g.getParentId() != null && i < 32; i++) {
                        groupsAndAncestors.add(g.getParentId());
                        g = byId.get(g.getParentId());
                    }
                }
            }
        }

        boolean restricted() {
            return groups != null;
        }

        /** May change the zone: everything it is assigned to is theirs. */
        boolean canEdit(Geofence z) {
            if (!restricted()) return true;
            if (z.allDevices) return false;
            boolean any = false;
            if (z.groupIds != null) for (Integer g : z.groupIds) {
                if (!groups.contains(g)) return false;
                any = true;
            }
            if (z.deviceIds != null) for (Integer d : z.deviceIds) {
                if (d == null || !scope.canDeviceId(customerId, d)) return false;
                any = true;
            }
            return any;
        }

        boolean canSee(Geofence z) {
            if (!restricted() || z.allDevices) return true;
            if (z.groupIds != null) for (Integer g : z.groupIds) if (groupsAndAncestors.contains(g)) return true;
            if (z.deviceIds != null) for (Integer d : z.deviceIds) if (d != null && scope.canDeviceId(customerId, d)) return true;
            return false;
        }
    }

    // --- zones ---------------------------------------------------------------------------------------------------

    @ApiOperation(value = "Listar zonas", notes = "Zonas con sus carpetas y dispositivos; 'editable' indica si el usuario puede cambiarla.")
    @GET
    @Path("/zones")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listZones() {
        Optional<Integer> customerId = reader();
        if (!customerId.isPresent()) return Response.PERMISSION_DENIED();
        int c = customerId.get();
        Reach reach = new Reach(c);
        boolean mayEdit = SecurityContext.get().hasPermission("manage_fleet");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Geofence z : dao.listZones(c)) {
            if (!reach.canSee(z)) continue;
            out.add(view(z, mayEdit && reach.canEdit(z), reach));
        }
        return Response.OK(out);
    }

    private Map<String, Object> view(Geofence z, boolean editable, Reach reach) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", z.id);
        m.put("name", z.name);
        m.put("color", z.color);
        m.put("shape", z.shape);
        m.put("centerLat", z.centerLat);
        m.put("centerLon", z.centerLon);
        m.put("radiusM", z.radiusM);
        m.put("points", z.getPoints());
        m.put("rule", z.rule);
        m.put("active", z.active);
        m.put("alertOnReturn", z.alertOnReturn);
        m.put("allDevices", z.allDevices);
        m.put("schedule", z.schedule);
        m.put("trackingMinutes", z.trackingMinutes);
        m.put("updatedAt", z.updatedAt);
        m.put("editable", editable);
        // A folder administrator does not learn about folders, devices or addresses outside their reach.
        List<Integer> groups = new ArrayList<>();
        if (z.groupIds != null) for (Integer g : z.groupIds) {
            if (!reach.restricted() || reach.groupsAndAncestors.contains(g)) groups.add(g);
        }
        List<Integer> devices = new ArrayList<>();
        if (z.deviceIds != null) for (Integer d : z.deviceIds) {
            if (!reach.restricted() || (d != null && scope.canDeviceId(reach.customerId, d))) devices.add(d);
        }
        m.put("groupIds", groups);
        m.put("deviceIds", devices);
        m.put("emails", editable || !reach.restricted() ? z.emails : null);
        return m;
    }

    @ApiOperation(value = "Crear zona")
    @POST
    @Path("/zones")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createZone(Geofence body) {
        return saveZone(null, body);
    }

    @ApiOperation(value = "Cambiar zona")
    @PUT
    @Path("/zones/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateZone(@PathParam("id") int id, Geofence body) {
        return saveZone(id, body);
    }

    private Response saveZone(Integer id, Geofence body) {
        Optional<Integer> customerId = editor("save geofence");
        if (!customerId.isPresent()) return Response.PERMISSION_DENIED();
        if (body == null) return Response.ERROR("error.geofence.badRequest");
        int c = customerId.get();
        Reach reach = new Reach(c);
        Geofence existing = null;
        if (id != null) {
            existing = dao.findZone(c, id);
            if (existing == null) return Response.OBJECT_NOT_FOUND_ERROR();
            if (!reach.canEdit(existing)) return Response.PERMISSION_DENIED();
        }
        String problem = clean(body);
        if (problem != null) return Response.ERROR(problem);
        body.id = id;
        body.customerId = c;
        body.groupIds = distinct(body.groupIds);
        body.deviceIds = distinct(body.deviceIds);
        // The new assignment must also be entirely inside the user's reach (and a restricted user must assign it).
        if (!reach.canEdit(body)) return Response.PERMISSION_DENIED();
        boolean reset = existing != null && geometryChanged(existing, body);
        Geofence saved = dao.saveZone(body, reset);
        if (saved == null) return Response.OBJECT_NOT_FOUND_ERROR();
        service.invalidate();
        notifier.prime(c);
        Geofence fresh = dao.findZone(c, saved.id);
        return Response.OK(view(fresh, true, reach));
    }

    private static List<Integer> distinct(List<Integer> ids) {
        List<Integer> out = new ArrayList<>();
        if (ids != null) for (Integer i : new LinkedHashSet<>(ids)) if (i != null) out.add(i);
        return out;
    }

    private static boolean geometryChanged(Geofence a, Geofence b) {
        return !java.util.Objects.equals(a.shape, b.shape) || !java.util.Objects.equals(a.rule, b.rule)
                || !java.util.Objects.equals(a.centerLat, b.centerLat) || !java.util.Objects.equals(a.centerLon, b.centerLon)
                || !java.util.Objects.equals(a.radiusM, b.radiusM) || !java.util.Objects.equals(a.polygon, b.polygon);
    }

    /** Validate and normalise a zone in place. @return an error key, or null when it is valid. */
    static String clean(Geofence z) {
        String name = z.name == null ? "" : z.name.trim().replaceAll("\\p{Cntrl}", "");
        if (name.isEmpty() || name.length() > MAX_NAME) return "error.geofence.name";
        z.name = name;
        z.color = z.color != null && COLOR.matcher(z.color.trim()).matches() ? z.color.trim().toLowerCase() : "#2563eb";
        z.rule = GeofenceEvaluator.RULE_KEEP_OUT.equals(z.rule) ? GeofenceEvaluator.RULE_KEEP_OUT : GeofenceEvaluator.RULE_STAY_INSIDE;
        if ("polygon".equals(z.shape)) {
            double[][] pts = GeoMath.parsePolygon(z.polygon);
            if (pts == null) return "error.geofence.polygon";
            z.polygon = GeoMath.polygonJson(pts);
            z.centerLat = null;
            z.centerLon = null;
            z.radiusM = null;
        } else if ("circle".equals(z.shape)) {
            if (z.centerLat == null || z.centerLon == null || z.radiusM == null
                    || !GeoMath.validPoint(z.centerLat, z.centerLon) || z.radiusM.isNaN()
                    || z.radiusM < MIN_RADIUS_M || z.radiusM > MAX_RADIUS_M) {
                return "error.geofence.circle";
            }
            z.polygon = null;
        } else {
            return "error.geofence.shape";
        }
        if (z.schedule != null && !z.schedule.trim().isEmpty()) {
            GeofenceEvaluator.Schedule s = GeofenceEvaluator.Schedule.parse(z.schedule);
            if (s == null) return "error.geofence.schedule";
            z.schedule = s.toJson();
        } else {
            z.schedule = null;
        }
        List<String> emails = AlertDigest.parseEmails(z.emails);
        z.emails = emails.isEmpty() ? null : String.join(",", emails);
        if (z.trackingMinutes != null && (z.trackingMinutes < 1 || z.trackingMinutes > 1440)) z.trackingMinutes = null;
        return null;
    }

    @ApiOperation(value = "Borrar zona")
    @DELETE
    @Path("/zones/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteZone(@PathParam("id") int id) {
        Optional<Integer> customerId = editor("delete geofence");
        if (!customerId.isPresent()) return Response.PERMISSION_DENIED();
        int c = customerId.get();
        Geofence existing = dao.findZone(c, id);
        if (existing == null) return Response.OBJECT_NOT_FOUND_ERROR();
        if (!new Reach(c).canEdit(existing)) return Response.PERMISSION_DENIED();
        dao.deleteZone(c, id);
        service.invalidate();
        return Response.OK();
    }

    // --- inside / outside ----------------------------------------------------------------------------------------

    @ApiOperation(value = "Estado dentro/fuera", notes = "Por zona, los dispositivos evaluados y si están dentro o fuera.")
    @GET
    @Path("/states")
    @Produces(MediaType.APPLICATION_JSON)
    public Response states() {
        Optional<Integer> customerId = reader();
        if (!customerId.isPresent()) return Response.PERMISSION_DENIED();
        int c = customerId.get();
        Reach reach = new Reach(c);
        Map<Integer, Geofence> zones = new HashMap<>();
        for (Geofence z : dao.listZones(c)) if (reach.canSee(z)) zones.put(z.id, z);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : dao.listStates(c)) {
            Geofence z = zones.get(GeofenceDAO.intOf(r.get("zoneid")));
            String number = (String) r.get("number");
            if (z == null || (reach.deviceNumbers != null && !reach.deviceNumbers.contains(number))) continue;
            out.add(stateView(z, number, (String) r.get("description"), (String) r.get("state"),
                    GeofenceDAO.longOf(r.get("since")), GeofenceDAO.longOf(r.get("lastfixat"))));
        }
        return Response.OK(out);
    }

    private static Map<String, Object> stateView(Geofence z, String number, String description, String state, long since, long lastFixAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("zoneId", z.id);
        m.put("number", number);
        m.put("description", description);
        m.put("state", state);
        m.put("since", since);
        m.put("lastFixAt", lastFixAt);
        boolean violating = GeofenceEvaluator.RULE_KEEP_OUT.equals(z.rule)
                ? GeofenceEvaluator.INSIDE.equals(state) : GeofenceEvaluator.OUTSIDE.equals(state);
        m.put("violating", violating);
        return m;
    }

    @ApiOperation(value = "Zonas de un dispositivo", notes = "Las zonas activas que le aplican (propias o heredadas) y su estado.")
    @GET
    @Path("/devices/{number}/zones")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deviceZones(@PathParam("number") String number) {
        Optional<Integer> customerId = reader();
        if (!customerId.isPresent()) return Response.PERMISSION_DENIED();
        Device device = unsecureDAO.getDeviceByNumber(number);
        if (device == null) return Response.ERROR("error.agent.device.unknown");
        if (device.getCustomerId() != customerId.get()) return Response.PERMISSION_DENIED();
        Reach reach = new Reach(customerId.get());
        Map<Integer, GeofenceEvaluator.State> states = dao.statesOfDevice(number);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Geofence z : dao.zonesForDevice(number)) {
            Map<String, Object> m = view(z, false, reach);
            GeofenceEvaluator.State s = states.get(z.id);
            m.put("state", s == null || s.state == null ? null : stateView(z, number, device.getDescription(), s.state, s.since, s.lastFixAt));
            out.add(m);
        }
        return Response.OK(out);
    }

    // --- thresholds ----------------------------------------------------------------------------------------------

    @ApiOperation(value = "Umbrales", notes = "Precisión máxima, histéresis, posiciones de confirmación, retención del historial e intervalo de correo.")
    @GET
    @Path("/settings")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSettings() {
        Optional<Integer> customerId = reader();
        if (!customerId.isPresent()) return Response.PERMISSION_DENIED();
        return Response.OK(dao.getSettings(customerId.get()));
    }

    @ApiOperation(value = "Cambiar umbrales", notes = "Solo administradores completos.")
    @PUT
    @Path("/settings")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response setSettings(GeofenceDAO.Settings body) {
        Optional<Integer> customerId = editor("geofence settings");
        if (!customerId.isPresent() || scope.restricted()) return Response.PERMISSION_DENIED();
        if (body == null) return Response.ERROR("error.geofence.badRequest");
        dao.saveSettings(customerId.get(), body);
        service.invalidate();
        return Response.OK(dao.getSettings(customerId.get()));
    }

    // --- alert e-mails -------------------------------------------------------------------------------------------

    @ApiOperation(value = "Estado del correo", notes = "configured = false cuando el servidor no tiene SMTP (no se envía nada).")
    @GET
    @Path("/mail")
    @Produces(MediaType.APPLICATION_JSON)
    public Response mailStatus() {
        if (!reader().isPresent()) return Response.PERMISSION_DENIED();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", notifier.mailConfigured());
        return Response.OK(out);
    }

    public static final class TestBody {
        public String email;
    }

    @ApiOperation(value = "Enviar correo de prueba", notes = "Solo administradores completos.")
    @POST
    @Path("/mail/test")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response mailTest(TestBody body) {
        Optional<Integer> customerId = editor("alert mail test");
        if (!customerId.isPresent() || scope.restricted()) return Response.PERMISSION_DENIED();
        List<String> to = AlertDigest.parseEmails(body == null ? null : body.email);
        if (to.isEmpty()) return Response.ERROR("error.geofence.email");
        if (!notifier.mailConfigured()) return Response.ERROR("error.geofence.smtp.missing");
        return notifier.sendTest(to.get(0)) ? Response.OK() : Response.ERROR("error.geofence.smtp.failed");
    }

    public static final class RecipientBody {
        /** null = the whole fleet (full administrators only). */
        public Integer groupId;
        public String emails;
        /** Alert types this rule covers; empty = all. */
        public List<String> types;
        public Boolean active;
    }

    @ApiOperation(value = "Destinatarios de alertas", notes = "Reglas por carpeta (cubren sus subcarpetas) o de toda la flota.")
    @GET
    @Path("/recipients")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listRecipients() {
        Optional<Integer> customerId = reader();
        if (!customerId.isPresent()) return Response.PERMISSION_DENIED();
        Set<Integer> mine = scope.groups();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : dao.listRecipients(customerId.get())) {
            Integer groupId = GeofenceDAO.intOf(r.get("groupid"));
            if (mine != null && (groupId == null || !mine.contains(groupId))) continue;
            out.add(recipientView(r));
        }
        return Response.OK(out);
    }

    private static Map<String, Object> recipientView(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.get("id"));
        m.put("groupId", r.get("groupid"));
        m.put("emails", r.get("emails"));
        String types = (String) r.get("types");
        m.put("types", types == null || types.isEmpty() ? new ArrayList<String>() : Arrays.asList(types.split(",")));
        m.put("active", !Boolean.FALSE.equals(r.get("active")));
        return m;
    }

    private static String typesOf(RecipientBody body) {
        List<String> out = new ArrayList<>();
        if (body.types != null) for (String t : body.types) if (t != null && ALERT_TYPES.contains(t) && !out.contains(t)) out.add(t);
        return out.isEmpty() || out.size() == ALERT_TYPES.size() ? null : String.join(",", out);
    }

    @ApiOperation(value = "Crear regla de destinatarios")
    @POST
    @Path("/recipients")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createRecipient(RecipientBody body) {
        Optional<Integer> customerId = editor("alert recipients");
        if (!customerId.isPresent()) return Response.PERMISSION_DENIED();
        if (body == null) return Response.ERROR("error.geofence.badRequest");
        // A folder administrator may only notify about their own folders.
        if (scope.restricted() && (body.groupId == null || !scope.canGroup(body.groupId))) return Response.PERMISSION_DENIED();
        List<String> emails = AlertDigest.parseEmails(body.emails);
        if (emails.isEmpty()) return Response.ERROR("error.geofence.email");
        if (!dao.insertRecipient(customerId.get(), body.groupId, String.join(",", emails), typesOf(body), !Boolean.FALSE.equals(body.active))) {
            return Response.OBJECT_NOT_FOUND_ERROR();
        }
        notifier.prime(customerId.get());
        return Response.OK();
    }

    @ApiOperation(value = "Cambiar regla de destinatarios")
    @PUT
    @Path("/recipients/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateRecipient(@PathParam("id") int id, RecipientBody body) {
        Optional<Integer> customerId = editor("alert recipients");
        if (!customerId.isPresent()) return Response.PERMISSION_DENIED();
        if (body == null) return Response.ERROR("error.geofence.badRequest");
        Map<String, Object> existing = dao.findRecipient(customerId.get(), id);
        if (existing == null) return Response.OBJECT_NOT_FOUND_ERROR();
        Integer groupId = GeofenceDAO.intOf(existing.get("groupid"));
        if (scope.restricted() && (groupId == null || !scope.canGroup(groupId))) return Response.PERMISSION_DENIED();
        List<String> emails = AlertDigest.parseEmails(body.emails);
        if (emails.isEmpty()) return Response.ERROR("error.geofence.email");
        dao.updateRecipient(customerId.get(), id, String.join(",", emails), typesOf(body), !Boolean.FALSE.equals(body.active));
        notifier.prime(customerId.get());
        return Response.OK();
    }

    @ApiOperation(value = "Borrar regla de destinatarios")
    @DELETE
    @Path("/recipients/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteRecipient(@PathParam("id") int id) {
        Optional<Integer> customerId = editor("alert recipients");
        if (!customerId.isPresent()) return Response.PERMISSION_DENIED();
        Map<String, Object> existing = dao.findRecipient(customerId.get(), id);
        if (existing == null) return Response.OBJECT_NOT_FOUND_ERROR();
        Integer groupId = GeofenceDAO.intOf(existing.get("groupid"));
        if (scope.restricted() && (groupId == null || !scope.canGroup(groupId))) return Response.PERMISSION_DENIED();
        dao.deleteRecipient(customerId.get(), id);
        return Response.OK();
    }

    // --- location history ----------------------------------------------------------------------------------------

    /** The device when it is the customer's and inside the user's folders, else null. */
    private Device visibleDevice(int customerId, String number) {
        Device device = unsecureDAO.getDeviceByNumber(number);
        if (device == null || device.getCustomerId() != customerId) return null;
        if (scope.restricted() && !scope.canDeviceNumber(customerId, number)) return null;
        return device;
    }

    private static long[] range(Long from, Long to) {
        long t = to == null ? System.currentTimeMillis() : to;
        long f = from == null ? t - 24L * 3600_000L : from;
        if (t < f || t - f > MAX_RANGE_MS) return null;
        return new long[]{f, t};
    }

    @ApiOperation(value = "Historial de ubicación paginado",
            notes = "?from=&to= (epoch ms, máx. 366 días) &page=0 &size=500 (máx. 2000) &order=asc|desc. Más antiguas primero "
                    + "salvo order=desc; "
                    + "'lowAccuracy' marca las posiciones con ±m peor que el umbral.")
    @GET
    @Path("/devices/{number}/track")
    @Produces(MediaType.APPLICATION_JSON)
    public Response track(@PathParam("number") String number, @QueryParam("from") Long from, @QueryParam("to") Long to,
                          @QueryParam("page") Integer page, @QueryParam("size") Integer size,
                          @QueryParam("order") String order) {
        Optional<Integer> customerId = reader();
        if (!customerId.isPresent()) return Response.PERMISSION_DENIED();
        if (visibleDevice(customerId.get(), number) == null) return Response.PERMISSION_DENIED();
        long[] r = range(from, to);
        if (r == null) return Response.ERROR("error.agent.locations.range");
        int sz = Math.max(1, Math.min(MAX_PAGE, size == null ? 500 : size));
        int pg = Math.max(0, page == null ? 0 : page);
        int maxAccuracy = dao.getSettings(customerId.get()).maxAccuracyM;
        long total = dao.countTrack(number, r[0], r[1]);
        List<Map<String, Object>> fixes = new ArrayList<>();
        boolean desc = "desc".equalsIgnoreCase(order);
        List<DeviceLocation> rows = desc ? dao.listTrackDesc(number, r[0], r[1], sz, (long) pg * sz)
                : dao.listTrack(number, r[0], r[1], sz, (long) pg * sz);
        for (DeviceLocation f : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lat", f.getLat());
            m.put("lon", f.getLon());
            m.put("accuracy", f.getAccuracy());
            m.put("provider", f.getProvider());
            m.put("capturedAt", f.getCapturedAt());
            m.put("lowAccuracy", f.getAccuracy() != null && f.getAccuracy() > maxAccuracy);
            fixes.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", r[0]);
        out.put("to", r[1]);
        out.put("page", pg);
        out.put("size", sz);
        out.put("total", total);
        out.put("pages", (total + sz - 1) / sz);
        out.put("maxAccuracyM", maxAccuracy);
        out.put("fixes", fixes);
        return Response.OK(out);
    }

    @ApiOperation(value = "Exportar el recorrido", notes = "?format=csv|xlsx &from=&to= &tz=")
    @GET
    @Path("/devices/{number}/track/export")
    @Produces({"text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"})
    public javax.ws.rs.core.Response exportTrack(@PathParam("number") String number, @QueryParam("format") String format,
                                                 @QueryParam("from") Long from, @QueryParam("to") Long to,
                                                 @QueryParam("tz") String tz) {
        Optional<Integer> customerId = reader();
        if (!customerId.isPresent()) return javax.ws.rs.core.Response.status(403).build();
        Device device = visibleDevice(customerId.get(), number);
        if (device == null) return javax.ws.rs.core.Response.status(403).build();
        long[] r = range(from, to);
        if (r == null) return javax.ws.rs.core.Response.status(400).build();
        final List<Map<String, Object>> rows = new ArrayList<>();
        try {
            int maxAccuracy = dao.getSettings(customerId.get()).maxAccuracyM;
            String name = device.getDescription() == null || device.getDescription().trim().isEmpty() ? number : device.getDescription();
            for (long offset = 0; offset < MAX_EXPORT; offset += MAX_PAGE) {
                List<DeviceLocation> page = dao.listTrack(number, r[0], r[1], MAX_PAGE, offset);
                for (DeviceLocation f : page) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", name);
                    m.put("number", number);
                    m.put("capturedat", f.getCapturedAt());
                    m.put("lat", f.getLat());
                    m.put("lon", f.getLon());
                    m.put("accuracy", f.getAccuracy() == null ? null : Math.round(f.getAccuracy()));
                    m.put("provider", f.getProvider());
                    m.put("quality", f.getAccuracy() == null ? "Sin dato"
                            : f.getAccuracy() > maxAccuracy ? "Baja precisión" : "Buena");
                    m.put("map", "https://www.openstreetmap.org/?mlat=" + f.getLat() + "&mlon=" + f.getLon() + "#map=17/" + f.getLat() + "/" + f.getLon());
                    rows.add(m);
                }
                if (page.size() < MAX_PAGE) break;
            }
        } catch (Exception e) {
            logger.warn("Track export of {} failed", number, e);
            return javax.ws.rs.core.Response.status(500).build();
        }
        final List<Column> cols = Arrays.asList(
                new Column("name", "Equipo", "text"), new Column("number", "Número", "text"),
                new Column("capturedat", "Fecha", "datetime"), new Column("lat", "Latitud", "number"),
                new Column("lon", "Longitud", "number"), new Column("accuracy", "Precisión (±m)", "number"),
                new Column("provider", "Proveedor", "text"), new Column("quality", "Calidad", "text"),
                new Column("map", "Mapa", "text"));
        ZoneId z = DEFAULT_ZONE;
        if (tz != null && !tz.trim().isEmpty()) {
            try {
                z = ZoneId.of(tz.trim());
            } catch (Exception ignored) {
                // keep default
            }
        }
        final ZoneId zone = z;
        final boolean xlsx = !"csv".equalsIgnoreCase(format);
        final long now = System.currentTimeMillis();
        String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").format(java.time.Instant.ofEpochMilli(now).atZone(zone));
        String fileName = "recorrido-" + number.replaceAll("[^A-Za-z0-9._-]", "_") + "-" + stamp + (xlsx ? ".xlsx" : ".csv");
        StreamingOutput out = os -> {
            if (xlsx) ReportExport.writeXlsx("Recorrido del equipo", cols, rows, zone, now, os);
            else ReportExport.writeCsv(cols, rows, zone, os);
        };
        return javax.ws.rs.core.Response.ok(out)
                .header("Content-Type", xlsx ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" : "text/csv; charset=utf-8")
                .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .header("Cache-Control", "no-store")
                .build();
    }
}
