/*
 * DallyControl — scheduled fleet reports (new module, not part of upstream Headwind).
 */
package com.hmdm.rest.resource;

import com.hmdm.persistence.domain.ReportSchedule;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.resource.support.UserScope;
import com.hmdm.security.SecurityContext;
import com.hmdm.task.ReportScheduleService;
import com.hmdm.task.ReportScheduler;
import com.hmdm.util.ReportScheduleCalc;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reports sent by e-mail on a schedule (daily / weekly / monthly): create, edit, pause, delete and "send now".
 * Needs manage_fleet. A folder administrator handles only schedules of their own folders (and must pick one), so a
 * scheduled e-mail never carries devices its author cannot see.
 */
@Singleton
@Path("/private/reports/v1/schedules")
@Api(tags = {"Reportes: envíos programados"})
public class ReportScheduleResource {
    private static final Logger logger = LoggerFactory.getLogger(ReportScheduleResource.class);

    private ReportScheduleService service;
    private UserScope scope;

    /** A constructor required by Swagger. */
    public ReportScheduleResource() {
    }

    @Inject
    public ReportScheduleResource(ReportScheduleService service, UserScope scope) {
        this.service = service;
        this.scope = scope;
    }

    @ApiOperation(value = "Envíos programados", notes = "Los envíos, si el servidor tiene SMTP y los reportes programables.")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response list() {
        Optional<Integer> c = editor();
        if (!c.isPresent()) return Response.PERMISSION_DENIED();
        Set<Integer> mine = scope.groups();
        List<ReportSchedule> out = new ArrayList<>();
        for (ReportSchedule s : service.list(c.get())) if (visible(s, mine)) out.add(s);
        List<Map<String, Object>> reports = new ArrayList<>();
        for (Map.Entry<String, String> e : ReportScheduleService.REPORTS.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getKey());
            m.put("label", e.getValue());
            reports.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("smtpConfigured", service.smtpConfigured());
        body.put("reports", reports);
        body.put("schedules", out);
        return Response.OK(body);
    }

    @ApiOperation(value = "Crear envío programado")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(ReportSchedule body) {
        return save(null, body);
    }

    @ApiOperation(value = "Editar envío programado (también pausar: enabled=false)")
    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response update(@PathParam("id") int id, ReportSchedule body) {
        return save(id, body);
    }

    private Response save(Integer id, ReportSchedule body) {
        Optional<Integer> c = editor();
        if (!c.isPresent()) return Response.PERMISSION_DENIED();
        if (body == null) return Response.ERROR("Faltan los datos del envío.");
        Set<Integer> mine = scope.groups();
        ReportSchedule current = null;
        if (id != null) {
            current = service.find(c.get(), id);
            if (current == null || !visible(current, mine)) return Response.ERROR("Ese envío ya no existe.");
        }
        String problem = ReportScheduleCalc.normalize(body, ReportScheduleService.REPORTS.keySet());
        if (problem != null) return Response.ERROR(problem);
        if (mine != null && (body.getGroupId() == null || !mine.contains(body.getGroupId()))) {
            return Response.ERROR("Elige una de tus carpetas para este envío.");
        }
        if (body.getGroupId() != null && !service.groupExists(c.get(), body.getGroupId())) {
            return Response.ERROR("La carpeta elegida no existe.");
        }
        body.setId(id);
        body.setCustomerId(c.get());
        if (current == null) {
            body.setCreatedBy(SecurityContext.get().getCurrentUser().map(u -> u.getId()).orElse(null));
        }
        ReportSchedule saved = service.save(body);
        logger.info("Report schedule {} '{}' saved by {} (customer {}, {} {} to {})", saved.getId(), saved.getName(),
                SecurityContext.get().getCurrentUser().map(u -> u.getLogin()).orElse("?"), c.get(), saved.getReport(),
                saved.getFrequency(), saved.getRecipients());
        return Response.OK(saved);
    }

    @ApiOperation(value = "Eliminar envío programado")
    @DELETE
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response delete(@PathParam("id") int id) {
        Optional<Integer> c = editor();
        if (!c.isPresent()) return Response.PERMISSION_DENIED();
        ReportSchedule s = service.find(c.get(), id);
        if (s == null || !visible(s, scope.groups())) return Response.ERROR("Ese envío ya no existe.");
        service.delete(c.get(), id);
        return Response.OK();
    }

    @ApiOperation(value = "Enviar ahora", notes = "Genera el reporte y lo envía en este momento. Responde "
            + "{ sent, status: ok|error|smtp, message, smtpConfigured }; sin SMTP no falla: lo indica.")
    @POST
    @Path("/{id}/send")
    @Produces(MediaType.APPLICATION_JSON)
    public Response sendNow(@PathParam("id") int id) {
        Optional<Integer> c = editor();
        if (!c.isPresent()) return Response.PERMISSION_DENIED();
        ReportSchedule s = service.find(c.get(), id);
        if (s == null || !visible(s, scope.groups())) return Response.ERROR("Ese envío ya no existe.");
        ReportScheduler.Outcome o = service.sendNow(s);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sent", o.sent());
        out.put("status", o.status);
        out.put("message", o.sent() ? "Enviado a " + s.getRecipients() : o.error);
        out.put("smtpConfigured", service.smtpConfigured());
        out.put("schedule", service.find(c.get(), id));
        return Response.OK(out);
    }

    private static boolean visible(ReportSchedule s, Set<Integer> mine) {
        return mine == null || (s.getGroupId() != null && mine.contains(s.getGroupId()));
    }

    private static Optional<Integer> editor() {
        if (SecurityContext.get() == null || !SecurityContext.get().hasPermission("manage_fleet")) return Optional.empty();
        return SecurityContext.get().getCurrentCustomerId();
    }
}
