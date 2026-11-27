/*
 *
 * DallyControl — mobile data per app (new console module, not part of upstream Headwind).
 *
 * One device's mobile-data state and its own override on top of the policy:
 *   GET  /rest/private/agent/v1/devices/{deviceId}/mobileData   policy, override, what the phone enforces, use vs. limit
 *   POST /rest/private/agent/v1/devices/{deviceId}/mobileData   store the override and push the configuration now
 *
 * The path sits under /agent/v1/devices/{deviceId}/…, so UserScopeFilter checks a folder administrator against the
 * device's folder before the request gets here. Reading is open to any user of the customer who can see the device;
 * changing the override needs edit_devices, like every other per-device setting.
 *
 */

package com.hmdm.rest.resource;

import com.hmdm.notification.AgentWakeHub;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.Device;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.resource.support.ConfigReconciler;
import com.hmdm.rest.resource.support.MobileDataService;
import com.hmdm.security.SecurityContext;
import com.hmdm.util.MobileDataPolicy;
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
import javax.ws.rs.core.MediaType;
import java.util.Optional;

@Singleton
@Path("/private/agent/v1/devices/{deviceId}/mobileData")
@Api(tags = {"Datos móviles por aplicación"})
public class MobileDataResource {
    private static final Logger logger = LoggerFactory.getLogger(MobileDataResource.class);

    private UnsecureDAO unsecureDAO;
    private MobileDataService service;
    private ConfigReconciler reconciler;
    private AgentWakeHub wakeHub;

    /** A constructor required by Swagger. */
    public MobileDataResource() {
    }

    @Inject
    public MobileDataResource(UnsecureDAO unsecureDAO, MobileDataService service, ConfigReconciler reconciler, AgentWakeHub wakeHub) {
        this.unsecureDAO = unsecureDAO;
        this.service = service;
        this.reconciler = reconciler;
        this.wakeHub = wakeHub;
    }

    private Device device(String deviceId) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) return null;
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        return device == null || device.getCustomerId() != customerId.get() ? null : device;
    }

    @ApiOperation(value = "Datos móviles de un equipo",
            notes = "La sección de la política, el ajuste propio del equipo, lo que el teléfono aplica (mecanismo, apps "
                    + "sin datos móviles) y el consumo del periodo frente a los límites.")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response get(@PathParam("deviceId") String deviceId) {
        Device device = device(deviceId);
        if (device == null) return Response.PERMISSION_DENIED();
        try {
            return Response.OK(service.view(device));
        } catch (Exception e) {
            logger.warn("Mobile-data view of {} failed", deviceId, e);
            return Response.ERROR("error.mobiledata.failed");
        }
    }

    @ApiOperation(value = "Ajuste de datos móviles de un equipo",
            notes = "Cuerpo: { mode: \"policy\" | \"unrestricted\", allow: [paquetes], block: [paquetes], ignoreLimits: bool }. "
                    + "\"policy\" sin listas quita el ajuste. El ajuste del equipo gana sobre la política y se envía al "
                    + "teléfono de inmediato.")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response set(@PathParam("deviceId") String deviceId, MobileDataPolicy.DeviceOverride body) {
        if (!SecurityContext.get().hasPermission("edit_devices")) {
            logger.warn("Permission denied: mobile-data override requires edit_devices (user {})",
                    SecurityContext.get().getCurrentUser().map(u -> u.getLogin()).orElse("?"));
            return Response.PERMISSION_DENIED();
        }
        Device device = device(deviceId);
        if (device == null) return Response.PERMISSION_DENIED();
        try {
            service.saveOverride(device.getId(), body);
            // The changed document has a new revision: queue it now and wake the phone, so it applies within seconds.
            reconciler.forceApply(device, System.currentTimeMillis());
            if (wakeHub != null) wakeHub.wake(deviceId, "commands");
            logger.info("Mobile-data override of device {} changed by {}", deviceId,
                    SecurityContext.get().getCurrentUser().map(u -> u.getLogin()).orElse("?"));
            return Response.OK(service.view(device));
        } catch (Exception e) {
            logger.warn("Mobile-data override of {} failed", deviceId, e);
            return Response.ERROR("error.mobiledata.failed");
        }
    }
}
