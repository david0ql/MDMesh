package com.hmdm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.notification.AgentWakeHub;
import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.Device;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.agent.DesiredConfig;
import com.hmdm.rest.resource.support.ConfigReconciler;
import com.hmdm.security.SecurityContext;
import com.hmdm.util.AgentCapabilityTokens;
import com.hmdm.util.AppPermissionRules;
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
import javax.ws.rs.core.MediaType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * <p>App permissions of ONE device: what its policy asks for, the device's own rules on top (grant / deny / prompt per
 * app and permission, special accesses), and the real state its agent last reported.</p>
 *
 * <p>Folder administrators only reach their own devices: the path is covered by {@code UserScopeFilter}'s
 * {@code /agent/v1/devices/{number}/...} rule. Reading is open to any user of the customer; changing the rules needs
 * {@code edit_devices}, like every other per-device setting.</p>
 */
@Singleton
@Path("/private/agent/v1/devices/{deviceId}/permissions")
@Api(tags = {"Agent v1 admin"})
public class DevicePermissionsResource {
    private static final Logger logger = LoggerFactory.getLogger(DevicePermissionsResource.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Capability of an agent that enforces and reports app permissions. */
    public static final String CAPABILITY = "device.permissionScan";

    private AgentCommandDAO commandDAO;
    private UnsecureDAO unsecureDAO;
    private ConfigReconciler configReconciler;
    private AgentWakeHub wakeHub;

    /** A constructor required by Swagger. */
    public DevicePermissionsResource() {
    }

    @Inject
    public DevicePermissionsResource(AgentCommandDAO commandDAO, UnsecureDAO unsecureDAO, ConfigReconciler configReconciler,
                                     AgentWakeHub wakeHub) {
        this.commandDAO = commandDAO;
        this.unsecureDAO = unsecureDAO;
        this.configReconciler = configReconciler;
        this.wakeHub = wakeHub;
    }

    private Device device(String number) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) return null;
        Device device = unsecureDAO.getDeviceByNumber(number);
        return device == null || device.getCustomerId() != customerId.get() ? null : device;
    }

    @ApiOperation(value = "App permissions of a device",
            notes = "supported: the agent enforces and reports permissions. policy: what the device's policy asks for. "
                    + "override: the device's own rules by package. effective: what is sent to the device (policy + override). "
                    + "report: the agent's last report (effective default policy, whether the admin may grant sensor "
                    + "permissions, and each managed app's real state).")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response get(@PathParam("deviceId") String deviceId) {
        Device device = device(deviceId);
        if (device == null) return Response.PERMISSION_DENIED();
        return Response.OK(view(device));
    }

    @ApiOperation(value = "Set a device's own app-permission rules",
            notes = "Body: { apps: { \"<package>\": { all?: grant|deny|prompt, permissions?: { <group>: grant|deny|prompt }, "
                    + "special?: [<access>] } } }. Replaces the device's rules (an empty object removes them: the device "
                    + "follows its policy again). The device re-applies on its next check-in; it is woken now.")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response put(@PathParam("deviceId") String deviceId, JsonNode body) {
        if (!SecurityContext.get().hasPermission("edit_devices")) {
            logger.warn("Permission denied: device app permissions require edit_devices (user {})",
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        Device device = device(deviceId);
        if (device == null) return Response.PERMISSION_DENIED();
        JsonNode apps = body == null ? null : body.get("apps");
        if (apps != null && !apps.isNull() && !apps.isObject()) return Response.ERROR("error.agent.command.invalid");
        String stored = apps == null || apps.isNull() ? null : AppPermissionRules.toJson(AppPermissionRules.parseMap(apps.toString()));
        commandDAO.setDevicePermissionOverride(device.getId(), stored);
        // The changed revision makes the next check-in queue a config.apply; wake the device so that is now.
        wakeHub.wake(deviceId, "commands");
        logger.info("App-permission rules of device {} {} by {}", deviceId, stored == null ? "removed" : "set",
                SecurityContext.get().getCurrentUserName());
        return Response.OK(view(device));
    }

    private Map<String, Object> view(Device device) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("supported", AgentCapabilityTokens.flatten(commandDAO.getDeviceCapabilities(device.getNumber())).contains(CAPABILITY));
        DesiredConfig policy = null;
        DesiredConfig effective = null;
        try {
            policy = configReconciler.policyDocument(device);
            effective = configReconciler.currentDocument(device);
        } catch (Exception e) {
            logger.warn("Could not build the permission document of {}: {}", device.getNumber(), e.getMessage());
        }
        out.put("policy", policy == null ? null : policy.getPermissions());
        out.put("effective", effective == null ? null : effective.getPermissions());
        out.put("override", AppPermissionRules.parseMap(commandDAO.getDevicePermissionOverride(device.getId())));
        JsonNode report = null;
        try {
            String json = commandDAO.getDevicePermissionReport(device.getId());
            report = json == null ? null : JSON.readTree(json);
        } catch (Exception e) {
            logger.warn("Unreadable permission report of {}: {}", device.getNumber(), e.getMessage());
        }
        out.put("report", report);
        out.put("reportAt", report == null ? null : commandDAO.getDevicePermissionReportAt(device.getId()));
        return out;
    }
}
