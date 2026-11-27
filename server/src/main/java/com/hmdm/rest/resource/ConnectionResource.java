package com.hmdm.rest.resource;

import com.hmdm.persistence.DeviceConnectionDAO;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.resource.support.UserScope;
import com.hmdm.security.SecurityContext;
import com.hmdm.util.ConnectionStatus;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Connection state and diagnostics for the console: per device, whether it is online, resting ("En reposo": silent
 * for a known, harmless reason) or offline, plus the last network failure and the last silence its agent explained.
 * Read-only; any signed-in user of the customer, narrowed to the user's folders like the device list.
 */
@Singleton
@Path("/private/connection/v1")
@Api(tags = {"Conexión: estado y diagnóstico de conexión de los dispositivos"})
public class ConnectionResource {

    private DeviceConnectionDAO connectionDAO;
    private UserScope scope;

    /** A constructor required by Swagger. */
    public ConnectionResource() {
    }

    @Inject
    public ConnectionResource(DeviceConnectionDAO connectionDAO, UserScope scope) {
        this.connectionDAO = connectionDAO;
        this.scope = scope;
    }

    @ApiOperation(value = "Connection state of every device", notes = "state: online | resting | offline, with the reason of a rest.")
    @GET
    @Path("/devices")
    @Produces(MediaType.APPLICATION_JSON)
    public Response list() {
        List<Map<String, Object>> rows = rows(null);
        return rows == null ? Response.PERMISSION_DENIED() : Response.OK(rows);
    }

    @ApiOperation(value = "Connection state and diagnostics of one device")
    @GET
    @Path("/devices/{number}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response one(@PathParam("number") String number) {
        List<Map<String, Object>> rows = rows(number);
        if (rows == null) return Response.PERMISSION_DENIED();
        return Response.OK(rows.isEmpty() ? null : rows.get(0));
    }

    private List<Map<String, Object>> rows(String only) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) return null;
        Set<String> visible = scope.deviceNumbers();
        long now = System.currentTimeMillis();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : connectionDAO.listForCustomer(customerId.get())) {
            String number = String.valueOf(r.get("number"));
            if (only != null && !only.equals(number)) continue;
            if (visible != null && !visible.contains(number)) continue;
            out.add(ConnectionStatus.view(r, now));
        }
        return out;
    }
}
