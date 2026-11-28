package com.hmdm.rest.resource;

import com.hmdm.notification.AgentWakeHub;
import com.hmdm.notification.SupportHub;
import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.SupportSessionDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.domain.DeviceState;
import com.hmdm.persistence.domain.SupportSession;
import com.hmdm.persistence.domain.User;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.resource.support.UserScope;
import com.hmdm.security.SecurityContext;
import com.hmdm.util.AgentCapabilityTokens;
import com.hmdm.util.RemoteSessions;
import com.hmdm.util.SupportSessions;
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
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Remote support (ADR 0012): start and end a support session on a device, what the device offers, and the log of
 * past sessions. The session itself (screen, touch, voice, chat, files) runs over the WebSocket pair that
 * {@link SupportHub} relays; this resource only opens and audits it.
 *
 * <p>Folder administrators reach only their own devices: the per-device paths are checked by UserScopeFilter, the
 * session list is filtered here.</p>
 */
@Singleton
@Path("/private/support/v1")
@Api(tags = {"Soporte remoto: sesiones de pantalla, voz y chat"})
public class SupportResource {

    private static final Logger logger = LoggerFactory.getLogger(SupportResource.class);

    private SupportHub hub;
    private AgentWakeHub wakeHub;
    private SupportSessionDAO dao;
    private AgentCommandDAO commandDAO;
    private UnsecureDAO unsecureDAO;
    private UserScope scope;

    /** A constructor required by Swagger. */
    public SupportResource() {
    }

    @Inject
    public SupportResource(SupportHub hub, AgentWakeHub wakeHub, SupportSessionDAO dao, AgentCommandDAO commandDAO,
                           UnsecureDAO unsecureDAO, UserScope scope) {
        this.hub = hub;
        this.wakeHub = wakeHub;
        this.dao = dao;
        this.commandDAO = commandDAO;
        this.unsecureDAO = unsecureDAO;
        this.scope = scope;
    }

    /** The device, when it belongs to the signed-in user's customer. */
    private Device ownDevice(String deviceId) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) return null;
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        return device != null && device.getCustomerId() == customerId.get() ? device : null;
    }

    private static boolean canOperate() {
        return SecurityContext.get().hasPermission("edit_devices");
    }

    // =================================================================================================================
    @ApiOperation(value = "Remote support status", notes = "What the device offers: session channel, screen readiness "
            + "(ready / needsAuthorization / unsupported / disabled), voice, the legacy VNC tier, and the open session if any.")
    @GET
    @Path("/devices/{deviceId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response status(@PathParam("deviceId") String deviceId) {
        Device device = ownDevice(deviceId);
        if (device == null) return Response.PERMISSION_DENIED();
        String caps = commandDAO.getDeviceCapabilities(deviceId);
        DeviceState state = commandDAO.getState(deviceId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("support", SupportSessions.support(caps));
        out.put("readiness", SupportSessions.readiness(caps));
        out.put("vncTier", RemoteSessions.tier(caps));
        out.put("online", wakeHub.isOnline(deviceId));
        out.put("powerMode", state == null ? null : state.getPowerMode());
        out.put("locked", state == null ? null : state.getLocked());
        out.put("lastSeen", device.getLastUpdate());
        SupportHub.Live live = hub.forDevice(deviceId);
        if (live != null) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("sessionKey", live.getKey());
            l.put("id", live.getDbId());
            l.put("viewOnly", live.isViewOnly());
            l.put("deviceConnected", live.isDeviceConnected());
            out.put("live", l);
        }
        return Response.OK(out);
    }

    public static class StartBody {
        public Boolean viewOnly;
    }

    @ApiOperation(value = "Start support session", notes = "Opens a session on the device and returns the one-time key "
            + "and token for the console's socket (/agent/ws/support/console/{sessionKey}). A connected agent joins at "
            + "once (live wake); otherwise at its next check-in. Body: { viewOnly }.")
    @POST
    @Path("/devices/{deviceId}/start")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response start(@PathParam("deviceId") String deviceId, StartBody body) {
        if (!canOperate()) {
            logger.warn("Permission denied: start support session requires edit_devices (user {})",
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        Device device = ownDevice(deviceId);
        if (device == null) return Response.PERMISSION_DENIED();
        String caps = commandDAO.getDeviceCapabilities(deviceId);
        Set<String> tokens = AgentCapabilityTokens.flatten(caps);
        if (!tokens.contains(SupportSessions.CAPABILITY)) {
            return Response.ERROR("error.agent.remote.unsupported");
        }
        boolean viewOnly = body != null && Boolean.TRUE.equals(body.viewOnly);
        Optional<User> user = SecurityContext.get().getCurrentUser();
        boolean online = wakeHub.isOnline(deviceId);
        String engine = "ready".equals(SupportSessions.readiness(caps)) ? "builtin" : "none";
        SupportHub.Created created = hub.create(device.getCustomerId(), deviceId,
                user.map(User::getId).orElse(null), user.map(User::getLogin).orElse(null), viewOnly, engine);

        // The wake reaches a connected agent in milliseconds. The queued command is the fallback for a phone whose
        // socket is down (battery saver while locked): it joins at its next check-in.
        AgentCommand command = new AgentCommand();
        command.setDeviceNumber(deviceId);
        command.setType(SupportSessions.COMMAND_START);
        command.setPayload(SupportSessions.commandPayload(created.live.getKey()));
        command.setRequiresCapability(SupportSessions.CAPABILITY);
        command.setStatus("pending");
        command.setCreatedAt(System.currentTimeMillis());
        commandDAO.insert(command);
        if (!online) wakeHub.wake(deviceId, "commands");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", created.live.getDbId());
        out.put("sessionKey", created.live.getKey());
        out.put("token", created.token);
        out.put("socket", "/agent/ws/support/console/" + created.live.getKey());
        out.put("viewOnly", viewOnly);
        out.put("online", online);
        out.put("commandId", command.getId());
        out.put("support", SupportSessions.support(caps));
        return Response.OK(out);
    }

    @ApiOperation(value = "End support session", notes = "Ends the device's open session, if any.")
    @POST
    @Path("/devices/{deviceId}/stop")
    @Produces(MediaType.APPLICATION_JSON)
    public Response stop(@PathParam("deviceId") String deviceId) {
        if (!canOperate()) return Response.PERMISSION_DENIED();
        Device device = ownDevice(deviceId);
        if (device == null) return Response.PERMISSION_DENIED();
        SupportHub.Live live = hub.forDevice(deviceId);
        if (live != null) hub.end(live, SupportSessions.ROLE_CONSOLE);
        return Response.OK(java.util.Collections.singletonMap("ended", live != null));
    }

    // =================================================================================================================
    @ApiOperation(value = "Support sessions of a device", notes = "Newest first: who, when, how long, what was used.")
    @GET
    @Path("/devices/{deviceId}/sessions")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deviceSessions(@PathParam("deviceId") String deviceId, @QueryParam("limit") Integer limit) {
        Device device = ownDevice(deviceId);
        if (device == null) return Response.PERMISSION_DENIED();
        List<SupportSession> rows = dao.list(device.getCustomerId(), deviceId, null, null, null, limit == null ? 100 : limit);
        return Response.OK(rows(rows));
    }

    @ApiOperation(value = "Support session log", notes = "Every session of the devices the user reaches, newest first. "
            + "from / to: epoch millis of the start.")
    @GET
    @Path("/sessions")
    @Produces(MediaType.APPLICATION_JSON)
    public Response sessions(@QueryParam("from") Long from, @QueryParam("to") Long to, @QueryParam("limit") Integer limit) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) return Response.PERMISSION_DENIED();
        Set<String> visible = scope.deviceNumbers();
        List<SupportSession> rows = dao.list(customerId.get(), null, visible == null ? null : new ArrayList<>(visible),
                from, to, limit == null ? 200 : limit);
        return Response.OK(rows(rows));
    }

    @ApiOperation(value = "Events of a support session", notes = "Chat lines, files, calls and actions, oldest first.")
    @GET
    @Path("/sessions/{id}/events")
    @Produces(MediaType.APPLICATION_JSON)
    public Response events(@PathParam("id") long id) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        SupportSession s = dao.get(id);
        if (!customerId.isPresent() || s == null || !customerId.get().equals(s.getCustomerId())
                || !scope.canDeviceNumber(customerId.get(), s.getDeviceNumber())) {
            return Response.PERMISSION_DENIED();
        }
        return Response.OK(dao.events(id));
    }

    private List<Map<String, Object>> rows(List<SupportSession> sessions) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, String> names = new java.util.HashMap<>();
        for (SupportSession s : sessions) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", s.getId());
            r.put("deviceNumber", s.getDeviceNumber());
            r.put("deviceName", names.computeIfAbsent(s.getDeviceNumber(), n -> {
                Device d = unsecureDAO.getDeviceByNumber(n);
                return d == null || d.getDescription() == null ? "" : d.getDescription();
            }));
            r.put("user", s.getUserLogin());
            r.put("viewOnly", s.getViewOnly());
            r.put("engine", s.getEngine());
            r.put("startedAt", s.getStartedAt());
            r.put("connectedAt", s.getConnectedAt());
            r.put("endedAt", s.getEndedAt());
            r.put("endReason", s.getEndReason());
            r.put("durationSec", duration(s));
            r.put("chatCount", s.getChatCount());
            r.put("fileCount", s.getFileCount());
            r.put("voiceSeconds", s.getVoiceSeconds());
            r.put("inputCount", s.getInputCount());
            r.put("live", s.getEndedAt() == null);
            out.add(r);
        }
        return out;
    }

    /** Seconds the device was in the session (from when it joined), null while open or when it never joined. */
    static Long duration(SupportSession s) {
        if (s.getEndedAt() == null || s.getConnectedAt() == null) return null;
        return Math.max(0L, (s.getEndedAt() - s.getConnectedAt()) / 1000L);
    }
}
