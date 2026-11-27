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
import com.hmdm.rest.json.agent.ConfigComplianceView;
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
    /** Folder administrators: their folders' devices only. */
    private com.hmdm.rest.resource.support.UserScope scope;
    private com.hmdm.persistence.DeviceDAO deviceDAO;

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
                              @javax.inject.Named("base.url") String baseUrl,
                              com.hmdm.rest.resource.support.UserScope scope,
                              com.hmdm.persistence.DeviceDAO deviceDAO) {
        this.deviceDAO = deviceDAO;
        this.scope = scope;
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

        // Optional email account to tag the enrolled device with (metadata/identification only; it never creates
        // an account on the phone). The device inherits it on enroll (device > group/folder).
        String accountEmail = body == null ? null : com.hmdm.util.AccountEmails.normalize(body.getAccountEmail());
        if (accountEmail != null && !com.hmdm.util.AccountEmails.isValid(accountEmail)) {
            return Response.ERROR("error.agent.account.email.invalid");
        }
        // A folder administrator enrolls only into their folders (and cannot pin a policy).
        if (scope.restricted() && (groupId == null || !scope.canGroup(groupId) || configurationId != null)) {
            return Response.PERMISSION_DENIED();
        }

        long now = System.currentTimeMillis();
        AgentEnrollmentToken token = new AgentEnrollmentToken();
        token.setToken(UUID.randomUUID().toString());
        token.setCustomerId(customerId.get());
        token.setConfigurationId(configurationId);
        token.setGroupId(groupId);
        token.setAccountEmail(accountEmail);
        token.setUsed(false);
        token.setCreatedAt(now);
        token.setExpiresAt(now + DEFAULT_TOKEN_TTL_MILLIS);
        // Optional declared phone number of the device's SIM: kept as typed (trimmed, capped) so the enroller can
        // write it with or without country code; the check-in comparison normalises both sides before matching.
        String expectedPhone = body == null ? null : body.getExpectedPhone();
        if (expectedPhone != null) {
            expectedPhone = expectedPhone.trim();
            if (expectedPhone.isEmpty()) {
                expectedPhone = null;
            } else if (expectedPhone.length() > 32) {
                expectedPhone = expectedPhone.substring(0, 32);
            }
        }
        token.setExpectedPhone(expectedPhone);
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
        /** The Wi-Fi does not broadcast its name: the QR tells Android to look for it (PROVISIONING_WIFI_HIDDEN). */
        public Boolean wifiHidden;
        public String wifiPassword;
        /** WPA (default), WEP or NONE. */
        public String wifiSecurity;
        /** Optional email account to tag devices enrolled through this code (metadata/identification only). */
        public String accountEmail;
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
        if (!scope.canGroup(body.groupId)) {
            return Response.PERMISSION_DENIED();
        }
        long now = System.currentTimeMillis();
        if (body.expiresAt != null && body.expiresAt <= now) {
            return Response.ERROR("error.agent.code.expiry.invalid");
        }
        String label = body.label == null ? null : body.label.trim();
        if (label != null && (label.isEmpty() || label.length() > 100)) {
            label = label.isEmpty() ? null : label.substring(0, 100);
        }
        String accountEmail = com.hmdm.util.AccountEmails.normalize(body.accountEmail);
        if (accountEmail != null && !com.hmdm.util.AccountEmails.isValid(accountEmail)) {
            return Response.ERROR("error.agent.account.email.invalid");
        }
        AgentEnrollmentToken token = new AgentEnrollmentToken();
        token.setCustomerId(customerId.get());
        token.setGroupId(body.groupId);
        token.setAccountEmail(accountEmail);
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
        if (token.getWifiSsid() != null && Boolean.TRUE.equals(body.wifiHidden)) {
            tokenDAO.updateWifiHidden(token.getId(), true);
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
        java.util.Set<Integer> mine = scope.groups();
        if (mine == null) {
            return Response.OK(tokenDAO.listCodes(customerId.get()));
        }
        return Response.OK(tokenDAO.listCodes(customerId.get()).stream()
                .filter(c -> c.getGroupId() != null && mine.contains(c.getGroupId()))
                .collect(java.util.stream.Collectors.toList()));
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
        if (scope.restricted() && tokenDAO.listCodes(customerId.get()).stream()
                .noneMatch(c -> c.getId() != null && c.getId() == id && scope.canGroup(c.getGroupId()))) {
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
    @ApiOperation(value = "Enrollment token status", notes = "What became of a single-use token: still waiting, expired, "
            + "or the device it enrolled, plus the attempts made with it. The Enroll page polls this while its QR is shown.")
    @GET
    @Path("/token/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response tokenStatus(@QueryParam("token") String value) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        AgentEnrollmentToken token = value == null ? null : tokenDAO.findByToken(value.trim());
        if (token == null || token.getCustomerId() != customerId.get()
                || (scope.restricted() && (token.getGroupId() == null || !scope.canGroup(token.getGroupId())))) {
            return Response.ERROR("error.agent.token.invalid");
        }
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("used", token.isUsed());
        out.put("usedAt", token.getUsedAt());
        out.put("expiresAt", token.getExpiresAt());
        out.put("expired", !token.isUsed() && token.getExpiresAt() != null
                && token.getExpiresAt() < System.currentTimeMillis());
        out.put("deviceNumber", token.getDeviceNumber());
        if (token.getDeviceNumber() != null) {
            Device device = unsecureDAO.getDeviceByNumber(token.getDeviceNumber());
            if (device != null) {
                out.put("deviceId", device.getId());
                if (device.getConfigurationId() != null) {
                    com.hmdm.persistence.domain.Configuration cfg = unsecureDAO.getConfigurationById(device.getConfigurationId());
                    out.put("configurationName", cfg == null ? null : cfg.getName());
                }
            }
        }
        out.put("attempts", tokenDAO.listAttemptsOfToken(token.getId()));
        return Response.OK(out);
    }

    // =================================================================================================================
    @ApiOperation(value = "Enrollment attempts", notes = "The latest enrollment attempts the server saw (agent downloads, "
            + "refused and successful enrolls) with the reason and the phone's maker, model and Android release.")
    @GET
    @Path("/enroll/attempts")
    @Produces(MediaType.APPLICATION_JSON)
    public Response enrollAttempts(@QueryParam("limit") Integer limit) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        // Attempts with a code nobody issued (a mistyped code, a QR from another server) belong to no customer. They
        // are shown to every user who is not limited to folders: on this single-organisation product that is the
        // person who has to diagnose them. (A multi-tenant install would show them to each tenant's administrators.)
        boolean includeUnknown = !scope.restricted();
        java.util.List<com.hmdm.persistence.domain.AgentEnrollAttempt> rows =
                tokenDAO.listAttempts(customerId.get(), includeUnknown, limit == null ? 100 : limit);
        java.util.Set<Integer> mine = scope.groups();
        if (mine != null) {
            rows = rows.stream().filter(a -> a.getGroupId() != null && mine.contains(a.getGroupId()))
                    .collect(java.util.stream.Collectors.toList());
        }
        return Response.OK(rows);
    }

    @ApiOperation(value = "Enrollment attempts summary", notes = "How many phones tried to enroll in the last 7 days and "
            + "still are not enrolled, and how many of them are Device Owner without a token.")
    @GET
    @Path("/enroll/attempts/summary")
    @Produces(MediaType.APPLICATION_JSON)
    public Response enrollAttemptsSummary() {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        // Counted over the whole customer: a folder administrator sees the list of their folders, not this total.
        if (scope.restricted()) {
            out.put("unresolvedDevices", null);
            out.put("ownerWithoutToken", null);
        } else {
            int[] n = tokenDAO.countUnresolved(customerId.get(), true, 7);
            out.put("unresolvedDevices", n[0]);
            out.put("ownerWithoutToken", n[1]);
        }
        out.put("days", 7);
        return Response.OK(out);
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
        // Interrupting actions (lock/reboot/passcode/wipe) need the extra "disruptive_actions" permission,
        // so a view-only folder role can run the safe actions but not these.
        if (requiresDisruptivePermission(body.getType()) && !SecurityContext.get().hasPermission("disruptive_actions")) {
            logger.warn("Permission denied: {} requires disruptive_actions (user {})",
                    body.getType(), SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        // Installing/uninstalling apps modifies the device's software — needs manage_fleet (view-only roles cannot).
        if (requiresFleetPermission(body.getType()) && !SecurityContext.get().hasPermission("manage_fleet")) {
            logger.warn("Permission denied: {} requires manage_fleet (user {})",
                    body.getType(), SecurityContext.get().getCurrentUserName());
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
        CommandHistoryView view = CommandHistoryView.from(command);
        // Not connected right now: the command is not lost, it waits in the queue; the console says so.
        view.setDeviceOnline(wakeHub.isOnline(deviceId));
        return Response.OK(view);
    }

    // =================================================================================================================
    @ApiOperation(value = "Cancel a queued command", notes = "Withdraws a command the device has not run yet (pending, or "
            + "delivered without an answer). Needs the same permissions as queueing that type of command.")
    @POST
    @Path("/devices/{deviceId}/commands/{commandId}/cancel")
    @Produces(MediaType.APPLICATION_JSON)
    public Response cancelCommand(@PathParam("deviceId") String deviceId, @PathParam("commandId") Integer commandId) {
        if (!canEditDevices("cancel agent command")) {
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
        if (device.getCustomerId() != customerId.get() || !scope.canDeviceNumber(customerId.get(), deviceId)) {
            return Response.PERMISSION_DENIED();
        }
        AgentCommand command = commandId == null ? null : commandDAO.findByDeviceAndId(deviceId, commandId);
        if (command == null) {
            return Response.ERROR("error.agent.command.unknown");
        }
        // Whoever may not send a lock or an install may not withdraw somebody else's either.
        if (requiresDisruptivePermission(command.getType()) && !SecurityContext.get().hasPermission("disruptive_actions")) {
            return Response.PERMISSION_DENIED();
        }
        if (requiresFleetPermission(command.getType()) && !SecurityContext.get().hasPermission("manage_fleet")) {
            return Response.PERMISSION_DENIED();
        }
        String who = SecurityContext.get().getCurrentUserName();
        boolean wasDelivered = "delivered".equals(command.getStatus());
        String detail = "Cancelado por " + who + (wasDelivered
                ? " (ya se había entregado al equipo: no se reenvía, pero pudo haberse ejecutado)" : "");
        if (!commandDAO.cancel(deviceId, commandId, detail)) {
            // Already ran, expired or was cancelled: nothing left to withdraw.
            return Response.ERROR("error.agent.command.notCancellable");
        }
        logger.info("Agent command {} ({}) of device {} cancelled by {}", commandId, command.getType(), deviceId, who);
        return Response.OK(CommandHistoryView.from(commandDAO.findByDeviceAndId(deviceId, commandId)));
    }

    // =================================================================================================================
    @ApiOperation(value = "Wiped devices", notes = "Devices that received a remote wipe and stopped reporting: number -> "
            + "{wipedAt, wipedBy}. The device row is kept; the mark goes away if the device reports again.")
    @GET
    @Path("/wiped")
    @Produces(MediaType.APPLICATION_JSON)
    public Response wipedDevices() {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        java.util.Set<String> visible = scope.deviceNumbers();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> row : commandDAO.refreshWipedDevices(customerId.get())) {
            String number = String.valueOf(row.get("number"));
            if (visible != null && !visible.contains(number)) continue;
            Map<String, Object> mark = new LinkedHashMap<>();
            mark.put("wipedAt", row.get("wipedat"));
            mark.put("wipedBy", row.get("wipedby"));
            out.put(number, mark);
        }
        return Response.OK(out);
    }

    /** Actions that interrupt the user (lock/reboot/change passcode/wipe). A role without the
     *  "disruptive_actions" permission (e.g. a view-only folder role) runs everything else but not these. */
    private static final Set<String> DISRUPTIVE_TYPES =
            Set.of("device.lock", "device.reboot", "device.passcodereset", "device.wipe");

    private static boolean requiresDisruptivePermission(String type) {
        return type != null && DISRUPTIVE_TYPES.contains(type.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /** App management (install/uninstall) modifies the device's software — fleet management, so it needs
     *  manage_fleet. A view-only folder role can see the installed apps but not add or remove them. */
    private static final Set<String> APP_MGMT_TYPES =
            Set.of("app.install", "app.uninstall");

    private static boolean requiresFleetPermission(String type) {
        return type != null && APP_MGMT_TYPES.contains(type.trim().toLowerCase(java.util.Locale.ROOT));
    }

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
        // Destructive types are rejected in bulk; device.passcodeReset is allowed only to SET a (non-empty)
        // lock password en masse, never to clear it. See BulkCommandPolicy.
        if (!com.hmdm.rest.resource.support.BulkCommandPolicy.allowedInBulk(type, req.getCommand().getPayload())) {
            return Response.ERROR("error.agent.command.bulkForbidden");
        }
        if (requiresDisruptivePermission(type) && !SecurityContext.get().hasPermission("disruptive_actions")) {
            logger.warn("Permission denied: bulk {} requires disruptive_actions (user {})",
                    type, SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }

        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }

        int queued = 0;
        int offline = 0;
        List<Integer> skipped = new ArrayList<>();
        for (Integer id : req.getDeviceIds()) {
            if (id == null) { continue; }
            Device device = unsecureDAO.getDeviceById(id);
            if (device == null || device.getCustomerId() != customerId.get() || !scope.canDeviceId(customerId.get(), id)) {
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
            if (!wakeHub.isOnline(device.getNumber())) offline++;
            wakeHub.wake(device.getNumber(), "commands");
            queued++;
        }

        logger.info("Bulk command {} queued for {} device(s), {} skipped", type, queued, skipped.size());
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("queued", queued);
        result.put("skipped", skipped);
        // Of the queued ones, how many devices are not connected now: theirs run when they come back.
        result.put("offline", offline);
        return Response.OK(result);
    }

    // =================================================================================================================
    @ApiOperation(value = "Devices with an app installed",
            notes = "Fleet-level app targeting: this customer's devices whose last-reported inventory contains the given "
                    + "package (?pkg=com.example). Each entry carries the numeric device id, so the console can feed the set "
                    + "straight into POST /bulk/commands (e.g. a mass app.uninstall). Folder administrators see only their folders.")
    @GET
    @Path("/apps/devices")
    @Produces(MediaType.APPLICATION_JSON)
    public Response devicesWithApp(@QueryParam("pkg") String pkg) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        if (pkg == null || pkg.trim().isEmpty()) {
            return Response.ERROR("error.agent.app.pkg.required");
        }
        List<com.hmdm.rest.json.DeviceWithApp> rows =
                deviceDAO.getDevicesWithPackage(customerId.get(), pkg.trim());
        // Folder administrators only see their own folders' devices (null = unrestricted).
        Set<String> visible = scope.deviceNumbers();
        if (visible != null) {
            rows.removeIf(r -> r.getNumber() == null || !visible.contains(r.getNumber()));
        }
        return Response.OK(rows);
    }

    // =================================================================================================================
    @ApiOperation(value = "Live devices", notes = "Numbers of this customer's devices whose live channel (wake socket) is "
            + "open now: connected even when their last report is older than the report window.")
    @GET
    @Path("/live")
    @Produces(MediaType.APPLICATION_JSON)
    public Response liveDevices() {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        java.util.List<String> mine = new java.util.ArrayList<>();
        java.util.Set<String> visible = scope.deviceNumbers();
        for (String number : wakeHub.liveNumbers()) {
            if (visible != null && !visible.contains(number)) continue;
            Device device = unsecureDAO.getDeviceByNumber(number);
            if (device != null && device.getCustomerId() == customerId.get()) mine.add(number);
        }
        return Response.OK(mine);
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
        DeviceState state = commandDAO.getState(deviceId);
        // Android 16 (SDK 36)+ runs a SOFT kiosk (persistent HOME + status bar off, no lock-task), so
        // lockTaskModeState is NONE and the agent reports kioskActive=false even while the kiosk launcher is
        // showing. When the device's configuration has a kiosk and the agent is 0.9+, report it as active —
        // computed here (server-side) so it is right for the viewer role too, which cannot read configurations.
        if (state != null && !Boolean.TRUE.equals(state.getKioskActive())
                && AgentResource.atLeast(state.getAndroidRelease(), 16, 0, 0)
                && AgentResource.atLeast(state.getAgentVersion(), 0, 9, 0)) {
            Integer cfgId = device.getConfigurationId();
            com.hmdm.persistence.domain.Configuration cfg = cfgId == null ? null : unsecureDAO.getConfigurationById(cfgId);
            // ...but honour a manual "exit kiosk": a soft kiosk cannot report that it left, so if the latest
            // kiosk.exit is newer than the latest config.apply the operator took it out — leave it inactive.
            if (cfg != null && cfg.isKioskMode() && !kioskExitedSinceApply(deviceId)) {
                state.setKioskActive(Boolean.TRUE);
            }
        }
        return Response.OK(state);
    }

    /** True when the device's most recent kiosk.exit is newer than its most recent config.apply (manually taken out). */
    private boolean kioskExitedSinceApply(String deviceNumber) {
        com.hmdm.persistence.domain.AgentCommand exit = commandDAO.findLatestOfType(deviceNumber, "kiosk.exit");
        if (exit == null || exit.getCreatedAt() == null) {
            return false;
        }
        com.hmdm.persistence.domain.AgentCommand apply =
                commandDAO.findLatestOfType(deviceNumber, DesiredConfigBuilder.COMMAND_TYPE);
        long applyAt = (apply == null || apply.getCreatedAt() == null) ? 0L : apply.getCreatedAt();
        return exit.getCreatedAt() > applyAt;
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
    @ApiOperation(value = "Configuration compliance", notes = "Current revision, sync counts and the devices that have not applied the latest version of one configuration.")
    @GET
    @Path("/configurations/{id}/compliance")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getConfigCompliance(@PathParam("id") Integer id) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) return Response.PERMISSION_DENIED();

        com.hmdm.persistence.domain.Device probe = new com.hmdm.persistence.domain.Device();
        probe.setConfigurationId(id);
        probe.setCustomerId(customerId.get());
        String current = configReconciler.currentRevision(probe);

        ConfigComplianceView view = new ConfigComplianceView();
        view.setConfigurationId(id);
        view.setCurrentRevision(current);
        List<ConfigComplianceView.Device> drifting = new ArrayList<>();
        for (com.hmdm.persistence.domain.ConfigDriftRow row : commandDAO.listDevicesForConfig(customerId.get(), id)) {
            view.setTotal(view.getTotal() + 1);
            Set<String> tokens = AgentCapabilityTokens.flatten(row.getCapabilitiesJson());
            boolean supported = AgentCapabilityTokens.isAllowed(DesiredConfigBuilder.CAPABILITY, tokens);
            String status;
            if (!supported) {
                view.setUnsupported(view.getUnsupported() + 1);
                status = "unsupported";
            } else if (row.getAppliedConfigRevision() == null) {
                view.setNeverSeen(view.getNeverSeen() + 1);
                status = "neverSeen";
            } else if (row.getAppliedConfigRevision().equals(current)) {
                view.setInSync(view.getInSync() + 1);
                continue; // compliant: not listed
            } else {
                view.setOutOfSync(view.getOutOfSync() + 1);
                status = "outOfSync";
            }
            ConfigComplianceView.Device d = new ConfigComplianceView.Device();
            d.setNumber(row.getDeviceNumber());
            d.setDescription(row.getDescription());
            d.setAppliedRevision(row.getAppliedConfigRevision());
            d.setAppliedAt(row.getAppliedConfigAt());
            d.setLastUpdate(row.getLastUpdate());
            d.setStatus(status);
            drifting.add(d);
        }
        view.setDevices(drifting);
        return Response.OK(view);
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
    @ApiOperation(value = "Device events", notes = "Agent lifecycle event timeline, newest first. "
            + "Pass 'types' (comma-separated) to get just those event types — e.g. the on-device user-activity "
            + "slice (appOpened,cameraOpened) for the device view's activity tab.")
    @GET
    @Path("/devices/{deviceId}/events")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listEvents(@PathParam("deviceId") String deviceId,
                               @QueryParam("since") Long since,
                               @QueryParam("limit") Integer limit,
                               @QueryParam("types") String types) {
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
        if (types != null && !types.trim().isEmpty()) {
            java.util.List<String> wanted = new java.util.ArrayList<>();
            for (String t : types.split(",")) {
                String tt = t.trim();
                if (!tt.isEmpty()) wanted.add(tt);
            }
            return Response.OK(commandDAO.listEventsByTypes(deviceId, sinceMillis, wanted, cap));
        }
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
    @ApiOperation(value = "App usage report", notes = "Per day and app: time on screen, data over Wi-Fi and mobile, and "
            + "the battery estimate (battery lost while the app was on screen). Up to 90 days.")
    @GET
    @Path("/devices/{deviceId}/usage")
    @Produces(MediaType.APPLICATION_JSON)
    public Response appUsage(@PathParam("deviceId") String deviceId, @QueryParam("days") Integer days) {
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
        int d = days == null ? 7 : Math.max(0, Math.min(com.hmdm.persistence.AgentCommandDAO.APP_USAGE_KEEP_DAYS, days));
        return Response.OK(commandDAO.listAppUsage(deviceId, d));
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
        // A phone that is not checking in gets no sweep of its own: close here what already expired, so the
        // history never shows "pending" for a command that will no longer run. Redelivery is decided only on
        // the phone's own check-in, right after its results were recorded.
        try {
            commandDAO.expireOverdue(deviceId);
        } catch (Exception e) {
            logger.warn("Could not expire overdue commands of {}: {}", deviceId, e.getMessage());
        }
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
        // A folder editor must be scoped IN THE QUERY, before the fix cap — otherwise a long range fills the cap with
        // other folders' fixes and the per-device filter below drops them all, leaving the map empty.
        java.util.Set<String> visible = scope.deviceNumbers();
        List<com.hmdm.persistence.domain.DeviceLocation> fixes;
        if (scope.restricted()) {
            fixes = (visible == null || visible.isEmpty())
                    ? java.util.Collections.emptyList()
                    : commandDAO.listFleetLocationsScoped(customerId.get(), from, to, FLEET_MAP_MAX_FIXES + 1, String.join(",", visible));
        } else {
            fixes = commandDAO.listFleetLocations(customerId.get(), from, to, FLEET_MAP_MAX_FIXES + 1);
        }
        boolean truncated = fixes.size() > FLEET_MAP_MAX_FIXES;
        if (truncated) {
            fixes = fixes.subList(0, FLEET_MAP_MAX_FIXES);
        }
        Map<String, Map<String, Object>> byDevice = new LinkedHashMap<>();
        for (com.hmdm.persistence.domain.DeviceLocation f : fixes) {
            if (visible != null && !visible.contains(f.getDeviceNumber())) continue;
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
    public static class RecoverBody {
        public String number;
        public Integer groupId;
        public String description;
    }

    @ApiOperation(value = "Deleted devices that can come back", notes = "Devices deleted from the console in the last 90 days; "
            + "a phone that is still enrolled re-appears by itself on its next check-in.")
    @GET
    @Path("/devices/deleted")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deletedDevices() {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        return Response.OK(commandDAO.listTombstones(customerId.get()));
    }

    @ApiOperation(value = "Forget a deleted device", notes = "It will no longer come back by itself (use it after the "
            + "phone was reset or re-enrolled, or to withdraw a recovery claim).")
    @javax.ws.rs.DELETE
    @Path("/devices/deleted/{number}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response forgetDeleted(@PathParam("number") String number) {
        if (!canEditDevices("forget deleted device")) {
            return Response.PERMISSION_DENIED();
        }
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        return commandDAO.forgetTombstone(customerId.get(), number) ? Response.OK() : Response.ERROR("error.agent.device.unknown");
    }

    @ApiOperation(value = "Recover a deleted device", notes = "For a device deleted before its identity was kept: the "
            + "administrator vouches for that device id, and its next check-in (within 7 days) re-creates it in the folder.")
    @POST
    @Path("/devices/recover")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response recoverDevice(RecoverBody body) {
        if (!canEditDevices("recover deleted device")) {
            return Response.PERMISSION_DENIED();
        }
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        String number = body == null || body.number == null ? "" : body.number.trim().toLowerCase(java.util.Locale.ROOT);
        if (!number.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) {
            return Response.ERROR("El identificador del dispositivo no es válido.");
        }
        if (unsecureDAO.getDeviceByNumber(number) != null) {
            return Response.ERROR("Ese dispositivo ya está en la consola.");
        }
        if (body.groupId != null && commandDAO.findGroup(customerId.get(), body.groupId) == null) {
            return Response.ERROR("error.group.not.found");
        }
        String description = body.description == null || body.description.trim().isEmpty() ? null
                : body.description.trim().substring(0, Math.min(100, body.description.trim().length()));
        commandDAO.claimTombstone(number, customerId.get(), description, body.groupId);
        logger.info("Deleted device {} claimed for recovery into folder {} (customer {})", number, body.groupId, customerId.get());
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
    @ApiOperation(value = "List the device's kiosk apps", notes = "Packages shown in THIS device's kiosk on top of its policy.")
    @GET
    @Path("/devices/{deviceId}/kioskApps")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listKioskApps(@PathParam("deviceId") String deviceId) {
        if (!canEditDevices("list device kiosk apps")) return Response.PERMISSION_DENIED();
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) return Response.PERMISSION_DENIED();
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) return Response.ERROR("error.agent.device.unknown");
        if (device.getCustomerId() != customerId.get()) return Response.PERMISSION_DENIED();
        return Response.OK(commandDAO.listDeviceKioskApps(device.getId()));
    }

    // =================================================================================================================
    @ApiOperation(value = "Show an app in this device's kiosk",
            notes = "Adds a package to THIS device's kiosk (no per-device policy) and re-applies. Body: { packageName }.")
    @POST
    @Path("/devices/{deviceId}/kioskApps")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response addKioskApp(@PathParam("deviceId") String deviceId, Map<String, Object> body) {
        if (!SecurityContext.get().hasPermission("manage_fleet")) {
            logger.warn("Permission denied: add kiosk app requires manage_fleet (user {})", SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) return Response.PERMISSION_DENIED();
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) return Response.ERROR("error.agent.device.unknown");
        if (device.getCustomerId() != customerId.get()) return Response.PERMISSION_DENIED();
        String pkg = body == null || body.get("packageName") == null ? null : String.valueOf(body.get("packageName")).trim();
        if (pkg == null || pkg.isEmpty() || pkg.length() > 255) return Response.ERROR("error.agent.command.invalid");
        commandDAO.addDeviceKioskApp(device.getId(), pkg);
        configReconciler.forceApply(device, System.currentTimeMillis());
        wakeHub.wake(deviceId, "commands");
        logger.info("Kiosk app {} added to device {} (per-device)", pkg, deviceId);
        return Response.OK(commandDAO.listDeviceKioskApps(device.getId()));
    }

    // =================================================================================================================
    @ApiOperation(value = "Remove an app from this device's kiosk", notes = "Removes a per-device kiosk package and re-applies.")
    @DELETE
    @Path("/devices/{deviceId}/kioskApps/{pkg}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response removeKioskApp(@PathParam("deviceId") String deviceId, @PathParam("pkg") String pkg) {
        if (!SecurityContext.get().hasPermission("manage_fleet")) {
            logger.warn("Permission denied: remove kiosk app requires manage_fleet (user {})", SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) return Response.PERMISSION_DENIED();
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) return Response.ERROR("error.agent.device.unknown");
        if (device.getCustomerId() != customerId.get()) return Response.PERMISSION_DENIED();
        commandDAO.removeDeviceKioskApp(device.getId(), pkg == null ? "" : pkg.trim());
        configReconciler.forceApply(device, System.currentTimeMillis());
        wakeHub.wake(deviceId, "commands");
        logger.info("Kiosk app {} removed from device {} (per-device)", pkg, deviceId);
        return Response.OK(commandDAO.listDeviceKioskApps(device.getId()));
    }

    // =================================================================================================================
    @ApiOperation(value = "This device's share-data override",
            notes = "The per-device \"share data\" (tethering) override: allow / block, or absent when it follows the policy.")
    @GET
    @Path("/devices/{deviceId}/dataSharing")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getDataSharing(@PathParam("deviceId") String deviceId) {
        if (!canEditDevices("read device data-sharing")) return Response.PERMISSION_DENIED();
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) return Response.PERMISSION_DENIED();
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) return Response.ERROR("error.agent.device.unknown");
        if (device.getCustomerId() != customerId.get()) return Response.PERMISSION_DENIED();
        return Response.OK(java.util.Collections.singletonMap("state", commandDAO.getDeviceDataSharing(device.getId())));
    }

    // =================================================================================================================
    @ApiOperation(value = "Override share data on this device",
            notes = "Per-device \"share data\" (tethering) override on top of the policy. Body: { state: \"allow\" | \"block\" "
                    + "| \"policy\" }. \"policy\" clears the override. The override wins over the policy; an immediate "
                    + "device.dataSharing command applies it now and the device's config.apply carries it durably.")
    @POST
    @Path("/devices/{deviceId}/dataSharing")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response setDataSharing(@PathParam("deviceId") String deviceId, Map<String, Object> body) {
        if (!canEditDevices("override device data-sharing")) return Response.PERMISSION_DENIED();
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) return Response.PERMISSION_DENIED();
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) return Response.ERROR("error.agent.device.unknown");
        if (device.getCustomerId() != customerId.get()) return Response.PERMISSION_DENIED();

        String state = body == null || body.get("state") == null ? "" :
                String.valueOf(body.get("state")).trim().toLowerCase(java.util.Locale.ROOT);
        if (!"allow".equals(state) && !"block".equals(state) && !"policy".equals(state)) {
            return Response.ERROR("error.agent.command.invalid");
        }

        if ("policy".equals(state)) {
            // Back to the policy: drop the override. The changed desired-config revision makes the next check-in
            // reconcile push a config.apply that re-derives tethering from the policy.
            commandDAO.clearDeviceDataSharing(device.getId());
        } else {
            commandDAO.setDeviceDataSharing(device.getId(), state);
            // Immediate effect for capable agents; older agents get it via the config.apply the override triggers.
            AgentCommand command = new AgentCommand();
            command.setDeviceNumber(deviceId);
            command.setType("device.dataSharing");
            command.setPayload("{\"enabled\":" + ("allow".equals(state)) + "}");
            command.setRequiresCapability("device.dataSharing");
            command.setStatus("pending");
            command.setCreatedAt(System.currentTimeMillis());
            commandDAO.insert(command);
        }
        // Wake the device so it checks in now (delivers the command and lets reconcile push the config.apply).
        wakeHub.wake(deviceId, "commands");
        logger.info("Data-sharing override of device {} set to {}", deviceId, state);
        return Response.OK(java.util.Collections.singletonMap("state", commandDAO.getDeviceDataSharing(device.getId())));
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
