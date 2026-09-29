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
 *     <li><b>Actions</b>: a command can be queued for a group or for all devices. Destructive commands (wipe, passcode
 *     reset) are refused here: they are issued one device at a time.</li>
 * </ul>
 */
@Singleton
@Path("/private/fleet/v1")
@Api(tags = {"Fleet: groups and scopes"})
public class FleetResource {

    private static final Logger logger = LoggerFactory.getLogger(FleetResource.class);

    /** Never fanned out to a group or the whole fleet (same rule as the device-list bulk actions). */
    private static final Set<String> FANOUT_FORBIDDEN_TYPES = Set.of("device.wipe", "device.passcodereset");
    private static final int MAX_GROUP_NAME = 100;

    private AgentCommandDAO commandDAO;
    private UnsecureDAO unsecureDAO;
    private AgentWakeHub wakeHub;
    private ConfigurationScopeApplier scopes;
    private String baseUrl = "";
    private com.hmdm.rest.resource.support.ConfigAppInstaller configAppInstaller;

    /** A constructor required by Swagger. */
    public FleetResource() {
    }

    @Inject
    public FleetResource(AgentCommandDAO commandDAO, UnsecureDAO unsecureDAO, AgentWakeHub wakeHub,
                         ConfigurationScopeApplier scopes,
                         @com.google.inject.name.Named("base.url") String baseUrl,
                         com.hmdm.rest.resource.support.ConfigAppInstaller configAppInstaller) {
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
        for (Map<String, Object> d : commandDAO.listDeviceExportRows(customerId.get())) {
            out.add(com.hmdm.util.DeviceSummary.of(d));
        }
        return Response.OK(out);
    }

    // --- export -------------------------------------------------------------------------------------------------

    @ApiOperation(value = "Export devices (Excel)", notes = "Workbook with Resumen, Dispositivos, Carpetas and Conexiones "
            + "(connection history). ?group=<id> limits it to a folder and its sub-folders; ?days=<n> (default 30) the history.")
    @GET
    @Path("/export.xlsx")
    @Produces("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public javax.ws.rs.core.Response exportDevices(@javax.ws.rs.QueryParam("group") Integer groupId,
                                                  @javax.ws.rs.QueryParam("days") Integer days,
                                                  @javax.ws.rs.QueryParam("tz") String tz) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return javax.ws.rs.core.Response.status(403).build();
        }
        int c = customerId.get();
        com.hmdm.util.DeviceExport.Input in = new com.hmdm.util.DeviceExport.Input();
        in.baseUrl = baseUrl;
        try {
            if (tz != null && !tz.trim().isEmpty()) in.zone = java.time.ZoneId.of(tz.trim());
        } catch (Exception ignored) {
            // keep America/Bogota
        }
        in.now = System.currentTimeMillis();
        int d = days == null ? 30 : Math.max(1, Math.min(days, 366));
        in.from = in.now - d * 86_400_000L;
        in.to = in.now;
        in.connectionGapMs = com.hmdm.rest.resource.AgentResource.CONNECTION_GAP_MS;
        in.groups = commandDAO.listGroups(c);
        in.devices = commandDAO.listDeviceExportRows(c);
        in.connections = commandDAO.listConnections(c, in.from, in.to);
        String fileName = "dispositivos";
        if (groupId != null) {
            DeviceGroupView g = commandDAO.findGroup(c, groupId);
            if (g == null) {
                return javax.ws.rs.core.Response.status(404).build();
            }
            in.onlyGroups = com.hmdm.util.DeviceExport.branch(groupId, in.groups);
            in.scopeLabel = "Carpeta " + g.getName() + " y sus subcarpetas";
            fileName += "-" + g.getName().replaceAll("[^A-Za-z0-9._-]+", "_");
        }
        final com.hmdm.util.DeviceExport.Input input = in;
        javax.ws.rs.core.StreamingOutput body = out -> com.hmdm.util.DeviceExport.write(input, out);
        String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").format(java.time.Instant.ofEpochMilli(in.now).atZone(in.zone));
        return javax.ws.rs.core.Response.ok(body)
                .header("Content-Disposition", "attachment; filename=\"" + fileName + "-" + stamp + ".xlsx\"")
                .header("Cache-Control", "no-store")
                .build();
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
        out.put("groups", commandDAO.listGroups(c));
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
        if (commandDAO.groupNameTaken(c, name, body.parentId, null)) {
            return Response.ERROR("error.group.name.taken");
        }
        if (body.configurationId != null && !ownsConfiguration(c, body.configurationId)) {
            return Response.ERROR("error.configuration.not.found");
        }
        int id = commandDAO.insertGroup(c, name, body.configurationId, body.parentId);
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
        // The whole branch may inherit the change (or a new ancestor's configuration after a move).
        Set<Integer> branch = new HashSet<>(commandDAO.groupSubtree(c, id));
        int changed = scopes.apply(c, r -> r.getGroupId() != null && branch.contains(r.getGroupId()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("group", commandDAO.findGroup(c, id));
        out.put("devicesReconfigured", changed);
        return Response.OK(out);
    }

    @ApiOperation(value = "Delete group", notes = "Its devices stay, without a group (they fall back to the global "
            + "configuration unless they pin their own).")
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
        if (FANOUT_FORBIDDEN_TYPES.contains(type.toLowerCase(Locale.ROOT))) {
            return Response.ERROR("error.agent.command.bulkForbidden");
        }
        int queued = 0;
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
            wakeHub.wake(row.getNumber(), "commands");
            queued++;
        }
        logger.info("Command {} queued for {} device(s) of {} (customer {})", type, queued, target, customerId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("queued", queued);
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

    /** The current customer, when the user may change devices (edit_devices), else empty. */
    private static Optional<Integer> editor(String action) {
        if (!SecurityContext.get().hasPermission("edit_devices")) {
            logger.warn("Permission denied: {} requires edit_devices (user {})", action,
                    SecurityContext.get().getCurrentUser().map(u -> u.getLogin()).orElse("?"));
            return Optional.empty();
        }
        return SecurityContext.get().getCurrentCustomerId();
    }
}
