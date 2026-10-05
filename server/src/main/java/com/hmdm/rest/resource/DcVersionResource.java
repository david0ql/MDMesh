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

    public DcVersionResource() {
    }

    @Inject
    public DcVersionResource(DcVersionMapper mapper) {
        this.mapper = mapper;
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
        List<Map<String, Object>> policies = mapper.policies(c.get(), applicationId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> v : mapper.versions(c.get(), applicationId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            Object id = v.get("id");
            m.put("id", id);
            m.put("version", v.get("version"));
            m.put("versionCode", v.get("versioncode"));
            m.put("url", v.get("url"));
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
}
