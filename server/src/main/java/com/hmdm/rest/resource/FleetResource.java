package com.hmdm.rest.resource;

import com.hmdm.notification.AgentWakeHub;
import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.DeviceGroupView;
import com.hmdm.persistence.domain.DeviceScopeRow;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.resource.support.ConfigurationScopeApplier;
import com.hmdm.security.SecurityContext;
import com.hmdm.util.ConfigurationScopes;
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
import javax.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * <p>Fleet organisation for DallyControl: groups (companies such as DISAY or AMOVIL) that hold devices, and changes
 * at three levels — one device, one group, or every device (global).</p>
 *
 * <ul>
 *     <li><b>Configuration</b>: a device runs its own configuration when pinned, else its group's, else the global
 *     default ({@link ConfigurationScopes}). Changing a level re-resolves the devices under it and wakes them.</li>
 *     <li><b>Actions</b>: a command can be queued for a group or for all devices. A factory reset (wipe) and
 *     clearing the lock password are refused here (one device at a time); SETTING a lock password en masse is
 *     allowed (disruptive_actions). See {@link com.hmdm.rest.resource.support.BulkCommandPolicy}.</li>
 * </ul>
 */
@Singleton
@Path("/private/fleet/v1")
@Api(tags = {"Fleet: groups and scopes"})
public class FleetResource {

    private static final Logger logger = LoggerFactory.getLogger(FleetResource.class);

    /** Never fanned out to a group or the whole fleet (same rule as the device-list bulk actions). */
    /** Actions that interrupt the user (lock / reboot / change passcode). Fanning these out to a folder or the
     *  whole fleet needs the disruptive_actions permission — mirrors AgentAdminResource. Lowercased. */
    private static final Set<String> DISRUPTIVE_TYPES =
            Set.of("device.lock", "device.reboot", "device.passcodereset");
    private static final int MAX_GROUP_NAME = 100;

    private AgentCommandDAO commandDAO;
    private UnsecureDAO unsecureDAO;
    private AgentWakeHub wakeHub;
    private ConfigurationScopeApplier scopes;
    private String baseUrl = "";
    private com.hmdm.rest.resource.support.ConfigAppInstaller configAppInstaller;
    /** Folder administrators: their folders only (and the folder tree's changes reach their access). */
    private com.hmdm.rest.resource.support.UserScope scope;

    /** A constructor required by Swagger. */
    public FleetResource() {
    }

    @Inject
    public FleetResource(AgentCommandDAO commandDAO, UnsecureDAO unsecureDAO, AgentWakeHub wakeHub,
                         ConfigurationScopeApplier scopes,
                         @javax.inject.Named("base.url") String baseUrl,
                         com.hmdm.rest.resource.support.ConfigAppInstaller configAppInstaller,
                         com.hmdm.rest.resource.support.UserScope scope) {
        this.scope = scope;
        this.configAppInstaller = configAppInstaller;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.commandDAO = commandDAO;
        this.unsecureDAO = unsecureDAO;
        this.wakeHub = wakeHub;
        this.scopes = scopes;
    }

    // --- request bodies -------------------------------------------------------------------------------------------

    public static class GroupBody {
        public String name;
        public Integer configurationId;
        /** Parent folder; null = top level. */
        public Integer parentId;
    }

    public static class DevicesGroupBody {
        public List<Integer> deviceIds;
        /** null removes the devices from any group. */
        public Integer groupId;
    }

    public static class DevicesConfigurationBody {
        public List<Integer> deviceIds;
        /** A configuration pins it on the devices; null makes them inherit again (group, then global). */
        public Integer configurationId;
    }

    public static class GlobalBody {
        public Integer configurationId;
    }

    public static class DevicesAccountEmailBody {
        public List<Integer> deviceIds;
        /** The email account to set on those devices; null/blank clears it (they inherit the folder's again). */
        public String accountEmail;
    }

    public static class AccountEmailBody {
        /** The email account to set on the group/folder; null/blank clears it (it inherits the parent folder's). */
        public String accountEmail;
    }

    public static class CommandBody {
        public AgentCommand command;
    }

    // --- device summaries (the device list's extra columns) ---------------------------------------------------

    @ApiOperation(value = "Device summaries", notes = "Per device, from its last report: model, Android, agent, battery, "
            + "network type and signal quality, carrier, free storage, kiosk, connection mode.")
    @GET
    @Path("/devices/summary")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deviceSummaries() {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> visible = scope.deviceNumbers();
        for (Map<String, Object> d : commandDAO.listDeviceExportRows(customerId.get())) {
            if (visible != null && !visible.contains(String.valueOf(d.get("number")))) continue;
            out.add(com.hmdm.util.DeviceSummary.of(d));
        }
        return Response.OK(out);
    }

    // --- export -------------------------------------------------------------------------------------------------

    /** Builds what the exports contain (shared with the inventory report and the scheduled e-mails). */
    @Inject
    private com.hmdm.rest.resource.support.FleetExportService exportService;

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** Body of POST /export: what the device list is showing (filters or selection) and the file wanted. */
    public static class ExportBody {
        /** xlsx (default) | csv */
        public String format;
        /** CSV field separator: ";" (default) "," "tab" "|". */
        public String separator;
        public Integer group;
        public Integer days;
        public String tz;
        /** Only these device numbers; null/absent = every device of the scope. */
        public List<String> numbers;
    }

    @ApiOperation(value = "Export devices (Excel)", notes = "Workbook with Resumen, Dispositivos (inventory: connection, "
            + "battery, storage, Android, network, identifiers), Carpetas and Conexiones (connection history). "
            + "?group=<id> limits it to a folder and its sub-folders; ?days=<n> (default 30) the history; "
            + "?numbers=a,b limits it to those devices.")
    @GET
    @Path("/export.xlsx")
    @Produces("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public javax.ws.rs.core.Response exportDevices(@javax.ws.rs.QueryParam("group") Integer groupId,
                                                  @javax.ws.rs.QueryParam("days") Integer days,
                                                  @javax.ws.rs.QueryParam("tz") String tz,
                                                  @javax.ws.rs.QueryParam("numbers") String numbers) {
        return export("xlsx", null, groupId, days, tz, splitNumbers(numbers));
    }

    @ApiOperation(value = "Export devices (CSV)", notes = "The Dispositivos sheet as CSV: UTF-8 with BOM, one row per "
            + "device. ?sep=; (default) | , | tab | pipe. Same ?group, ?numbers and ?tz as the Excel export.")
    @GET
    @Path("/export.csv")
    @Produces("text/csv")
    public javax.ws.rs.core.Response exportDevicesCsv(@javax.ws.rs.QueryParam("group") Integer groupId,
                                                     @javax.ws.rs.QueryParam("tz") String tz,
                                                     @javax.ws.rs.QueryParam("sep") String sep,
                                                     @javax.ws.rs.QueryParam("numbers") String numbers) {
        return export("csv", sep, groupId, null, tz, splitNumbers(numbers));
    }

    @ApiOperation(value = "Export devices (filtered or selected)", notes = "Body: { format: xlsx|csv, separator?, group?, "
            + "days?, tz?, numbers?: [device numbers] } — the same files as export.xlsx / export.csv, for a device list "
            + "too long for a URL (what the console's table is showing, or its selection).")
    @POST
    @Path("/export")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces({"text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"})
    public javax.ws.rs.core.Response exportDevicesPost(ExportBody body) {
        ExportBody b = body == null ? new ExportBody() : body;
        return export(b.format, b.separator, b.group, b.days, b.tz, b.numbers);
    }

    private static List<String> splitNumbers(String numbers) {
        if (numbers == null || numbers.trim().isEmpty()) return null;
        List<String> out = new ArrayList<>();
        for (String n : numbers.split(",")) if (!n.trim().isEmpty()) out.add(n.trim());
        return out;
    }

    private javax.ws.rs.core.Response export(String format, String separator, Integer groupId, Integer days, String tz,
                                             List<String> numbers) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return javax.ws.rs.core.Response.status(403).build();
        }
        com.hmdm.rest.resource.support.FleetExportService.Request rq = new com.hmdm.rest.resource.support.FleetExportService.Request();
        rq.groupId = groupId;
        rq.days = days;
        rq.tz = tz;
        rq.numbers = numbers;
        // A folder administrator exports only their folders' devices.
        com.hmdm.rest.resource.support.FleetExportService.Built built =
                exportService.build(customerId.get(), rq, scope.deviceNumbers(), scope.groups());
        if (built == null) {
            return javax.ws.rs.core.Response.status(404).build();
        }
        final com.hmdm.util.DeviceExport.Input input = built.input;
        final boolean csv = "csv".equalsIgnoreCase(format);
        final char sep = com.hmdm.util.ReportExport.separator(separator);
        javax.ws.rs.core.StreamingOutput out = csv
                ? o -> com.hmdm.util.DeviceExport.writeCsv(input, sep, o)
                : o -> com.hmdm.util.DeviceExport.write(input, o);
        return javax.ws.rs.core.Response.ok(out)
                .header("Content-Type", csv ? "text/csv; charset=utf-8" : XLSX)
                .header("Content-Disposition", "attachment; filename=\"" + built.fileBase + (csv ? ".csv" : ".xlsx") + "\"")
                .header("Cache-Control", "no-store")
                .build();
    }

    // --- refresh ("Actualizar ahora") -------------------------------------------------------------------------

    public static class RefreshBody {
        public List<Integer> deviceIds;
        public List<String> numbers;
    }

    @ApiOperation(value = "Ask devices to report now", notes = "Body: { deviceIds? , numbers? } — wakes each device over its "
            + "live channel so it checks in at once (same signal as the per-device force sync; no command is queued). "
            + "Returns how many were asked and how many have the channel open now; the others report on their next "
            + "periodic check-in.")
    @POST
    @Path("/devices/refresh")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response refreshDevices(RefreshBody body) {
        if (!SecurityContext.get().hasPermission("edit_devices")) {
            return Response.PERMISSION_DENIED();
        }
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        Set<Integer> ids = body == null || body.deviceIds == null ? Set.of() : new HashSet<>(body.deviceIds);
        Set<String> numbers = body == null || body.numbers == null ? Set.of() : new HashSet<>(body.numbers);
        if (ids.isEmpty() && numbers.isEmpty()) {
            return Response.ERROR("error.devices.empty");
        }
        Set<String> visible = scope.deviceNumbers(); // null = all
        int requested = 0, live = 0;
        List<String> offline = new ArrayList<>();
        for (DeviceScopeRow r : commandDAO.listDeviceScopes(customerId.get())) {
            if (!ids.contains(r.getId()) && !numbers.contains(r.getNumber())) continue;
            if (visible != null && !visible.contains(r.getNumber())) continue;
            requested++;
            if (wakeHub.isOnline(r.getNumber())) live++;
            else offline.add(r.getNumber());
            wakeHub.wake(r.getNumber(), "commands");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requested", requested);
        out.put("live", live);
        out.put("notLive", offline);
        out.put("at", System.currentTimeMillis());
        return Response.OK(out);
    }

    // --- groups ---------------------------------------------------------------------------------------------------

    @ApiOperation(value = "List groups", notes = "Groups with their configuration and device count, the global default "
            + "configuration, and how many devices have no group.")
    @GET
    @Path("/groups")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listGroups() {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        int c = customerId.get();
        long ungrouped = commandDAO.listDeviceScopes(c).stream().filter(r -> r.getGroupId() == null).count();
        Map<String, Object> out = new LinkedHashMap<>();
        Set<Integer> mine = scope.groups();
        if (mine == null) {
            out.put("groups", commandDAO.listGroups(c));
        } else {
            // A folder administrator sees their folders; theirs whose parent they cannot see show at the top.
            List<DeviceGroupView> visible = new ArrayList<>();
            for (DeviceGroupView g : commandDAO.listGroups(c)) {
                if (!mine.contains(g.getId())) continue;
                if (g.getParentId() != null && !mine.contains(g.getParentId())) g.setParentId(null);
                visible.add(g);
            }
            out.put("groups", visible);
            ungrouped = 0;
        }
        out.put("ungroupedDevices", ungrouped);
        out.put("global", globalView(c));
        return Response.OK(out);
    }

    @ApiOperation(value = "Create group", notes = "Body: { name, configurationId? }")
    @POST
    @Path("/groups")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createGroup(GroupBody body) {
        Optional<Integer> customerId = editor("create group");
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        int c = customerId.get();
        String name = body == null ? null : cleanName(body.name);
        if (name == null) {
            return Response.ERROR("error.group.name.invalid");
        }
        if (body.parentId != null && commandDAO.findGroup(c, body.parentId) == null) {
            return Response.ERROR("error.group.parent.invalid");
        }
        if (scope.restricted() && (body.parentId == null || !scope.canGroup(body.parentId))) {
            return Response.PERMISSION_DENIED(); // a folder administrator creates folders only inside theirs
        }
        if (commandDAO.groupNameTaken(c, name, body.parentId, null)) {
            return Response.ERROR("error.group.name.taken");
        }
        if (body.configurationId != null && !ownsConfiguration(c, body.configurationId)) {
            return Response.ERROR("error.configuration.not.found");
        }
        int id = commandDAO.insertGroup(c, name, body.configurationId, body.parentId);
        scope.refresh(c); // a folder created inside someone's folders is theirs too
        logger.info("Group {} '{}' created (customer {}, parent {}, configuration {})", id, name, c, body.parentId,
                body.configurationId);
        return Response.OK(commandDAO.findGroup(c, id));
    }

    @ApiOperation(value = "Update group", notes = "Body: { name, configurationId? } — a new configuration reaches the "
            + "group's devices that do not pin their own.")
    @PUT
    @Path("/groups/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateGroup(@PathParam("id") int id, GroupBody body) {
        Optional<Integer> customerId = editor("update group");
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        int c = customerId.get();
        if (commandDAO.findGroup(c, id) == null) {
            return Response.ERROR("error.group.not.found");
        }
        String name = body == null ? null : cleanName(body.name);
        if (name == null) {
            return Response.ERROR("error.group.name.invalid");
        }
        if (scope.restricted()) {
            DeviceGroupView current = commandDAO.findGroup(c, id);
            boolean parentVisible = current.getParentId() != null && scope.canGroup(current.getParentId());
            // Their top folders stay where the administrator put them; other folders move only inside theirs.
            boolean sameParent = java.util.Objects.equals(current.getParentId(), body.parentId);
            if (!(parentVisible ? (body.parentId != null && scope.canGroup(body.parentId)) : sameParent)) {
                return Response.PERMISSION_DENIED();
            }
        }
        // A folder cannot move under itself or one of its descendants (that would detach a loop from the tree).
        if (body.parentId != null && (commandDAO.findGroup(c, body.parentId) == null
                || commandDAO.groupSubtree(c, id).contains(body.parentId))) {
            return Response.ERROR("error.group.parent.invalid");
        }
        if (commandDAO.groupNameTaken(c, name, body.parentId, id)) {
            return Response.ERROR("error.group.name.taken");
        }
        if (body.configurationId != null && !ownsConfiguration(c, body.configurationId)) {
            return Response.ERROR("error.configuration.not.found");
        }
        commandDAO.updateGroup(c, id, name, body.configurationId, body.parentId);
        scope.refresh(c);
        dropGeofenceCache();
        // The whole branch may inherit the change (or a new ancestor's configuration after a move).
        Set<Integer> branch = new HashSet<>(commandDAO.groupSubtree(c, id));
        int changed = scopes.apply(c, r -> r.getGroupId() != null && branch.contains(r.getGroupId()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("group", commandDAO.findGroup(c, id));
        out.put("devicesReconfigured", changed);
        return Response.OK(out);
    }

    @ApiOperation(value = "Set a folder's kiosk branding", notes = "Body: { logoUrl?, footerLogoUrl?, backgroundUrl?, supportPhone?, supportLabel? } (empty clears it: the "
            + "folder inherits again). Devices under the folder re-apply their kiosk on their next check-in.")
    @PUT
    @Path("/groups/{id}/brand")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response setGroupBrand(@PathParam("id") int id, com.hmdm.util.KioskBrand body) {
        Optional<Integer> customerId = editor("set group branding");
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        int c = customerId.get();
        if (commandDAO.findGroup(c, id) == null) {
            return Response.ERROR("error.group.not.found");
        }
        com.hmdm.util.KioskBrand b = body == null ? null : body.cleaned();
        if (b != null) b.setShowSerial(null); // the serial is the policy's choice
        commandDAO.updateGroupBrand(c, id, b == null || b.isEmpty() ? null : b.toJson());
        Set<Integer> branch = new HashSet<>(commandDAO.groupSubtree(c, id));
        int woken = 0;
        for (DeviceScopeRow r : commandDAO.listDeviceScopes(c)) {
            if (r.getGroupId() != null && branch.contains(r.getGroupId())) {
                wakeHub.wake(r.getNumber(), "commands");
                woken++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("group", commandDAO.findGroup(c, id));
        out.put("devices", woken);
        return Response.OK(out);
    }

    @ApiOperation(value = "Set a folder's console branding",
            notes = "Body: { logoUrl?, name? } (empty clears it: the folder inherits again). The logo/name shown in the "
                    + "admin console header to the folder administrators of this folder. Independent of the kiosk branding.")
    @PUT
    @Path("/groups/{id}/consoleBrand")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response setGroupConsoleBrand(@PathParam("id") int id, com.hmdm.util.ConsoleBrand body) {
        Optional<Integer> customerId = editor("set group console branding");
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        int c = customerId.get();
        if (commandDAO.findGroup(c, id) == null) {
            return Response.ERROR("error.group.not.found");
        }
        if (scope.restricted() && !scope.canGroup(id)) {
            return Response.PERMISSION_DENIED();
        }
        com.hmdm.util.ConsoleBrand b = body == null ? null : body.cleaned();
        commandDAO.updateGroupConsoleBrand(c, id, b == null || b.isEmpty() ? null : b.toJson());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("group", commandDAO.findGroup(c, id));
        return Response.OK(out);
    }

    @ApiOperation(value = "Current user's console branding",
            notes = "The logo/name to show in the console header for the signed-in user, resolved over the folders they "
                    + "can reach (nearest the top wins). Empty for full administrators (the console uses its default wordmark).")
    @GET
    @Path("/consoleBrand")
    @Produces(MediaType.APPLICATION_JSON)
    public Response currentConsoleBrand() {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        int c = customerId.get();
        // A full administrator (no folder scope) keeps the default DallyControl wordmark.
        Set<Integer> mine = scope.groups();
        if (mine == null) {
            return Response.OK(new LinkedHashMap<String, Object>());
        }
        List<DeviceGroupView> all = commandDAO.listGroups(c);
        Map<Integer, DeviceGroupView> byId = new java.util.HashMap<>();
        for (DeviceGroupView g : all) byId.put(g.getId(), g);
        // Depth of each reachable folder (distance from the root of its tree); shallowest first.
        List<DeviceGroupView> reachable = new ArrayList<>();
        for (DeviceGroupView g : all) if (mine.contains(g.getId())) reachable.add(g);
        reachable.sort((a, b) -> Integer.compare(depthOf(a, byId), depthOf(b, byId)));
        List<com.hmdm.util.ConsoleBrand> chain = new ArrayList<>();
        for (DeviceGroupView g : reachable) chain.add(com.hmdm.util.ConsoleBrand.parse(g.getConsoleBrand()));
        com.hmdm.util.ConsoleBrand eff = com.hmdm.util.ConsoleBrand.effective(chain);
        Map<String, Object> out = new LinkedHashMap<>();
        if (eff != null) {
            if (eff.getLogoUrl() != null) out.put("logoUrl", eff.getLogoUrl());
            if (eff.getName() != null) out.put("name", eff.getName());
        }
        return Response.OK(out);
    }

    /** Distance of a folder from the root of its tree (0 = top level), guarded against cycles. */
    private static int depthOf(DeviceGroupView g, Map<Integer, DeviceGroupView> byId) {
        int depth = 0;
        Integer parent = g.getParentId();
        Set<Integer> seen = new HashSet<>();
        while (parent != null && seen.add(parent) && depth < 64) {
            DeviceGroupView p = byId.get(parent);
            if (p == null) break;
            depth++;
            parent = p.getParentId();
        }
        return depth;
    }

    @ApiOperation(value = "Delete group", notes = "Refused while the folder itself holds devices (move them first); "
            + "its sub-folders move up to its parent.")
    @DELETE
    @Path("/groups/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteGroup(@PathParam("id") int id) {
        Optional<Integer> customerId = editor("delete group");
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        int c = customerId.get();
        DeviceGroupView group = commandDAO.findGroup(c, id);
        if (group == null) {
            return Response.ERROR("error.group.not.found");
        }
        if (scope.restricted() && (group.getParentId() == null || !scope.canGroup(group.getParentId()))) {
            return Response.PERMISSION_DENIED(); // a folder administrator cannot delete a folder they were given
        }
        // A folder with devices of its own is not deleted: they would silently lose their folder and policy.
        if (group.getDeviceCount() > 0) {
            return Response.ERROR("La carpeta tiene " + group.getDeviceCount() + " dispositivo"
                    + (group.getDeviceCount() == 1 ? "" : "s") + ": muévelos a otra carpeta antes de eliminarla.");
        }
        // Its sub-folders move up to its parent; they and their devices may now inherit something else.
        Set<Integer> branch = new HashSet<>(commandDAO.groupSubtree(c, id));
        Set<Integer> members = new HashSet<>();
        for (DeviceScopeRow r : commandDAO.listDeviceScopes(c)) {
            if (r.getGroupId() != null && branch.contains(r.getGroupId())) {
                members.add(r.getId());
            }
        }
        commandDAO.reparentChildren(c, id, group.getParentId());
        if (!commandDAO.deleteGroup(c, id)) {
            return Response.ERROR("error.group.not.found");
        }
        int changed = scopes.apply(c, r -> members.contains(r.getId()));
        scope.refresh(c);
        dropGeofenceCache();
        logger.info("Group {} deleted (customer {}); {} device(s) reconfigured", id, c, changed);
        return Response.OK();
    }

    public static class SyncAppsBody {
        /** Folders (with their sub-folders). */
        public List<Integer> groupIds;
        public List<Integer> deviceIds;
        public boolean all;
    }

    @ApiOperation(value = "Update policy apps now", notes = "For every device of the folders (and sub-folders), the listed "
            + "devices or all: queues app.install for each policy app the phone lacks or has in an older version.")
    @POST
    @Path("/syncApps")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response syncApps(SyncAppsBody body) {
        Optional<Integer> customerId = editor("update policy apps");
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        int c = customerId.get();
        Set<Integer> groups = new HashSet<>();
        if (body != null && body.groupIds != null) {
            for (Integer g : body.groupIds) if (g != null) groups.addAll(commandDAO.groupSubtree(c, g));
        }
        Set<Integer> ids = body == null || body.deviceIds == null ? Set.of() : new HashSet<>(body.deviceIds);
        boolean all = body != null && body.all;
        int devices = 0, queued = 0;
        for (DeviceScopeRow r : commandDAO.listDeviceScopes(c)) {
            if (!(all || ids.contains(r.getId()) || (r.getGroupId() != null && groups.contains(r.getGroupId())))) continue;
            com.hmdm.persistence.domain.Device d = unsecureDAO.getDeviceByNumber(r.getNumber());
            if (d == null) continue;
            devices++;
            queued += configAppInstaller.enqueueConfigApps(d);
            wakeHub.wake(r.getNumber(), "commands");
        }
        logger.info("Update policy apps: {} install(s) queued on {} device(s) (customer {})", queued, devices, c);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("devices", devices);
        out.put("queued", queued);
        return Response.OK(out);
    }

    @ApiOperation(value = "Queue a command for a group", notes = "Body: { command: { type, payload?, requiresCapability? } }")
    @POST
    @Path("/groups/{id}/commands")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response groupCommand(@PathParam("id") int id, CommandBody body) {
        Optional<Integer> customerId = editor("queue group command");
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        if (commandDAO.findGroup(customerId.get(), id) == null) {
            return Response.ERROR("error.group.not.found");
        }
        // A folder's command reaches its sub-folders' devices too.
        Set<Integer> branch = new HashSet<>(commandDAO.groupSubtree(customerId.get(), id));
        return fanOut(customerId.get(), body, r -> r.getGroupId() != null && branch.contains(r.getGroupId()),
                "group " + id);
    }

    // --- devices --------------------------------------------------------------------------------------------------

    @ApiOperation(value = "Move devices to a group", notes = "Body: { deviceIds, groupId|null } — each device ends up in "
            + "exactly that group (or none) and takes its configuration unless it pins its own.")
    @POST
    @Path("/devices/group")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response moveDevices(DevicesGroupBody body) {
        Optional<Integer> customerId = editor("move devices to group");
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        int c = customerId.get();
        if (body == null || body.deviceIds == null || body.deviceIds.isEmpty()) {
            return Response.ERROR("error.devices.empty");
        }
        if (body.groupId != null && commandDAO.findGroup(c, body.groupId) == null) {
            return Response.ERROR("error.group.not.found");
        }
        // A folder administrator moves only their devices, and only into their folders.
        if (scope.restricted() && (body.groupId == null || !scope.canGroup(body.groupId) || !scope.canDeviceIds(c, body.deviceIds))) {
            return Response.PERMISSION_DENIED();
        }
        Set<Integer> moved = new HashSet<>();
        List<Integer> skipped = new ArrayList<>();
        for (Integer deviceId : body.deviceIds) {
            if (deviceId == null || commandDAO.findDeviceScope(c, deviceId) == null) {
                skipped.add(deviceId);
                continue;
            }
            commandDAO.setDeviceGroup(deviceId, body.groupId);
            moved.add(deviceId);
        }
        int changed = scopes.apply(c, r -> moved.contains(r.getId()));
        dropGeofenceCache(); // a device in another folder inherits other zones
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("moved", moved.size());
        out.put("skipped", skipped);
        out.put("devicesReconfigured", changed);
        return Response.OK(out);
    }

    @ApiOperation(value = "Set device-level configuration", notes = "Body: { deviceIds, configurationId|null } — a "
            + "configuration pins it on those devices; null returns them to their group's (or the global) configuration.")
    @PUT
    @Path("/devices/configuration")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response setDeviceConfiguration(DevicesConfigurationBody body) {
        Optional<Integer> customerId = editor("set device configuration");
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        int c = customerId.get();
        if (body == null || body.deviceIds == null || body.deviceIds.isEmpty()) {
            return Response.ERROR("error.devices.empty");
        }
        if (body.configurationId != null && !ownsConfiguration(c, body.configurationId)) {
            return Response.ERROR("error.configuration.not.found");
        }
        if (!scope.canDeviceIds(c, body.deviceIds)) {
            return Response.PERMISSION_DENIED();
        }
        Set<Integer> touched = new HashSet<>();
        int changed = 0;
        for (Integer deviceId : body.deviceIds) {
            DeviceScopeRow row = deviceId == null ? null : commandDAO.findDeviceScope(c, deviceId);
            if (row == null) {
                continue;
            }
            touched.add(deviceId);
            if (body.configurationId != null) {
                commandDAO.updateDevicePinned(deviceId, true);
                if (!body.configurationId.equals(row.getConfigurationId())) {
                    commandDAO.updateDeviceConfiguration(deviceId, body.configurationId);
                    scopes.installConfigurationApps(row.getNumber());
                    wakeHub.wake(row.getNumber(), "commands");
                    changed++;
                }
            } else {
                commandDAO.updateDevicePinned(deviceId, false);
            }
        }
        if (body.configurationId == null) {
            changed = scopes.apply(c, r -> touched.contains(r.getId()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("updated", touched.size());
        out.put("devicesReconfigured", changed);
        return Response.OK(out);
    }

    @ApiOperation(value = "Set the device-level email account", notes = "Body: { deviceIds, accountEmail|null } — sets the "
            + "email account on those devices (metadata/identification only; it does not create an account on the phone). "
            + "null/blank clears it, so the device inherits its folder's account again. Device > group/folder.")
    @PUT
    @Path("/devices/accountEmail")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response setDeviceAccountEmail(DevicesAccountEmailBody body) {
        Optional<Integer> customerId = editor("set device account email");
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        int c = customerId.get();
        if (body == null || body.deviceIds == null || body.deviceIds.isEmpty()) {
            return Response.ERROR("error.devices.empty");
        }
        String email = com.hmdm.util.AccountEmails.normalize(body.accountEmail);
        if (email != null && !com.hmdm.util.AccountEmails.isValid(email)) {
            return Response.ERROR("error.agent.account.email.invalid");
        }
        if (!scope.canDeviceIds(c, body.deviceIds)) {
            return Response.PERMISSION_DENIED();
        }
        int updated = 0;
        for (Integer deviceId : body.deviceIds) {
            if (deviceId == null || commandDAO.findDeviceScope(c, deviceId) == null) {
                continue;
            }
            commandDAO.updateDeviceAccountEmailById(deviceId, email);
            updated++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("updated", updated);
        return Response.OK(out);
    }

    @ApiOperation(value = "Set a folder's email account", notes = "Body: { accountEmail|null } — the email account of "
            + "this group/folder (metadata/identification only). null/blank clears it so the folder inherits its parent's. "
            + "A device without its own email resolves to the nearest folder with one.")
    @PUT
    @Path("/groups/{id}/accountEmail")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response setGroupAccountEmail(@PathParam("id") int id, AccountEmailBody body) {
        Optional<Integer> customerId = editor("set group account email");
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        int c = customerId.get();
        if (commandDAO.findGroup(c, id) == null) {
            return Response.ERROR("error.group.not.found");
        }
        if (scope.restricted() && !scope.canGroup(id)) {
            return Response.PERMISSION_DENIED();
        }
        String email = body == null ? null : com.hmdm.util.AccountEmails.normalize(body.accountEmail);
        if (email != null && !com.hmdm.util.AccountEmails.isValid(email)) {
            return Response.ERROR("error.agent.account.email.invalid");
        }
        commandDAO.updateGroupAccountEmail(c, id, email);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("group", commandDAO.findGroup(c, id));
        return Response.OK(out);
    }

    @ApiOperation(value = "Device scope", notes = "The device's group and where its configuration comes from "
            + "(device, group or global).")
    @GET
    @Path("/devices/{id}/scope")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deviceScope(@PathParam("id") int id) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        int c = customerId.get();
        DeviceScopeRow row = commandDAO.findDeviceScope(c, id);
        if (row == null) {
            return Response.ERROR("error.agent.device.unknown");
        }
        Integer global = commandDAO.getGlobalConfigurationId(c);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deviceId", row.getId());
        out.put("groupId", row.getGroupId());
        out.put("groupName", row.getGroupName());
        out.put("configurationId", row.getConfigurationId());
        out.put("configurationName", configurationName(row.getConfigurationId()));
        out.put("pinned", row.isConfigurationPinned());
        out.put("source", ConfigurationScopes.source(row, global));
        out.put("groupConfigurationId", row.getGroupConfigurationId());
        out.put("groupConfigurationName", configurationName(row.getGroupConfigurationId()));
        out.put("globalConfigurationId", global);
        out.put("globalConfigurationName", configurationName(global));
        // Email account (metadata/identification): the device's own wins, else its folder chain's (nearest ancestor).
        String ownEmail = row.getAccountEmail();
        String folderEmail = row.getGroupAccountEmail();
        out.put("accountEmail", ownEmail);
        out.put("groupAccountEmail", folderEmail);
        out.put("effectiveAccountEmail", ownEmail != null ? ownEmail : folderEmail);
        out.put("accountEmailSource", ownEmail != null ? "device" : (folderEmail != null ? "group" : null));
        return Response.OK(out);
    }

    // --- global ---------------------------------------------------------------------------------------------------

    @ApiOperation(value = "Global default configuration", notes = "The configuration of devices that neither pin one "
            + "nor belong to a group with one.")
    @GET
    @Path("/global")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getGlobal() {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        return Response.OK(globalView(customerId.get()));
    }

    @ApiOperation(value = "Set the global default configuration", notes = "Body: { configurationId } — reaches every "
            + "device that inherits it.")
    @PUT
    @Path("/global")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response setGlobal(GlobalBody body) {
        Optional<Integer> customerId = editor("set global configuration");
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        int c = customerId.get();
        if (body == null || body.configurationId == null || !ownsConfiguration(c, body.configurationId)) {
            return Response.ERROR("error.configuration.not.found");
        }
        commandDAO.updateGlobalConfigurationId(c, body.configurationId);
        int changed = scopes.applyAll(c);
        Map<String, Object> out = globalView(c);
        out.put("devicesReconfigured", changed);
        return Response.OK(out);
    }

    @ApiOperation(value = "Queue a command for every device", notes = "Body: { command: { type, payload?, requiresCapability? } }")
    @POST
    @Path("/global/commands")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response globalCommand(CommandBody body) {
        Optional<Integer> customerId = editor("queue global command");
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        return fanOut(customerId.get(), body, r -> true, "all devices");
    }

    // --- helpers --------------------------------------------------------------------------------------------------

    private Response fanOut(int customerId, CommandBody body, Predicate<DeviceScopeRow> which, String target) {
        AgentCommand cmd = body == null ? null : body.command;
        if (cmd == null || cmd.getType() == null || cmd.getType().trim().isEmpty()) {
            return Response.ERROR("error.agent.command.invalid");
        }
        String type = cmd.getType().trim();
        // Destructive types never fan out; device.passcodeReset only to SET a (non-empty) lock password,
        // never to clear it for a whole folder / fleet. See BulkCommandPolicy.
        if (!com.hmdm.rest.resource.support.BulkCommandPolicy.allowedInBulk(type, cmd.getPayload())) {
            return Response.ERROR("error.agent.command.bulkForbidden");
        }
        // Interrupting actions (lock / reboot / change passcode) need the extra disruptive_actions permission,
        // same as the per-device and bulk endpoints — a view-only folder role cannot fan these out.
        if (DISRUPTIVE_TYPES.contains(type.toLowerCase(Locale.ROOT))
                && !SecurityContext.get().hasPermission("disruptive_actions")) {
            logger.warn("Permission denied: fan-out {} requires disruptive_actions (user {})", type,
                    SecurityContext.get().getCurrentUser().map(u -> u.getLogin()).orElse("?"));
            return Response.ERROR("error.agent.command.bulkForbidden");
        }
        int queued = 0;
        int offline = 0;
        long now = System.currentTimeMillis();
        for (DeviceScopeRow row : commandDAO.listDeviceScopes(customerId)) {
            if (!which.test(row)) {
                continue;
            }
            AgentCommand c = new AgentCommand();
            c.setDeviceNumber(row.getNumber());
            c.setType(type);
            c.setPayload(cmd.getPayload());
            c.setRequiresCapability(cmd.getRequiresCapability());
            c.setStatus("pending");
            c.setCreatedAt(now);
            commandDAO.insert(c);
            if (!wakeHub.isOnline(row.getNumber())) offline++;
            wakeHub.wake(row.getNumber(), "commands");
            queued++;
        }
        logger.info("Command {} queued for {} device(s) of {} (customer {})", type, queued, target, customerId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("queued", queued);
        // Of those, the devices not connected now: their command waits and runs when they come back.
        out.put("offline", offline);
        return Response.OK(out);
    }

    private Map<String, Object> globalView(int customerId) {
        Integer global = commandDAO.getGlobalConfigurationId(customerId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configurationId", global);
        out.put("configurationName", configurationName(global));
        return out;
    }

    private String configurationName(Integer id) {
        if (id == null) {
            return null;
        }
        Configuration cfg = unsecureDAO.getConfigurationById(id);
        return cfg == null ? null : cfg.getName();
    }

    private boolean ownsConfiguration(int customerId, int configurationId) {
        Configuration cfg = unsecureDAO.getConfigurationById(configurationId);
        return cfg != null && cfg.getCustomerId() == customerId;
    }

    private static String cleanName(String name) {
        if (name == null) {
            return null;
        }
        String n = name.trim().replaceAll("\\s+", " ");
        return n.isEmpty() || n.length() > MAX_GROUP_NAME ? null : n;
    }

    /** Folders or their devices changed: the zones each device inherits may have changed too. */
    private static void dropGeofenceCache() {
        com.hmdm.rest.resource.support.GeofenceService geofences = com.hmdm.rest.resource.support.GeofenceService.INSTANCE;
        if (geofences != null) geofences.invalidate();
    }

    /** The current customer, when the user may MODIFY the fleet (manage_fleet: change folders/policies),
     *  else empty. Device actions are gated separately by edit_devices, so a view-only folder role keeps
     *  the safe actions but cannot change folders or assign policies. */
    private static Optional<Integer> editor(String action) {
        if (!SecurityContext.get().hasPermission("manage_fleet")) {
            logger.warn("Permission denied: {} requires manage_fleet (user {})", action,
                    SecurityContext.get().getCurrentUser().map(u -> u.getLogin()).orElse("?"));
            return Optional.empty();
        }
        return SecurityContext.get().getCurrentCustomerId();
    }
}
