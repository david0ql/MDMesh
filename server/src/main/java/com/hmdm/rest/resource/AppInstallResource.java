package com.hmdm.rest.resource;

import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.mapper.AppInstallStatusMapper;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.resource.support.InstallRetrier;
import com.hmdm.rest.resource.support.UserScope;
import com.hmdm.security.SecurityContext;
import com.hmdm.util.AgentCapabilityTokens;
import com.hmdm.util.InstallFleetStatus;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * "Instalación en la flota": where the installation of an app stands on each phone that was ordered to install it
 * (installed, on its way, held back and why, failed and why), and "Reintentar". Reads the install commands; a retry
 * adds a new one.
 */
@Singleton
@Path("/private/agent/v1/apps/install")
@Api(tags = {"Agent v1 app install status"})
public class AppInstallResource {
    private static final Logger logger = LoggerFactory.getLogger(AppInstallResource.class);
    private static final Pattern PACKAGE = Pattern.compile("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$");
    /** How far back install commands are read. */
    private static final long HISTORY_MS = 90L * 24 * 3600_000L;

    private AppInstallStatusMapper mapper;
    private AgentCommandDAO commandDAO;
    private UnsecureDAO unsecureDAO;
    private InstallRetrier retrier;
    private UserScope scope;

    /** A constructor required by Swagger. */
    public AppInstallResource() {
    }

    @Inject
    public AppInstallResource(AppInstallStatusMapper mapper, AgentCommandDAO commandDAO, UnsecureDAO unsecureDAO,
                              InstallRetrier retrier, UserScope scope) {
        this.mapper = mapper;
        this.commandDAO = commandDAO;
        this.unsecureDAO = unsecureDAO;
        this.retrier = retrier;
        this.scope = scope;
    }

    /** A device's row of the fleet view. */
    private static Map<String, Object> deviceRow(Map<String, Object> d, List<InstallRetrier.Row> group, Set<String> tokens) {
        InstallFleetStatus.Result r = InstallRetrier.classify(group, tokens);
        InstallRetrier.Row last = group.get(group.size() - 1);
        Map<String, Object> row = new LinkedHashMap<>(r.toMap());
        row.put("deviceId", d.get("id"));
        row.put("number", d.get("number"));
        row.put("description", d.get("description"));
        row.put("group", d.get("groupname"));
        row.put("lastSeen", d.get("lastupdate"));
        row.put("targetVersionCode", last.cmd.versionCode);
        row.put("installedVersion", d.get("installedversion"));
        row.put("deviceOwner", tokens.contains(com.hmdm.util.RolloutProgress.INSTALL_CAPABILITY));
        return row;
    }

    @ApiOperation(value = "Fleet install status of an app", notes = "For every device that was ordered to install the "
            + "package (?pkg=): installed / pending / installing / held / failed, with the reason in Spanish, the failed "
            + "attempts and the next automatic retry; plus the totals. Folder administrators see their folders only.")
    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response status(@QueryParam("pkg") String pkg) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) return Response.PERMISSION_DENIED();
        if (pkg == null || !PACKAGE.matcher(pkg.trim()).matches()) return Response.ERROR("error.agent.app.pkg.required");
        final String p = pkg.trim();
        long since = System.currentTimeMillis() - HISTORY_MS;
        List<InstallRetrier.Row> rows = new ArrayList<>();
        for (InstallRetrier.Row r : InstallRetrier.rows(mapper.commandsForPackage(customerId.get(), "%" + p + "%", since))) {
            if (p.equals(r.pkg)) rows.add(r);
        }
        Map<String, List<InstallRetrier.Row>> groups = InstallRetrier.byDeviceAndPackage(rows);
        Set<String> visible = scope.deviceNumbers();
        List<Map<String, Object>> devices = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String k : new String[]{"installed", "pending", "installing", "held", "failed"}) counts.put(k, 0);
        long targetVersionCode = 0;
        for (Map<String, Object> d : mapper.devices(customerId.get(), p)) {
            String number = (String) d.get("number");
            List<InstallRetrier.Row> group = groups.get(number + "|" + p);
            if (group == null || (visible != null && !visible.contains(number))) continue;
            Map<String, Object> row = deviceRow(d, group, AgentCapabilityTokens.flatten((String) d.get("agentcapabilities")));
            devices.add(row);
            counts.merge((String) row.get("state"), 1, Integer::sum);
            targetVersionCode = Math.max(targetVersionCode, ((Number) row.get("targetVersionCode")).longValue());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("packageName", p);
        out.put("targetVersionCode", targetVersionCode);
        out.put("total", devices.size());
        out.put("counts", counts);
        out.put("devices", devices);
        return Response.OK(out);
    }

    @ApiOperation(value = "Installs of a device that need attention", notes = "The device's installs that are held "
            + "(not Device Owner…) or failed, with the reason; an empty list when all is well.")
    @GET
    @Path("/device/{number}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deviceProblems(@PathParam("number") String number) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) return Response.PERMISSION_DENIED();
        Device device = unsecureDAO.getDeviceByNumber(number);
        if (device == null) return Response.DEVICE_NOT_FOUND_ERROR();
        if (device.getCustomerId() != customerId.get() || !scope.canDeviceNumber(customerId.get(), number)) {
            return Response.PERMISSION_DENIED();
        }
        Set<String> tokens = AgentCapabilityTokens.flatten(commandDAO.getDeviceCapabilities(number));
        List<Map<String, Object>> out = new ArrayList<>();
        List<InstallRetrier.Row> rows = InstallRetrier.rows(mapper.commandsForDevice(number, System.currentTimeMillis() - HISTORY_MS));
        for (List<InstallRetrier.Row> group : InstallRetrier.byDeviceAndPackage(rows).values()) {
            InstallFleetStatus.Result r = InstallRetrier.classify(group, tokens);
            if (r.state != InstallFleetStatus.State.HELD && r.state != InstallFleetStatus.State.FAILED) continue;
            InstallRetrier.Row last = group.get(group.size() - 1);
            Map<String, Object> row = new LinkedHashMap<>(r.toMap());
            row.put("packageName", last.pkg);
            row.put("targetVersionCode", last.cmd.versionCode);
            out.add(row);
        }
        return Response.OK(out);
    }

    public static class RetryBody {
        public String packageName;
        /** Device numbers; empty or absent = every device where it failed. */
        public List<String> deviceNumbers;
    }

    @ApiOperation(value = "Retry failed installs", notes = "Queues the install of the package again on the given devices "
            + "(or on every one where it failed), clearing the phone's \"the system refused it\" memory when the agent can.")
    @POST
    @Path("/retry")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response retry(RetryBody body) {
        if (!SecurityContext.get().hasPermission("edit_devices") || !SecurityContext.get().hasPermission("manage_fleet")) {
            logger.warn("Permission denied: retrying installs requires edit_devices and manage_fleet (user {})",
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) return Response.PERMISSION_DENIED();
        if (body == null || body.packageName == null || !PACKAGE.matcher(body.packageName.trim()).matches()) {
            return Response.ERROR("error.agent.app.pkg.required");
        }
        final String p = body.packageName.trim();
        Set<String> wanted = body.deviceNumbers == null || body.deviceNumbers.isEmpty() ? null : new HashSet<>(body.deviceNumbers);
        Set<String> visible = scope.deviceNumbers();
        List<InstallRetrier.Row> rows = new ArrayList<>();
        for (InstallRetrier.Row r : InstallRetrier.rows(
                mapper.commandsForPackage(customerId.get(), "%" + p + "%", System.currentTimeMillis() - HISTORY_MS))) {
            if (p.equals(r.pkg)) rows.add(r);
        }
        int queued = 0;
        for (List<InstallRetrier.Row> group : InstallRetrier.byDeviceAndPackage(rows).values()) {
            InstallRetrier.Row last = group.get(group.size() - 1);
            if (wanted != null && !wanted.contains(last.deviceNumber)) continue;
            if (visible != null && !visible.contains(last.deviceNumber)) continue;
            InstallFleetStatus.Result r = InstallRetrier.classify(group, null);
            if (r.state != InstallFleetStatus.State.FAILED) continue;
            try {
                if (retrier.resend(last, true)) queued++;
            } catch (Exception e) {
                logger.warn("Could not retry the install of {} on {}: {}", p, last.deviceNumber, e.toString());
            }
        }
        logger.info("Install of {} retried on {} device(s) by {}", p, queued, SecurityContext.get().getCurrentUserName());
        return Response.OK(java.util.Collections.singletonMap("queued", queued));
    }
}
