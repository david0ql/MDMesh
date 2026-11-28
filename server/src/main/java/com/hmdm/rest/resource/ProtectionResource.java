/*
 *
 * DallyControl — factory-reset protection (FRP) for the console (not part of upstream Headwind).
 *
 * The company Google accounts that may set a phone up again after a reset from recovery are kept once for the
 * company and, when a folder needs its own, per folder; a device takes its policy's own accounts, else those of the
 * nearest folder above it, else the company's (see FrpAccounts.resolve). This resource is the console's API for
 * those defaults and for the status of one device: what it should hold, what the agent read back from the system,
 * and one line saying whether the protection is in force.
 *
 * Permissions: any signed-in user of the customer may read; changing the accounts needs manage_fleet. The company's
 * accounts are for full administrators only; a folder administrator sets the accounts of their own folders (checked
 * by UserScopeFilter) and sees only those folders and their own devices.
 *
 */

package com.hmdm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.ProtectionDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.domain.DeviceState;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.agent.DesiredDevice;
import com.hmdm.rest.resource.support.UserScope;
import com.hmdm.security.SecurityContext;
import com.hmdm.util.AgentCapabilityTokens;
import com.hmdm.util.DcPolicy;
import com.hmdm.util.FrpAccounts;
import com.hmdm.util.ProtectionSummary;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Singleton
@Path("/private/protection/v1")
@Api(tags = {"Protección de restablecimiento de fábrica (FRP)"})
public class ProtectionResource {

    private static final Logger logger = LoggerFactory.getLogger(ProtectionResource.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private ProtectionDAO dao;
    private AgentCommandDAO commandDAO;
    private UnsecureDAO unsecureDAO;
    private UserScope scope;

    /** A constructor required by Swagger. */
    public ProtectionResource() {
    }

    @Inject
    public ProtectionResource(ProtectionDAO dao, AgentCommandDAO commandDAO, UnsecureDAO unsecureDAO, UserScope scope) {
        this.dao = dao;
        this.commandDAO = commandDAO;
        this.unsecureDAO = unsecureDAO;
        this.scope = scope;
    }

    /** Body of the two PUTs. */
    public static class AccountsBody {
        public List<FrpAccounts.Account> accounts;
    }

    private static Optional<Integer> reader() {
        return SecurityContext.get() == null ? Optional.empty() : SecurityContext.get().getCurrentCustomerId();
    }

    private static Optional<Integer> editor(String action) {
        if (SecurityContext.get() == null || !SecurityContext.get().hasPermission("manage_fleet")) {
            logger.warn("Permission denied: {} requires manage_fleet", action);
            return Optional.empty();
        }
        return SecurityContext.get().getCurrentCustomerId();
    }

    private static String who() {
        return SecurityContext.get().getCurrentUser().map(u -> u.getLogin()).orElse("?");
    }

    // =================================================================================================================
    @ApiOperation(value = "Cuentas por defecto", notes = "Las cuentas de Google de la empresa y de cada carpeta que pueden "
            + "reactivar un equipo tras un restablecimiento por botones.")
    @GET
    @Path("/defaults")
    @Produces(MediaType.APPLICATION_JSON)
    public Response defaults() {
        Optional<Integer> customerId = reader();
        if (!customerId.isPresent()) return Response.PERMISSION_DENIED();
        Set<Integer> mine = scope.groups(); // null = every folder
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> folders = new LinkedHashMap<>();
        List<FrpAccounts.Account> company = java.util.Collections.emptyList();
        for (Map.Entry<Integer, List<FrpAccounts.Account>> e : dao.defaults(customerId.get()).entrySet()) {
            if (e.getKey() == ProtectionDAO.COMPANY) company = e.getValue();
            else if (mine == null || mine.contains(e.getKey())) folders.put(String.valueOf(e.getKey()), e.getValue());
        }
        out.put("company", company);
        out.put("folders", folders);
        out.put("canEditCompany", !scope.restricted() && SecurityContext.get().hasPermission("manage_fleet"));
        out.put("canEditFolders", SecurityContext.get().hasPermission("manage_fleet"));
        return Response.OK(out);
    }

    // =================================================================================================================
    @ApiOperation(value = "Guardar las cuentas de la empresa")
    @PUT
    @Path("/defaults/company")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response saveCompany(AccountsBody body) {
        Optional<Integer> customerId = editor("set the company's reset-protection accounts");
        if (!customerId.isPresent() || scope.restricted()) return Response.PERMISSION_DENIED();
        return save(customerId.get(), ProtectionDAO.COMPANY, body);
    }

    // =================================================================================================================
    @ApiOperation(value = "Guardar las cuentas de una carpeta", notes = "Una lista vacía quita las cuentas propias de la "
            + "carpeta: sus equipos vuelven a heredar las de la carpeta superior o las de la empresa.")
    @PUT
    @Path("/defaults/groups/{groupId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response saveFolder(@PathParam("groupId") int groupId, AccountsBody body) {
        Optional<Integer> customerId = editor("set a folder's reset-protection accounts");
        if (!customerId.isPresent()) return Response.PERMISSION_DENIED();
        if (groupId <= 0 || !dao.groupExists(customerId.get(), groupId)) return Response.ERROR("error.protection.folder.unknown");
        if (!scope.canGroup(groupId)) return Response.PERMISSION_DENIED();
        return save(customerId.get(), groupId, body);
    }

    private Response save(int customerId, int groupId, AccountsBody body) {
        String json;
        try {
            json = FrpAccounts.normalize(body == null ? null : body.accounts);
        } catch (IllegalArgumentException e) {
            // "id:<value>" = that entry's id is not a numeric Google account id; "email:<value>"; "max:<n>".
            return Response.ERROR("error.protection.account.invalid", e.getMessage());
        }
        dao.saveDefault(customerId, groupId, json, who());
        logger.info("Reset-protection accounts of {} {} set by {} ({})", groupId == ProtectionDAO.COMPANY ? "customer" : "folder",
                groupId == ProtectionDAO.COMPANY ? customerId : groupId, who(), json == null ? "none" : FrpAccounts.parse(json).size() + " account(s)");
        return Response.OK(FrpAccounts.parse(json));
    }

    // =================================================================================================================
    @ApiOperation(value = "Estado de protección de un equipo", notes = "Las cuentas que le corresponden (y de dónde vienen), "
            + "lo que el agente leyó del sistema, y una línea que dice si la protección está activa.")
    @GET
    @Path("/devices/{deviceId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response device(@PathParam("deviceId") String deviceId) {
        Optional<Integer> customerId = reader();
        if (!customerId.isPresent()) return Response.PERMISSION_DENIED();
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) return Response.ERROR("error.agent.device.unknown");
        if (device.getCustomerId() != customerId.get() || !scope.canDeviceNumber(customerId.get(), deviceId)) {
            return Response.PERMISSION_DENIED();
        }

        Configuration cfg = device.getConfigurationId() == null ? null : unsecureDAO.getConfigurationById(device.getConfigurationId());
        DcPolicy dc = DcPolicy.parse(cfg == null ? null : cfg.getDcPolicy());
        DesiredDevice rules = dc.getDevice();
        FrpAccounts.Effective expected = FrpAccounts.resolve(rules, dc.getFrpLabels(),
                device.getId() == null ? null : dao.deviceFolderDefaults(customerId.get(), device.getId()),
                dao.companyDefault(customerId.get()));

        DeviceState state = commandDAO.getState(deviceId);
        JsonNode protection = null;
        try {
            if (state != null && state.getTelemetry() != null) {
                JsonNode p = JSON.readTree(state.getTelemetry()).path("security").path("protection");
                if (p.isObject()) protection = p;
            }
        } catch (Exception e) {
            logger.debug("Telemetry of {} not readable: {}", deviceId, e.getMessage());
        }
        ProtectionSummary summary = ProtectionSummary.frp(protection, expected.accounts.size());
        Set<String> tokens = AgentCapabilityTokens.flatten(commandDAO.getDeviceCapabilities(deviceId));

        Map<String, Object> exp = new LinkedHashMap<>();
        exp.put("accounts", expected.accounts);
        exp.put("source", expected.source);
        exp.put("groupId", expected.groupId);
        exp.put("groupName", expected.groupName);

        Map<String, Object> policy = new LinkedHashMap<>();
        // The anti-theft PIN of a kiosk policy blocks the reset from Settings too (see DesiredConfigBuilder).
        boolean pinBlocks = dc.getPowerPin() != null && cfg != null && cfg.isKioskMode();
        policy.put("factoryReset", pinBlocks || (rules != null && "block".equals(rules.getFactoryReset())));
        policy.put("safeBoot", rules != null && "block".equals(rules.getSafeBoot()));
        policy.put("debugging", rules != null && "block".equals(rules.getDebugging()));
        policy.put("oemUnlock", rules != null && "block".equals(rules.getOemUnlock()));
        policy.put("maxFailedPasswordsForWipe", rules == null ? null : rules.getMaxFailedPasswordsForWipe());

        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("wipeOptions", tokens.contains("device.wipeOptions"));
        caps.put("lostMode", tokens.contains("device.lostMode"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", summary.state);
        out.put("summary", summary.text);
        out.put("expected", exp);
        out.put("policy", policy);
        out.put("reported", protection);
        out.put("reportedAt", state == null ? null : state.getUpdatedAt());
        out.put("capabilities", caps);
        return Response.OK(out);
    }
}
