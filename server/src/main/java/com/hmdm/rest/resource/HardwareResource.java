package com.hmdm.rest.resource;

import com.hmdm.notification.AgentWakeHub;
import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.Device;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.agent.DesiredConfig;
import com.hmdm.rest.resource.support.ConfigReconciler;
import com.hmdm.security.SecurityContext;
import com.hmdm.util.DcPolicy;
import com.hmdm.util.DesiredConfigBuilder;
import com.hmdm.util.HardwarePolicy;
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
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Hardware and ports of ONE device: what its policy asks, the per-device override on top of it, and what the agent
 * reported as really applied ("Restricciones aplicadas"). Folder administrators only reach their folders' devices
 * (the {@code /agent/v1/devices/{number}/…} rule of {@code UserScopeFilter}); changing the override needs
 * {@code edit_devices}.
 */
@Singleton
@Path("/private/agent/v1/devices/{deviceId}/hardware")
@Api(tags = {"Agent v1 hardware and ports"})
public class HardwareResource {
    private static final Logger logger = LoggerFactory.getLogger(HardwareResource.class);

    private AgentCommandDAO commandDAO;
    private UnsecureDAO unsecureDAO;
    private ConfigReconciler configReconciler;
    private AgentWakeHub wakeHub;

    /** A constructor required by Swagger. */
    public HardwareResource() {
    }

    @Inject
    public HardwareResource(AgentCommandDAO commandDAO, UnsecureDAO unsecureDAO, ConfigReconciler configReconciler,
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

    private HardwarePolicy.Status status(Device device) {
        Configuration cfg = device.getConfigurationId() == null ? null : unsecureDAO.getConfigurationById(device.getConfigurationId());
        DesiredConfig doc = configReconciler.currentDocument(device);
        AgentCommand last = commandDAO.findLatestOfType(device.getNumber(), DesiredConfigBuilder.COMMAND_TYPE);
        // Only a finished command carries the device's report.
        String detail = last != null && ("done".equals(last.getStatus()) || "failed".equals(last.getStatus())) ? last.getDetail() : null;
        HardwarePolicy.Status s = HardwarePolicy.status(
                cfg == null ? null : DcPolicy.parse(cfg.getDcPolicy()).getHardware(),
                HardwarePolicy.parseOverride(commandDAO.getDeviceHardwareOverride(device.getId())),
                doc, detail, cfg == null ? null : cfg.getRestrictions());
        if (s.reported && last != null) s.reportedAt = last.getCompletedAt();
        return s;
    }

    // =================================================================================================================
    @ApiOperation(value = "Hardware and ports of a device",
            notes = "Per rule: what the policy asks, the device override, what was sent and what the agent reported "
                    + "(applied / unsupported: why / failed: why / pending).")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response get(@PathParam("deviceId") String deviceId) {
        Device device = device(deviceId);
        if (device == null) return Response.PERMISSION_DENIED();
        return Response.OK(status(device));
    }

    // =================================================================================================================
    @ApiOperation(value = "Override hardware and ports on a device",
            notes = "Body: { rules: { <key>: \"allow\" | \"block\" | \"policy\" } }. Only the keys in the body change; "
                    + "\"policy\" removes that key's override. The override wins over the policy; the device is woken "
                    + "so it applies it now.")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response put(@PathParam("deviceId") String deviceId, Map<String, Object> body) {
        if (!SecurityContext.get().hasPermission("edit_devices")) return Response.PERMISSION_DENIED();
        Device device = device(deviceId);
        if (device == null) return Response.PERMISSION_DENIED();
        Object raw = body == null ? null : body.get("rules");
        if (!(raw instanceof Map)) return Response.ERROR("error.agent.command.invalid");

        Map<String, String> next = new TreeMap<String, String>(HardwarePolicy.parseOverride(commandDAO.getDeviceHardwareOverride(device.getId())));
        for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
            String key = String.valueOf(e.getKey());
            String value = e.getValue() == null ? "policy" : String.valueOf(e.getValue()).trim().toLowerCase(java.util.Locale.ROOT);
            HardwarePolicy.Rule rule = HardwarePolicy.rule(key);
            boolean known = HardwarePolicy.ALLOW.equals(value) || HardwarePolicy.BLOCK.equals(value) || "policy".equals(value);
            // Tethering has its own per-device override ("Compartir datos").
            if (rule == null || rule.kind == HardwarePolicy.Kind.DEVICE || !known) return Response.ERROR("error.agent.command.invalid");
            if ("policy".equals(value)) next.remove(key);
            else next.put(key, value);
        }
        commandDAO.setDeviceHardwareOverride(device.getId(), next.isEmpty() ? null : HardwarePolicy.toJson(next));
        // The changed revision makes the next check-in queue a config.apply; wake the phone so that is now.
        wakeHub.wake(deviceId, "commands");
        logger.info("Hardware override of device {} set to {} by {}", deviceId, next,
                SecurityContext.get().getCurrentUser().map(u -> u.getLogin()).orElse("?"));
        return Response.OK(status(device));
    }
}
