package com.hmdm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.mapper.AppGroupMapper;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.resource.support.ConfigAppInstaller;
import com.hmdm.rest.resource.support.PolicyApps;
import com.hmdm.security.SecurityContext;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * App groups ("Grupos de aplicaciones"): a named set of library apps that policies use as a whole — the apps are
 * installed and allowed on the policy's phones, and shown in the kiosk only if the policy says so for that group.
 * Changing a group re-sends installs to every policy that uses it (the policy documents follow on their own).
 */
@Singleton
@Path("/private/app-groups")
@Api(tags = {"App groups"})
public class AppGroupResource {

    private static final Logger logger = LoggerFactory.getLogger(AppGroupResource.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private AppGroupMapper mapper;
    private PolicyApps policyApps;
    private ConfigAppInstaller installer;
    private com.hmdm.persistence.AgentCommandDAO commandDAO;
    private com.hmdm.notification.AgentWakeHub wakeHub;

    /** A constructor required by Swagger. */
    public AppGroupResource() {
    }

    @Inject
    public AppGroupResource(AppGroupMapper mapper, PolicyApps policyApps, ConfigAppInstaller installer,
                            com.hmdm.persistence.AgentCommandDAO commandDAO, com.hmdm.notification.AgentWakeHub wakeHub) {
        this.commandDAO = commandDAO;
        this.wakeHub = wakeHub;
        this.mapper = mapper;
        this.policyApps = policyApps;
        this.installer = installer;
    }

    public static class Body {
        public String name;
        public String description;
        public List<Integer> appIds;
        /** Device folders (and their sub-folders) that get the group directly; null = leave as they are. */
        public List<Ref> folders;
        /** Policies that use the group; null = leave as they are. */
        public List<Ref> policies;
    }

    public static class Ref {
        public Integer id;
        /** The group's apps also show in the kiosk there. */
        public boolean kiosk;
    }

    @ApiOperation(value = "List app groups", notes = "Each with its apps (name, package, version) and how many policies use it.")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response list() {
        Optional<Integer> c = SecurityContext.get().getCurrentCustomerId();
        if (!c.isPresent()) return Response.PERMISSION_DENIED();
        List<Map<String, Object>> groups = mapper.list(c.get());
        List<Map<String, Object>> policies = mapper.policies(c.get());
        Set<Integer> all = new LinkedHashSet<>();
        for (Map<String, Object> g : groups) all.addAll(PolicyApps.appIds(g.get("appids")));
        Map<Integer, Application> byId = new HashMap<>();
        if (!all.isEmpty()) for (Application a : mapper.latestApps(c.get(), new ArrayList<>(all))) byId.put(a.getId(), a);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> g : groups) {
            int id = ((Number) g.get("id")).intValue();
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", id);
            v.put("name", g.get("name"));
            v.put("description", g.get("description"));
            List<Map<String, Object>> apps = new ArrayList<>();
            for (Integer appId : PolicyApps.appIds(g.get("appids"))) {
                Application a = byId.get(appId);
                if (a == null) continue; // deleted from the library
                Map<String, Object> av = new LinkedHashMap<>();
                av.put("id", a.getId());
                av.put("name", a.getName());
                av.put("pkg", a.getPkg());
                av.put("version", a.getVersion());
                av.put("installable", (a.getUrl() != null && a.getUrl().startsWith("http")) || (a.getParts() != null && !a.getParts().isEmpty()));
                apps.add(av);
            }
            v.put("apps", apps);
            List<Map<String, Object>> folders = new ArrayList<>();
            for (PolicyApps.FolderRef f : PolicyApps.folderRefs(g.get("folders"))) {
                Map<String, Object> fv = new LinkedHashMap<>();
                fv.put("id", f.id);
                fv.put("kiosk", f.kiosk);
                folders.add(fv);
            }
            v.put("folders", folders);
            List<Map<String, Object>> used = new ArrayList<>();
            for (Map<String, Object> p : policies) {
                com.hmdm.util.DcPolicy dc = com.hmdm.util.DcPolicy.parse(p.get("dcpolicy") == null ? null : String.valueOf(p.get("dcpolicy")));
                if (dc.getAppGroups() == null) continue;
                for (com.hmdm.util.DcPolicy.AppGroupRef r : dc.getAppGroups()) {
                    if (r.getId() != null && r.getId() == id) {
                        Map<String, Object> pv = new LinkedHashMap<>();
                        pv.put("id", p.get("id"));
                        pv.put("name", p.get("name"));
                        pv.put("kiosk", Boolean.TRUE.equals(r.getKiosk()));
                        used.add(pv);
                    }
                }
            }
            v.put("policyRefs", used);
            v.put("policies", used.size());
            out.add(v);
        }
        return Response.OK(out);
    }

    @ApiOperation(value = "Create an app group")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(Body b) {
        Optional<Integer> c = editor();
        if (!c.isPresent()) return Response.PERMISSION_DENIED();
        String err = validate(b);
        if (err != null) return Response.ERROR(err);
        Map<String, Object> g = new HashMap<>();
        g.put("customerId", c.get());
        g.put("name", b.name.trim());
        g.put("description", blankToNull(b.description));
        g.put("appIds", ids(b.appIds));
        g.put("updatedAt", System.currentTimeMillis());
        mapper.insert(g);
        int id = ((Number) g.get("id")).intValue();
        Map<String, Object> out = assign(c.get(), id, b);
        out.put("id", id);
        return Response.OK(out);
    }

    @ApiOperation(value = "Update an app group", notes = "Policies using it install the added apps right away.")
    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response update(@PathParam("id") int id, Body b) {
        Optional<Integer> c = editor();
        if (!c.isPresent()) return Response.PERMISSION_DENIED();
        String err = validate(b);
        if (err != null) return Response.ERROR(err);
        if (mapper.update(c.get(), id, b.name.trim(), blankToNull(b.description), ids(b.appIds), System.currentTimeMillis()) == 0) {
            return Response.ERROR("El grupo no existe.");
        }
        return Response.OK(assign(c.get(), id, b));
    }

    @ApiOperation(value = "Delete an app group", notes = "Refused while a policy uses it.")
    @DELETE
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response delete(@PathParam("id") int id) {
        Optional<Integer> c = editor();
        if (!c.isPresent()) return Response.PERMISSION_DENIED();
        int used = policyApps.policiesUsing(c.get(), Collections.singleton(id)).size()
                + policyApps.foldersUsing(c.get(), Collections.singleton(id)).size();
        if (used > 0) {
            return Response.ERROR("Está asignado a " + used + (used == 1 ? " política o carpeta" : " políticas o carpetas")
                    + ": quita la asignación antes de eliminarlo.");
        }
        return mapper.delete(c.get(), id) > 0 ? Response.OK() : Response.ERROR("El grupo no existe.");
    }

    /**
     * Store where the group is used (folders, policies — each only when given) and bring the affected phones up to
     * date: installs for what they now lack, and a wake-up so they re-apply their policy (allowed apps, kiosk).
     */
    private Map<String, Object> assign(int customerId, int id, Body b) {
        Set<Integer> folders = new java.util.HashSet<>(policyApps.foldersUsing(customerId, Collections.singleton(id)));
        Set<Integer> policies = new java.util.HashSet<>(policyApps.policiesUsing(customerId, Collections.singleton(id)));
        if (b.folders != null) {
            List<Map<String, Object>> keep = new ArrayList<>();
            Set<Integer> seen = new java.util.HashSet<>();
            for (Ref r : b.folders) {
                if (r == null || r.id == null || !seen.add(r.id) || commandDAO.findGroup(customerId, r.id) == null) continue;
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("id", r.id);
                if (r.kiosk) f.put("kiosk", true);
                keep.add(f);
            }
            try {
                mapper.updateFolders(customerId, id, JSON.writeValueAsString(keep), System.currentTimeMillis());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            folders.addAll(seen);
        }
        if (b.policies != null) {
            Map<Integer, Boolean> want = new HashMap<>();
            for (Ref r : b.policies) if (r != null && r.id != null) want.put(r.id, r.kiosk);
            for (Map<String, Object> p : mapper.policies(customerId)) {
                int pid = ((Number) p.get("id")).intValue();
                String before = p.get("dcpolicy") == null ? null : String.valueOf(p.get("dcpolicy"));
                com.hmdm.util.DcPolicy dc = com.hmdm.util.DcPolicy.parse(before);
                List<com.hmdm.util.DcPolicy.AppGroupRef> refs = new ArrayList<>();
                if (dc.getAppGroups() != null) for (com.hmdm.util.DcPolicy.AppGroupRef r : dc.getAppGroups()) if (r.getId() == null || r.getId() != id) refs.add(r);
                if (want.containsKey(pid)) {
                    com.hmdm.util.DcPolicy.AppGroupRef r = new com.hmdm.util.DcPolicy.AppGroupRef();
                    r.setId(id);
                    r.setKiosk(Boolean.TRUE.equals(want.get(pid)) ? Boolean.TRUE : null);
                    refs.add(r);
                }
                dc.setAppGroups(refs.isEmpty() ? null : refs);
                String after;
                try {
                    after = com.hmdm.util.DcPolicy.normalize(JSON.writeValueAsString(dc));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                if (!java.util.Objects.equals(com.hmdm.util.DcPolicy.normalize(before), after)) {
                    mapper.updatePolicyDc(customerId, pid, after);
                    policies.add(pid);
                }
            }
        }
        int queued = 0;
        for (Integer p : policies) {
            queued += installer.enqueueForConfiguration(p);
            for (String number : commandDAO.listDeviceNumbersByConfigurationId(p)) wakeHub.wake(number, "commands");
        }
        queued += installer.enqueueForFolders(customerId, folders);
        logger.info("App group {} saved: {} policy(ies), {} folder(s) touched, {} install(s) queued", id, policies.size(), folders.size(), queued);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("policies", policyApps.policiesUsing(customerId, Collections.singleton(id)).size());
        out.put("folders", policyApps.foldersUsing(customerId, Collections.singleton(id)).size());
        out.put("queued", queued);
        return out;
    }

    private static String validate(Body b) {
        if (b == null || b.name == null || b.name.trim().isEmpty() || b.name.trim().length() > 100) return "El nombre es obligatorio (máximo 100 caracteres).";
        if (b.description != null && b.description.length() > 500) return "La descripción es muy larga.";
        if (b.appIds != null && b.appIds.size() > 500) return "Demasiadas apps en un grupo.";
        return null;
    }

    private static String ids(List<Integer> in) {
        Set<Integer> s = new LinkedHashSet<>();
        if (in != null) for (Integer i : in) if (i != null && i > 0) s.add(i);
        try {
            return JSON.writeValueAsString(s);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private static Optional<Integer> editor() {
        if (!SecurityContext.get().hasPermission("configurations")) return Optional.empty();
        return SecurityContext.get().getCurrentCustomerId();
    }
}
