package com.hmdm.rest.resource;

import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.mapper.AppInstallStatusMapper;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.resource.support.ConfigAppInstaller;
import com.hmdm.rest.resource.support.InstallRetrier;
import com.hmdm.rest.resource.support.UserScope;
import com.hmdm.security.SecurityContext;
import com.hmdm.util.AgentCapabilityTokens;
import com.hmdm.util.InstallFleetStatus;
import com.hmdm.util.RolloutProgress;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * "Instalación en la flota": where the installation of an app stands on EVERY phone its policy (app list or app
 * group) assigns it to — also the ones that have not received the order yet — plus the phones that got a manual
 * order; measured against the version the policy assigns. And "Reintentar". Reads the install commands; a retry
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
    private ConfigAppInstaller installer;

    /** A constructor required by Swagger. */
    public AppInstallResource() {
    }

    @Inject
    public AppInstallResource(AppInstallStatusMapper mapper, AgentCommandDAO commandDAO, UnsecureDAO unsecureDAO,
                              InstallRetrier retrier, UserScope scope, ConfigAppInstaller installer) {
        this.mapper = mapper;
        this.commandDAO = commandDAO;
        this.unsecureDAO = unsecureDAO;
        this.retrier = retrier;
        this.scope = scope;
        this.installer = installer;
    }

    /** One phone of the fleet view, with what a retry needs. */
    private static final class Entry {
        Map<String, Object> view;
        InstallFleetStatus.Result result;
        Device device;
        /** The policy assigns the app to this phone. */
        boolean assigned;
        /** The phone's install commands for the package, oldest first (empty when it never got one). */
        List<InstallRetrier.Row> commands;
    }

    /**
     * Every phone of the customer the current user reaches that the policy assigns [pkg] to, or that was ordered to
     * install it. The target version is the policy's; for a phone with only a manual order, that order's.
     */
    private List<Entry> entries(int customerId, String pkg) {
        long since = System.currentTimeMillis() - HISTORY_MS;
        List<InstallRetrier.Row> rows = new ArrayList<>();
        for (InstallRetrier.Row r : InstallRetrier.rows(mapper.commandsForPackage(customerId, "%" + pkg + "%", since))) {
            if (pkg.equals(r.pkg)) rows.add(r);
        }
        Map<String, List<InstallRetrier.Row>> groups = InstallRetrier.byDeviceAndPackage(rows);
        Set<String> visible = scope.deviceNumbers();
        Map<Integer, List<Application>> plainCache = new HashMap<>();
        List<Entry> out = new ArrayList<>();
        for (Map<String, Object> d : mapper.devices(customerId, pkg)) {
            String number = (String) d.get("number");
            if (visible != null && !visible.contains(number)) continue;
            Device device = new Device();
            device.setId(((Number) d.get("id")).intValue());
            device.setNumber(number);
            device.setCustomerId(customerId);
            Number cfg = (Number) d.get("configurationid");
            device.setConfigurationId(cfg == null ? null : cfg.intValue());
            ConfigAppInstaller.DesiredInstall desired = installer.desiredInstallFor(device, pkg, plainCache);
            List<InstallRetrier.Row> group = groups.get(number + "|" + pkg);
            if (desired == null && group == null) continue;

            Entry e = new Entry();
            e.device = device;
            e.assigned = desired != null;
            e.commands = group == null ? Collections.<InstallRetrier.Row>emptyList() : group;
            Set<String> tokens = AgentCapabilityTokens.flatten((String) d.get("agentcapabilities"));
            long target;
            String targetVersion = null;
            if (desired != null) {
                List<InstallFleetStatus.Cmd> cmds = new ArrayList<>(e.commands.size());
                for (InstallRetrier.Row r : e.commands) cmds.add(r.cmd);
                target = desired.versionCode;
                targetVersion = desired.version;
                e.result = InstallFleetStatus.forAssignment(target, cmds, tokens, RolloutProgress.INSTALL_CAPABILITY);
            } else {
                target = group.get(group.size() - 1).cmd.versionCode;
                e.result = InstallRetrier.classify(group, tokens);
            }
            Map<String, Object> row = new LinkedHashMap<>(e.result.toMap());
            row.put("deviceId", d.get("id"));
            row.put("number", number);
            row.put("description", d.get("description"));
            row.put("group", d.get("groupname"));
            row.put("lastSeen", d.get("lastupdate"));
            row.put("assigned", e.assigned);
            row.put("targetVersionCode", target);
            row.put("targetVersion", targetVersion);
            row.put("installedVersion", d.get("installedversion"));
            row.put("deviceOwner", tokens.contains(RolloutProgress.INSTALL_CAPABILITY));
            e.view = row;
            out.add(e);
        }
        return out;
    }

    @ApiOperation(value = "Fleet install status of an app", notes = "For every device the policy (app list or app group) "
            + "assigns the package to (?pkg=) — also those that have not received the order yet — and every device that "
            + "got a manual order: installed / pending / installing / held / failed against the version the policy assigns, "
            + "with the reason in Spanish, the failed attempts and the next automatic retry; plus the totals. Folder "
            + "administrators see their folders only.")
    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response status(@QueryParam("pkg") String pkg) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) return Response.PERMISSION_DENIED();
        if (pkg == null || !PACKAGE.matcher(pkg.trim()).matches()) return Response.ERROR("error.agent.app.pkg.required");
        final String p = pkg.trim();
        List<Map<String, Object>> devices = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String k : new String[]{"installed", "pending", "installing", "held", "failed"}) counts.put(k, 0);
        long assignedTarget = 0;
        long orderedTarget = 0;
        String targetVersion = null;
        int assigned = 0;
        for (Entry e : entries(customerId.get(), p)) {
            devices.add(e.view);
            counts.merge((String) e.view.get("state"), 1, Integer::sum);
            long t = ((Number) e.view.get("targetVersionCode")).longValue();
            if (e.assigned) {
                assigned++;
                if (t >= assignedTarget) {
                    assignedTarget = t;
                    targetVersion = (String) e.view.get("targetVersion");
                }
            } else {
                orderedTarget = Math.max(orderedTarget, t);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("packageName", p);
        // The version the policy assigns; only when no policy assigns the app, the newest one ordered by hand.
        out.put("targetVersionCode", assigned > 0 ? assignedTarget : orderedTarget);
        out.put("targetVersion", targetVersion);
        out.put("assigned", assigned);
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
        /** Device numbers; empty or absent = every device where it failed or was never sent. */
        public List<String> deviceNumbers;
    }

    @ApiOperation(value = "Retry installs", notes = "Queues the install of the package again on the given devices (or on "
            + "every one) where it failed — clearing the phone's \"the system refused it\" memory when the agent can — and "
            + "sends it where the policy assigns it but no order went out. An app no longer assigned is not installed again.")
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
        int queued = 0;
        for (Entry e : entries(customerId.get(), p)) {
            if (wanted != null && !wanted.contains(e.device.getNumber())) continue;
            try {
                if (e.result.state == InstallFleetStatus.State.FAILED && !e.commands.isEmpty()) {
                    if (retrier.resend(e.commands.get(e.commands.size() - 1), true)) queued++;
                } else if (e.assigned && "notSent".equals(e.result.reasonCode)) {
                    // Assigned by the policy but never ordered: send what the policy wants (as saving the policy would).
                    Device full = unsecureDAO.getDeviceByNumber(e.device.getNumber());
                    if (full != null && installer.enqueueConfigApps(full, p::equals) > 0) queued++;
                }
            } catch (Exception ex) {
                logger.warn("Could not retry the install of {} on {}: {}", p, e.device.getNumber(), ex.toString());
            }
        }
        logger.info("Install of {} retried on {} device(s) by {}", p, queued, SecurityContext.get().getCurrentUserName());
        return Response.OK(Collections.singletonMap("queued", queued));
    }
}
