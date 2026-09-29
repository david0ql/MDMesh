/*
 *
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hmdm.rest.resource;

import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.AgentEnrollmentTokenDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.AgentEnrollmentToken;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.domain.DeviceState;
import com.hmdm.persistence.domain.DeviceSyncRow;
import com.hmdm.notification.AgentWakeHub;
import com.hmdm.rest.json.AgentBulkCommandRequest;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.agent.CommandHistoryView;
import com.hmdm.rest.json.agent.ConfigStatusView;
import com.hmdm.rest.json.agent.ConfigSyncSummary;
import com.hmdm.rest.resource.support.ConfigReconciler;
import com.hmdm.security.SecurityContext;
import com.hmdm.util.AgentCapabilityTokens;
import com.hmdm.util.DesiredConfigBuilder;
import com.hmdm.util.RemoteSessions;
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
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * <p>Authenticated admin resource for the from-scratch Android agent v1 protocol: mints enrollment
 * tokens and queues opaque commands. As with {@link AgentResource}, command {@code type}/{@code
 * payload} are never interpreted by the server.</p>
 */
@Singleton
@Path("/private/agent/v1")
@Api(tags = {"Agent v1 admin"})
public class AgentAdminResource {

    private static final Logger logger = LoggerFactory.getLogger(AgentAdminResource.class);

    /** Default lifetime of a freshly-minted enrollment token (24h). */
    private static final long DEFAULT_TOKEN_TTL_MILLIS = 24L * 60L * 60L * 1000L;

    private AgentEnrollmentTokenDAO tokenDAO;
    private AgentCommandDAO commandDAO;
    private UnsecureDAO unsecureDAO;
    private AgentWakeHub wakeHub;
    private com.hmdm.rest.resource.support.ConfigAppInstaller configAppInstaller;
    private ConfigReconciler configReconciler;
    private String baseUrl;

    /**
     * <p>A constructor required by Swagger.</p>
     */
    public AgentAdminResource() {
    }

    @Inject
    public AgentAdminResource(AgentEnrollmentTokenDAO tokenDAO,
                              AgentCommandDAO commandDAO,
                              UnsecureDAO unsecureDAO,
                              AgentWakeHub wakeHub,
                              com.hmdm.rest.resource.support.ConfigAppInstaller configAppInstaller,
                              ConfigReconciler configReconciler,
                              @javax.inject.Named("base.url") String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.tokenDAO = tokenDAO;
        this.commandDAO = commandDAO;
        this.unsecureDAO = unsecureDAO;
        this.wakeHub = wakeHub;
        this.configAppInstaller = configAppInstaller;
        this.configReconciler = configReconciler;
    }

    /**
     * <p>Every mutation here acts on devices (commands incl. wipe/passcode reset, wake-ups, enrollment
     * tokens), so it needs {@code edit_devices}, exactly like {@link DeviceResource}. Reads stay open to
     * any user of the customer.</p>
     */
    private static boolean canEditDevices(String action) {
        if (SecurityContext.get().hasPermission("edit_devices")) {
            return true;
        }
        logger.warn("Permission denied: {} requires edit_devices (user {})", action,
                SecurityContext.get().getCurrentUser().map(u -> u.getLogin()).orElse("?"));
        return false;
    }

    // =================================================================================================================
    @ApiOperation(value = "Mint enrollment token", notes = "Creates a single-use enrollment token for the current customer. "
            + "An optional configurationId pins the enrolled device to that configuration; an optional groupId puts it "
            + "in that group (company), whose configuration it then inherits.")
    @POST
    @Path("/token")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response mintToken(AgentEnrollmentToken body) {
        if (!canEditDevices("mint enrollment token")) {
            return Response.PERMISSION_DENIED();
        }
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }

        // Optional config binding: the enrolled device lands in this configuration instead of the
        // customer's settings default. Validate ownership — a token must not be able to place a
        // device into another customer's configuration.
        Integer configurationId = body == null ? null : body.getConfigurationId();
        if (configurationId != null) {
            com.hmdm.persistence.domain.Configuration cfg = unsecureDAO.getConfigurationById(configurationId);
            if (cfg == null || cfg.getCustomerId() != customerId.get()) {
                return Response.ERROR("error.configuration.not.found");
            }
        }

        Integer groupId = body == null ? null : body.getGroupId();
        if (groupId != null && commandDAO.findGroup(customerId.get(), groupId) == null) {
            return Response.ERROR("error.group.not.found");
        }

        long now = System.currentTimeMillis();
        AgentEnrollmentToken token = new AgentEnrollmentToken();
        token.setToken(UUID.randomUUID().toString());
        token.setCustomerId(customerId.get());
        token.setConfigurationId(configurationId);
        token.setGroupId(groupId);
        token.setUsed(false);
        token.setCreatedAt(now);
        token.setExpiresAt(now + DEFAULT_TOKEN_TTL_MILLIS);
        tokenDAO.insert(token);

        logger.info("Agent enrollment token {} minted for customer {} (configuration {}, group {})",
                token.getId(), customerId.get(), configurationId, groupId);
        return Response.OK(token);
    }

    public static class CodeBody {
        public Integer groupId;
        public String label;
        /** Optional expiry (epoch ms); null = until revoked. */
        public Long expiresAt;
        /** Optional Wi-Fi the code's QR provisions the phone on. */
        public String wifiSsid;
        public String wifiPassword;
        /** WPA (default), WEP or NONE. */
        public String wifiSecurity;
    }

    // =================================================================================================================
    @ApiOperation(value = "Create a reusable enrollment code", notes = "A folder's enrollment policy: the code enrolls any "
            + "number of devices into the group until revoked (or expired). Body: { groupId, label?, expiresAt? }.")
    @POST
    @Path("/codes")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createCode(CodeBody body) {
        if (!canEditDevices("create enrollment code")) {
            return Response.PERMISSION_DENIED();
        }
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        if (body == null || body.groupId == null || commandDAO.findGroup(customerId.get(), body.groupId) == null) {
            return Response.ERROR("error.group.not.found");
        }
        long now = System.currentTimeMillis();
        if (body.expiresAt != null && body.expiresAt <= now) {
            return Response.ERROR("error.agent.code.expiry.invalid");
        }
        String label = body.label == null ? null : body.label.trim();
        if (label != null && (label.isEmpty() || label.length() > 100)) {
            label = label.isEmpty() ? null : label.substring(0, 100);
        }
        AgentEnrollmentToken token = new AgentEnrollmentToken();
        token.setCustomerId(customerId.get());
        token.setGroupId(body.groupId);
        token.setReusable(true);
        token.setLabel(label);
        token.setUsed(false);
        token.setCreatedAt(now);
        token.setExpiresAt(body.expiresAt);
        String ssid = body.wifiSsid == null ? null : body.wifiSsid.trim();
        if (ssid != null && !ssid.isEmpty()) {
            String sec = body.wifiSecurity == null ? "WPA" : body.wifiSecurity.trim().toUpperCase(java.util.Locale.ROOT);
            if (ssid.length() > 32 || !(sec.equals("WPA") || sec.equals("WEP") || sec.equals("NONE"))
                    || body.wifiPassword != null && body.wifiPassword.length() > 63) {
                return Response.ERROR("error.agent.code.wifi.invalid");
            }
            token.setWifiSsid(ssid);
            token.setWifiSecurity(sec);
            token.setWifiPassword(sec.equals("NONE") ? null : body.wifiPassword);
        }
        // The code space is large (31^8), but the column is unique: retry on the rare collision.
        for (int attempt = 0; ; attempt++) {
            token.setToken(com.hmdm.util.EnrollmentCodes.generate());
            try {
                tokenDAO.insert(token);
                break;
            } catch (RuntimeException e) {
                if (attempt >= 4) {
                    throw e;
                }
            }
        }
        logger.info("Reusable enrollment code {} created for group {} (customer {})", token.getId(), body.groupId,
                customerId.get());
        return Response.OK(tokenDAO.listCodes(customerId.get()).stream()
                .filter(c -> c.getId().equals(token.getId())).findFirst().orElse(null));
    }

    @ApiOperation(value = "List reusable enrollment codes")
    @GET
    @Path("/codes")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listCodes() {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        return Response.OK(tokenDAO.listCodes(customerId.get()));
    }

    @ApiOperation(value = "Revoke a reusable enrollment code", notes = "Devices already enrolled stay; the code stops working. "
            + "With ?purge=true a code that is already revoked is deleted from the list.")
    @DELETE
    @Path("/codes/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response revokeCode(@PathParam("id") int id, @QueryParam("purge") boolean purge) {
        if (!canEditDevices("revoke enrollment code")) {
            return Response.PERMISSION_DENIED();
        }
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        if (purge) {
            if (!tokenDAO.deleteRevoked(customerId.get(), id)) {
                return Response.ERROR("error.agent.code.not.revoked");
            }
            logger.info("Revoked enrollment code {} deleted (customer {})", id, customerId.get());
            return Response.OK();
        }
        if (!tokenDAO.revoke(customerId.get(), id)) {
            return Response.ERROR("error.agent.code.not.found");
        }
        logger.info("Reusable enrollment code {} revoked (customer {})", id, customerId.get());
        return Response.OK();
    }

    // =================================================================================================================
    @ApiOperation(value = "Sync configuration apps", notes = "Queues app.install commands for the device's "
            + "configuration apps marked action=install. Use after enrolling an older device or editing a configuration.")
    @POST
    @Path("/devices/{deviceId}/syncApps")
    @Produces(MediaType.APPLICATION_JSON)
    public Response syncConfigApps(@PathParam("deviceId") String deviceId) {
        if (!canEditDevices("sync configuration apps")) {
            return Response.PERMISSION_DENIED();
        }
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) {
            return Response.DEVICE_NOT_FOUND_ERROR();
        }
        if (device.getCustomerId() != customerId.get()) {
            return Response.PERMISSION_DENIED();
        }
        int queued = configAppInstaller.enqueueConfigApps(device);
        logger.info("Sync apps for device {}: {} app.install queued", deviceId, queued);
        return Response.OK(java.util.Collections.singletonMap("queued", queued));
    }

    // =================================================================================================================
    @ApiOperation(value = "Queue agent command", notes = "Queues an opaque command for a device.")
    @POST
    @Path("/devices/{deviceId}/commands")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response queueCommand(@PathParam("deviceId") String deviceId, AgentCommand body) {
        if (!canEditDevices("queue agent command")) {
            return Response.PERMISSION_DENIED();
        }
        if (body == null || body.getType() == null || body.getType().trim().isEmpty()) {
            return Response.ERROR("error.agent.command.invalid");
        }

        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }

        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) {
            return Response.ERROR("error.agent.device.unknown");
        }
        if (device.getCustomerId() != customerId.get()) {
            return Response.PERMISSION_DENIED();
        }

        AgentCommand command = new AgentCommand();
        command.setDeviceNumber(deviceId);
        command.setType(body.getType());
        command.setPayload(body.getPayload());
        command.setRequiresCapability(body.getRequiresCapability());
        command.setStatus("pending");
        command.setCreatedAt(System.currentTimeMillis());
        commandDAO.insert(command);

        // Wake the device so it pulls the command immediately (best-effort; floor reconcile backs it up).
        wakeHub.wake(deviceId, "commands");

        logger.info("Agent command {} queued for device {}", command.getId(), deviceId);
        // Payload-free view (same shape as the history): never echo a payload back to the console.
        return Response.OK(CommandHistoryView.from(command));
    }

    /** Command types that must never be issued in bulk (destructive group). Lowercased — matched
     *  case-insensitively so a mixed-case type can't slip past this destructive-action guard. */
    private static final Set<String> BULK_FORBIDDEN_TYPES =
            Set.of("device.wipe", "device.passcodereset");

    // =================================================================================================================
    @ApiOperation(value = "Queue agent command for many devices",
            notes = "Fans one opaque command out to a list of device ids (destructive types rejected).")
    @POST
    @Path("/bulk/commands")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response queueCommandBulk(AgentBulkCommandRequest req) {
        if (!canEditDevices("queue bulk agent command")) {
            return Response.PERMISSION_DENIED();
        }
        if (req == null || req.getCommand() == null
                || req.getCommand().getType() == null || req.getCommand().getType().trim().isEmpty()) {
            return Response.ERROR("error.agent.command.invalid");
        }
        if (req.getDeviceIds() == null || req.getDeviceIds().isEmpty()) {
            return Response.ERROR("error.agent.command.invalid");
        }
        final String type = req.getCommand().getType().trim();
        if (BULK_FORBIDDEN_TYPES.contains(type.toLowerCase(java.util.Locale.ROOT))) {
            return Response.ERROR("error.agent.command.bulkForbidden");
        }

        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }

        int queued = 0;
        List<Integer> skipped = new ArrayList<>();
        for (Integer id : req.getDeviceIds()) {
            if (id == null) { continue; }
            Device device = unsecureDAO.getDeviceById(id);
            if (device == null || device.getCustomerId() != customerId.get()) {
                skipped.add(id);
                continue;
            }
            AgentCommand command = new AgentCommand();
            command.setDeviceNumber(device.getNumber());
            command.setType(type);
            command.setPayload(req.getCommand().getPayload());
            command.setRequiresCapability(req.getCommand().getRequiresCapability());
            command.setStatus("pending");
            command.setCreatedAt(System.currentTimeMillis());
            commandDAO.insert(command);
            wakeHub.wake(device.getNumber(), "commands");
            queued++;
        }

        logger.info("Bulk command {} queued for {} device(s), {} skipped", type, queued, skipped.size());
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("queued", queued);
        result.put("skipped", skipped);
        return Response.OK(result);
    }

    // =================================================================================================================
    @ApiOperation(value = "Device state", notes = "Latest agent-reported device-state snapshot.")
    @GET
    @Path("/devices/{deviceId}/state")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getState(@PathParam("deviceId") String deviceId) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) {
            return Response.ERROR("error.agent.device.unknown");
        }
        if (device.getCustomerId() != customerId.get()) {
            return Response.PERMISSION_DENIED();
        }
        return Response.OK(commandDAO.getState(deviceId));
    }

    // =================================================================================================================
    @ApiOperation(value = "Device configuration status", notes = "Desired-state revision vs the revision the agent last applied.")
    @GET
    @Path("/devices/{deviceId}/configStatus")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getConfigStatus(@PathParam("deviceId") String deviceId) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) return Response.PERMISSION_DENIED();
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) return Response.ERROR("error.agent.device.unknown");
        if (device.getCustomerId() != customerId.get()) return Response.PERMISSION_DENIED();

        ConfigStatusView v = new ConfigStatusView();
        v.setConfigurationId(device.getConfigurationId());
        v.setCurrentRevision(configReconciler.currentRevision(device));
        DeviceState state = commandDAO.getState(deviceId);
        if (state != null) { v.setAppliedRevision(state.getAppliedConfigRevision()); v.setAppliedAt(state.getAppliedConfigAt()); }
        Set<String> tokens = AgentCapabilityTokens.flatten(commandDAO.getDeviceCapabilities(deviceId));
        boolean supported = AgentCapabilityTokens.isAllowed(DesiredConfigBuilder.CAPABILITY, tokens);
        v.setSupported(supported);
        v.setInSync(v.getCurrentRevision() != null && v.getCurrentRevision().equals(v.getAppliedRevision()));
        v.setLastCommand(ConfigStatusView.LastCommand.from(commandDAO.findLatestOfType(deviceId, DesiredConfigBuilder.COMMAND_TYPE)));
        return Response.OK(v);
    }

    // =================================================================================================================
    @ApiOperation(value = "Configuration sync summary", notes = "Per configuration: how many devices applied its current revision.")
    @GET
    @Path("/configurations/syncSummary")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSyncSummary() {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) return Response.PERMISSION_DENIED();
        Map<Integer, String> revisionByConfig = new HashMap<>();
        Map<Integer, ConfigSyncSummary> out = new LinkedHashMap<>();
        for (DeviceSyncRow row : commandDAO.listDevicesForSync(customerId.get())) {
            Integer cfgId = row.getConfigurationId();
            ConfigSyncSummary s = out.computeIfAbsent(cfgId, id -> { ConfigSyncSummary x = new ConfigSyncSummary(); x.setConfigurationId(id); return x; });
            s.setTotal(s.getTotal() + 1);
            String current = revisionByConfig.computeIfAbsent(cfgId, id -> {
                Device probe = new Device(); probe.setConfigurationId(id); probe.setCustomerId(customerId.get());
                return configReconciler.currentRevision(probe);
            });
            Set<String> tokens = AgentCapabilityTokens.flatten(row.getCapabilitiesJson());
            boolean supported = AgentCapabilityTokens.isAllowed(DesiredConfigBuilder.CAPABILITY, tokens);
            if (!supported) {
                s.setUnsupported(s.getUnsupported() + 1);
            } else if (row.getAppliedConfigRevision() == null) {
                s.setNeverSeen(s.getNeverSeen() + 1);
            } else if (row.getAppliedConfigRevision().equals(current)) {
                s.setInSync(s.getInSync() + 1);
            } else {
                s.setOutOfSync(s.getOutOfSync() + 1);
            }
        }
        return Response.OK(new ArrayList<>(out.values()));
    }

    // =================================================================================================================
    @ApiOperation(value = "Device telemetry", notes = "Latest full census snapshot (JSON) reported by the agent.")
    @GET
    @Path("/devices/{deviceId}/telemetry")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getTelemetry(@PathParam("deviceId") String deviceId) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) {
            return Response.ERROR("error.agent.device.unknown");
        }
        if (device.getCustomerId() != customerId.get()) {
            return Response.PERMISSION_DENIED();
        }
        com.hmdm.persistence.domain.DeviceState s = commandDAO.getState(deviceId);
        String json = s == null ? null : s.getTelemetry();
        try {
            return Response.OK(json == null ? null
                    : new com.fasterxml.jackson.databind.ObjectMapper().readTree(json));
        } catch (Exception e) {
            return Response.OK(null);
        }
    }

    // =================================================================================================================
    @ApiOperation(value = "Device events", notes = "Agent lifecycle event timeline, newest first.")
    @GET
    @Path("/devices/{deviceId}/events")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listEvents(@PathParam("deviceId") String deviceId,
                               @QueryParam("since") Long since,
                               @QueryParam("limit") Integer limit) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) {
            return Response.ERROR("error.agent.device.unknown");
        }
        if (device.getCustomerId() != customerId.get()) {
            return Response.PERMISSION_DENIED();
        }
        long sinceMillis = since == null ? 0L : since;
        int cap = limit == null ? 200 : Math.min(limit, 500);
        return Response.OK(commandDAO.listEvents(deviceId, sinceMillis, cap));
    }

    // =================================================================================================================
    @ApiOperation(value = "Device history", notes = "Traceability: battery, network, signal (RSSI) and free space "
            + "samples (one every 5 min, kept 30 days) plus the connection sessions, oldest first.")
    @GET
    @Path("/devices/{deviceId}/history")
    @Produces(MediaType.APPLICATION_JSON)
    public Response history(@PathParam("deviceId") String deviceId, @QueryParam("days") Integer days) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) {
            return Response.ERROR("error.agent.device.unknown");
        }
        if (device.getCustomerId() != customerId.get()) {
            return Response.PERMISSION_DENIED();
        }
        int d = days == null ? 7 : Math.max(1, Math.min(30, days));
        long from = System.currentTimeMillis() - d * 24L * 3600_000L;
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("from", from);
        out.put("gapMs", AgentResource.CONNECTION_GAP_MS);
        out.put("metrics", commandDAO.listMetrics(deviceId, from));
        out.put("connections", commandDAO.listDeviceConnections(deviceId, from));
        return Response.OK(out);
    }

    // =================================================================================================================
    @ApiOperation(value = "Command history", notes = "Command lifecycle history for a device, newest first. "
            + "Payloads are never returned (they can embed secrets); app commands carry the package as 'subject'.")
    @GET
    @Path("/devices/{deviceId}/commands")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listCommands(@PathParam("deviceId") String deviceId,
                                 @QueryParam("since") Long since) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) {
            return Response.ERROR("error.agent.device.unknown");
        }
        if (device.getCustomerId() != customerId.get()) {
            return Response.PERMISSION_DENIED();
        }
        long sinceMillis = since == null ? 0L : since;
        return Response.OK(CommandHistoryView.fromAll(commandDAO.listHistory(deviceId, sinceMillis, 200)));
    }

    // =================================================================================================================
    @ApiOperation(value = "Location history", notes = "Recent location breadcrumb trail for a device, newest first.")
    @GET
    @Path("/devices/{deviceId}/locations")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listLocations(@PathParam("deviceId") String deviceId,
                                  @QueryParam("since") Long since) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) {
            return Response.ERROR("error.agent.device.unknown");
        }
        if (device.getCustomerId() != customerId.get()) {
            return Response.PERMISSION_DENIED();
        }
        long sinceMillis = since == null ? 0L : since;
        return Response.OK(commandDAO.listLocations(deviceId, sinceMillis, 500));
    }

    /** Upper bound on the fixes one fleet-map query returns (the response says when it was reached). */
    private static final int FLEET_MAP_MAX_FIXES = 50_000;
    /** Longest time range the fleet map may ask for. */
    private static final long FLEET_MAP_MAX_RANGE_MILLIS = 31L * 24 * 60 * 60 * 1000;

    // =================================================================================================================
    @ApiOperation(value = "Fleet locations", notes = "Where every device of the customer was between 'from' and 'to' "
            + "(epoch ms, at most 31 days apart): one entry per device with a fix in range, its fixes oldest first. "
            + "'truncated' is true when the range held more fixes than one response carries.")
    @GET
    @Path("/locations")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listFleetLocations(@QueryParam("from") Long from, @QueryParam("to") Long to) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        if (from == null || to == null || to < from || to - from > FLEET_MAP_MAX_RANGE_MILLIS) {
            return Response.ERROR("error.agent.locations.range");
        }
        List<com.hmdm.persistence.domain.DeviceLocation> fixes =
                commandDAO.listFleetLocations(customerId.get(), from, to, FLEET_MAP_MAX_FIXES + 1);
        boolean truncated = fixes.size() > FLEET_MAP_MAX_FIXES;
        if (truncated) {
            fixes = fixes.subList(0, FLEET_MAP_MAX_FIXES);
        }
        Map<String, Map<String, Object>> byDevice = new LinkedHashMap<>();
        for (com.hmdm.persistence.domain.DeviceLocation f : fixes) {
            Map<String, Object> entry = byDevice.computeIfAbsent(f.getDeviceNumber(), n -> {
                Map<String, Object> e = new LinkedHashMap<>();
                Device d = unsecureDAO.getDeviceByNumber(n);
                e.put("number", n);
                e.put("description", d == null ? null : d.getDescription());
                e.put("fixes", new ArrayList<Map<String, Object>>());
                return e;
            });
            Map<String, Object> fix = new LinkedHashMap<>();
            fix.put("lat", f.getLat());
            fix.put("lon", f.getLon());
            fix.put("accuracy", f.getAccuracy());
            fix.put("provider", f.getProvider());
            fix.put("capturedAt", f.getCapturedAt());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = (List<Map<String, Object>>) entry.get("fixes");
            list.add(fix);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from);
        out.put("to", to);
        out.put("truncated", truncated);
        out.put("devices", new ArrayList<>(byDevice.values()));
        return Response.OK(out);
    }

    // =================================================================================================================
    @ApiOperation(value = "Remote control status", notes = "What remote view/control the device offers (ADR 0010): "
            + "tier, transports, whether the session can be encrypted, and its power mode (adaptive = slow to answer while locked).")
    @GET
    @Path("/devices/{deviceId}/remote")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getRemoteStatus(@PathParam("deviceId") String deviceId) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) {
            return Response.ERROR("error.agent.device.unknown");
        }
        if (device.getCustomerId() != customerId.get()) {
            return Response.PERMISSION_DENIED();
        }
        String caps = commandDAO.getDeviceCapabilities(deviceId);
        DeviceState state = commandDAO.getState(deviceId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tier", RemoteSessions.tier(caps));
        out.put("transports", RemoteSessions.transports(caps));
        out.put("encrypted", RemoteSessions.supportsEncrypted(caps));
        out.put("powerMode", state == null ? null : state.getPowerMode());
        out.put("locked", state == null ? null : state.getLocked());
        out.put("lastSeen", device.getLastUpdate());
        return Response.OK(out);
    }

    // =================================================================================================================
    @ApiOperation(value = "Start remote session", notes = "Mints a one-time numeric session id and VNC password, queues "
            + "remote.vnc.start (encrypted tunnel when the agent supports it) and returns what the viewer needs. "
            + "Body: { viewOnly }. The password is returned only here, to the operator who started the session.")
    @POST
    @Path("/devices/{deviceId}/remote/start")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response startRemoteSession(@PathParam("deviceId") String deviceId, Map<String, Object> body) {
        if (!canEditDevices("start remote session")) {
            return Response.PERMISSION_DENIED();
        }
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) {
            return Response.ERROR("error.agent.device.unknown");
        }
        if (device.getCustomerId() != customerId.get()) {
            return Response.PERMISSION_DENIED();
        }
        boolean viewOnly = body != null && Boolean.TRUE.equals(body.get("viewOnly"));
        String caps = commandDAO.getDeviceCapabilities(deviceId);
        String tier = RemoteSessions.tier(caps);
        if (!"view".equals(tier) && !"control".equals(tier)) {
            return Response.ERROR("error.agent.remote.unsupported");
        }
        // A 'view' tier may be stale (the input service is often switched on after the last check-in): the
        // remote.control gate holds the command until the device advertises control, so queue it anyway.
        boolean encrypted = RemoteSessions.supportsEncrypted(caps);
        String sessionId = RemoteSessions.newSessionId();
        String password = RemoteSessions.newPassword();

        AgentCommand command = new AgentCommand();
        command.setDeviceNumber(deviceId);
        command.setType(RemoteSessions.COMMAND_START);
        command.setPayload(RemoteSessions.startPayload(sessionId, password, viewOnly, encrypted));
        command.setRequiresCapability(viewOnly ? "remote.view" : "remote.control");
        command.setStatus("pending");
        command.setCreatedAt(System.currentTimeMillis());
        commandDAO.insert(command);
        wakeHub.wake(deviceId, "commands");

        logger.info("Remote session command {} queued for device {} (viewOnly={}, encrypted={})",
                command.getId(), deviceId, viewOnly, encrypted);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("commandId", command.getId());
        out.put("sessionId", sessionId);
        out.put("password", password);
        out.put("viewOnly", viewOnly);
        out.put("encrypted", encrypted);
        return Response.OK(out);
    }

    // =================================================================================================================
    @ApiOperation(value = "Set up remote support", notes = "Installs droidVNC-NG (hosted by this server, sha256-pinned) on a "
            + "device that was enrolled without USB remote support; the agent prepares it on install.")
    @POST
    @Path("/devices/{deviceId}/remote/setup")
    @Produces(MediaType.APPLICATION_JSON)
    public Response setupRemote(@PathParam("deviceId") String deviceId) {
        if (!canEditDevices("set up remote support")) {
            return Response.PERMISSION_DENIED();
        }
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) {
            return Response.ERROR("error.agent.device.unknown");
        }
        if (device.getCustomerId() != customerId.get()) {
            return Response.PERMISSION_DENIED();
        }
        com.fasterxml.jackson.databind.node.ObjectNode p = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        p.put("url", baseUrl + com.hmdm.util.RemoteSupport.APK_PATH);
        p.put("packageName", com.hmdm.util.RemoteSupport.PACKAGE);
        p.put("versionCode", com.hmdm.util.RemoteSupport.VERSION_CODE);
        p.put("sha256", com.hmdm.util.RemoteSupport.SHA256);
        if (!commandDAO.hasOpenIdentical(deviceId, "app.install", p.toString())) {
            AgentCommand cmd = new AgentCommand();
            cmd.setDeviceNumber(deviceId);
            cmd.setType("app.install");
            cmd.setPayload(p.toString());
            cmd.setRequiresCapability(com.hmdm.util.RolloutProgress.INSTALL_CAPABILITY);
            cmd.setStatus("pending");
            cmd.setCreatedAt(System.currentTimeMillis());
            commandDAO.insert(cmd);
        }
        wakeHub.wake(deviceId, "commands");
        logger.info("Remote support (droidVNC-NG {}) queued for {}", com.hmdm.util.RemoteSupport.VERSION_NAME, deviceId);
        return Response.OK();
    }

    // =================================================================================================================
    @ApiOperation(value = "Remote-support package", notes = "The pinned droidVNC-NG this server hosts, as an app.install "
            + "spec (url, packageName, versionCode, sha256) — so the console can install it on many devices at once.")
    @GET
    @Path("/remote/package")
    @Produces(MediaType.APPLICATION_JSON)
    public Response remotePackage() {
        if (!SecurityContext.get().getCurrentCustomerId().isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("url", baseUrl + com.hmdm.util.RemoteSupport.APK_PATH);
        p.put("packageName", com.hmdm.util.RemoteSupport.PACKAGE);
        p.put("versionCode", com.hmdm.util.RemoteSupport.VERSION_CODE);
        p.put("sha256", com.hmdm.util.RemoteSupport.SHA256);
        return Response.OK(p);
    }

    // =================================================================================================================
    @ApiOperation(value = "Re-apply the configuration", notes = "Sends the device its configuration again (kiosk, policies) "
            + "even when it already applied it — e.g. back into the configuration's kiosk after a manual exit.")
    @POST
    @Path("/devices/{deviceId}/config/reapply")
    @Produces(MediaType.APPLICATION_JSON)
    public Response reapplyConfiguration(@PathParam("deviceId") String deviceId) {
        if (!canEditDevices("re-apply configuration")) {
            return Response.PERMISSION_DENIED();
        }
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) {
            return Response.ERROR("error.agent.device.unknown");
        }
        if (device.getCustomerId() != customerId.get()) {
            return Response.PERMISSION_DENIED();
        }
        if (!configReconciler.forceApply(device, System.currentTimeMillis())) {
            return Response.ERROR("error.agent.config.none");
        }
        wakeHub.wake(deviceId, "commands");
        return Response.OK();
    }

    // =================================================================================================================
    @ApiOperation(value = "Force sync", notes = "Wake the device now so it pulls pending commands + reports state.")
    @POST
    @Path("/devices/{deviceId}/sync")
    @Produces(MediaType.APPLICATION_JSON)
    public Response forceSync(@PathParam("deviceId") String deviceId) {
        if (!canEditDevices("force sync")) {
            return Response.PERMISSION_DENIED();
        }
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) {
            return Response.ERROR("error.agent.device.unknown");
        }
        if (device.getCustomerId() != customerId.get()) {
            return Response.PERMISSION_DENIED();
        }
        wakeHub.wake(deviceId, "commands");
        return Response.OK();
    }
}
