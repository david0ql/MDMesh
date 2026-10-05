package com.hmdm.rest.resource;

import com.hmdm.persistence.mapper.DcVersionMapper;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Library versions with a name the customer chose ("SKULL 9.2.44 · Disay"), and the policies that use each one — so
 * builds that share a version name or code can be told apart.
 */
@Singleton
@Path("/private/dc/versions")
@Api(tags = {"DallyControl version labels"})
public class DcVersionResource {
    private DcVersionMapper mapper;
    private com.hmdm.notification.PushService pushService;
    private com.hmdm.rest.resource.support.ConfigAppInstaller installer;

    public DcVersionResource() {
    }

    @Inject
    public DcVersionResource(DcVersionMapper mapper, com.hmdm.notification.PushService pushService,
                             com.hmdm.rest.resource.support.ConfigAppInstaller installer) {
        this.mapper = mapper;
        this.pushService = pushService;
        this.installer = installer;
    }

    public static class LabelBody {
        public String label;
    }

    private static Optional<Integer> customer() {
        return SecurityContext.get().getCurrentCustomerId();
    }

    @ApiOperation(value = "An app's versions with their names and the policies using each")
    @GET
    @Path("/app/{applicationId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response versions(@PathParam("applicationId") int applicationId) {
        Optional<Integer> c = customer();
        if (!c.isPresent()) return Response.PERMISSION_DENIED();
        return Response.OK(members(c.get(), applicationId));
    }

    /** An app's versions, newest first, each with its name and the policies that use it. */
    private List<Map<String, Object>> members(int customerId, int applicationId) {
        List<Map<String, Object>> policies = mapper.policies(customerId, applicationId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> v : mapper.versions(customerId, applicationId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            Object id = v.get("id");
            m.put("id", id);
            m.put("version", v.get("version"));
            m.put("versionCode", v.get("versioncode"));
            m.put("url", v.get("url"));
            m.put("parts", v.get("parts"));
            m.put("label", v.get("label"));
            List<Map<String, Object>> used = new ArrayList<>();
            for (Map<String, Object> p : policies) {
                if (String.valueOf(p.get("versionid")).equals(String.valueOf(id))) {
                    Map<String, Object> pm = new LinkedHashMap<>();
                    pm.put("id", p.get("id"));
                    pm.put("name", p.get("name"));
                    used.add(pm);
                }
            }
            m.put("policies", used);
            out.add(m);
        }
        return out;
    }

    @ApiOperation(value = "Groups of builds: every app with several versions (or named ones), with its builds and the policies using each")
    @GET
    @Path("/groups")
    @Produces(MediaType.APPLICATION_JSON)
    public Response groups() {
        Optional<Integer> c = customer();
        if (!c.isPresent()) return Response.PERMISSION_DENIED();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Integer appId : mapper.groupApps(c.get())) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("applicationId", appId);
            g.put("members", members(c.get(), appId));
            out.add(g);
        }
        return Response.OK(out);
    }

    public static class AssignBody {
        public List<Integer> configurationIds;
    }

    @ApiOperation(value = "Make exactly these policies use this build of its group (others using it drop the app)")
    @PUT
    @Path("/{versionId}/policies")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response assign(@PathParam("versionId") int versionId, AssignBody body) {
        Optional<Integer> c = customer();
        if (!c.isPresent() || !SecurityContext.get().hasPermission("configurations")) return Response.PERMISSION_DENIED();
        Map<String, Object> v = mapper.versionOf(c.get(), versionId);
        if (v == null) return Response.ERROR("La versión no existe.");
        int appId = ((Number) v.get("applicationid")).intValue();
        int code = v.get("versioncode") == null ? 0 : ((Number) v.get("versioncode")).intValue();
        java.util.Set<Integer> wanted = new java.util.LinkedHashSet<>();
        if (body != null && body.configurationIds != null) wanted.addAll(body.configurationIds);
        for (Integer cfg : wanted) {
            if (cfg == null || mapper.ownsConfiguration(c.get(), cfg) == 0) return Response.ERROR("La política no existe.");
        }
        // Below the group's newest build, the phones that have a newer one reinstall this one.
        boolean older = code < mapper.topCode(appId);
        java.util.Set<Integer> changed = new java.util.LinkedHashSet<>();
        for (Integer cfg : wanted) {
            if (mapper.pointPolicy(cfg, appId, versionId) == 0) mapper.addToPolicy(cfg, appId, versionId);
            if (older) mapper.allowDowngrade(cfg, appId); else mapper.forbidDowngrade(cfg, appId);
            changed.add(cfg);
        }
        int removed = 0;
        for (Map<String, Object> p : mapper.policies(c.get(), appId)) {
            int cfg = ((Number) p.get("id")).intValue();
            if (String.valueOf(p.get("versionid")).equals(String.valueOf(versionId)) && !wanted.contains(cfg)) {
                removed += mapper.removeFromPolicy(cfg, appId, versionId);
                mapper.forbidDowngrade(cfg, appId);
                changed.add(cfg);
            }
        }
        int queued = 0;
        for (Integer cfg : changed) {
            pushService.notifyDevicesOnUpdate(cfg);
            queued += installer.enqueueForConfiguration(cfg);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("assigned", wanted.size());
        out.put("removed", removed);
        out.put("queued", queued);
        return Response.OK(out);
    }

    @ApiOperation(value = "Every version name of the customer: {versionId: label}")
    @GET
    @Path("/labels")
    @Produces(MediaType.APPLICATION_JSON)
    public Response labels() {
        Optional<Integer> c = customer();
        if (!c.isPresent()) return Response.PERMISSION_DENIED();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> l : mapper.labels(c.get())) out.put(String.valueOf(l.get("versionid")), l.get("label"));
        return Response.OK(out);
    }

    @ApiOperation(value = "Name a version (empty removes the name)")
    @PUT
    @Path("/{versionId}/label")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response setLabel(@PathParam("versionId") int versionId, LabelBody body) {
        Optional<Integer> c = customer();
        if (!c.isPresent() || !SecurityContext.get().hasPermission("edit_applications")) return Response.PERMISSION_DENIED();
        if (mapper.owns(c.get(), versionId) == 0) return Response.ERROR("La versión no existe.");
        String label = body == null || body.label == null ? "" : body.label.trim();
        if (label.length() > 80) return Response.ERROR("El nombre puede tener hasta 80 caracteres.");
        if (label.isEmpty()) mapper.clearLabel(versionId); else mapper.setLabel(versionId, label);
        return Response.OK();
    }

    public static class DowngradeBody {
        public boolean allow;
    }

    @ApiOperation(value = "Apps this policy may take back to an older version (the phone reinstalls them)")
    @GET
    @Path("/policy/{configurationId}/downgrades")
    @Produces(MediaType.APPLICATION_JSON)
    public Response downgrades(@PathParam("configurationId") int configurationId) {
        Optional<Integer> c = customer();
        if (!c.isPresent()) return Response.PERMISSION_DENIED();
        return Response.OK(mapper.downgrades(c.get(), configurationId));
    }

    @ApiOperation(value = "Allow or forbid a policy to take an app back to an older version")
    @PUT
    @Path("/policy/{configurationId}/downgrade/{applicationId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response setDowngrade(@PathParam("configurationId") int configurationId, @PathParam("applicationId") int applicationId,
                                 DowngradeBody body) {
        Optional<Integer> c = customer();
        if (!c.isPresent() || !SecurityContext.get().hasPermission("configurations")) return Response.PERMISSION_DENIED();
        if (mapper.ownsConfiguration(c.get(), configurationId) == 0) return Response.ERROR("La política no existe.");
        if (body != null && body.allow) mapper.allowDowngrade(configurationId, applicationId);
        else mapper.forbidDowngrade(configurationId, applicationId);
        return Response.OK();
    }
}
