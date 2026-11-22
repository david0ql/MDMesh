/*
 *
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hmdm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdm.notification.AgentWakeHub;
import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.SavedScriptDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.domain.SavedScript;
import com.hmdm.persistence.domain.User;
import com.hmdm.rest.json.Response;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * <p>Admin-authored, reusable device scripts (ADR: delegable actions). Two audiences:</p>
 * <ul>
 *   <li><b>Authoring</b> ({@code GET/PUT/DELETE}) — gated to administrators ({@code isSuperAdmin()} or the
 *       {@code manage_scripts} permission). This is where a one-click action such as "Borrar datos de
 *       Amovil Preventa" is defined once.</li>
 *   <li><b>Running</b> ({@code POST /{id}/run}) — gated to {@code edit_devices}, exactly like queueing a
 *       command, so a folder operator can run a delegated script on their own devices without being able
 *       to change it.</li>
 * </ul>
 * <p>The server translates the script's verbs into the SAME agent-v1 commands the console already queues
 * (reusing {@link AgentCommandDAO} + {@link AgentWakeHub}); it never invents a new device contract.</p>
 */
@Singleton
@Path("/private/scripts")
@Api(tags = {"Device scripts"})
public class ScriptsResource {

    private static final Logger logger = LoggerFactory.getLogger(ScriptsResource.class);

    /** The permission (besides super-admin) that lets a user author scripts. */
    public static final String MANAGE_PERMISSION = "manage_scripts";

    private static final Pattern PKG = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$");
    private static final Pattern PARAM_REF = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*\\}\\}");

    private SavedScriptDAO scriptDAO;
    private AgentCommandDAO commandDAO;
    private UnsecureDAO unsecureDAO;
    private AgentWakeHub wakeHub;
    private com.hmdm.rest.resource.support.UserScope scope;

    public ScriptsResource() {
    }

    @Inject
    public ScriptsResource(SavedScriptDAO scriptDAO,
                           AgentCommandDAO commandDAO,
                           UnsecureDAO unsecureDAO,
                           AgentWakeHub wakeHub,
                           com.hmdm.rest.resource.support.UserScope scope) {
        this.scriptDAO = scriptDAO;
        this.commandDAO = commandDAO;
        this.unsecureDAO = unsecureDAO;
        this.wakeHub = wakeHub;
        this.scope = scope;
    }

    /** Authoring is for administrators only (super-admin or the manage_scripts permission). */
    private static boolean canManage() {
        return SecurityContext.get().isSuperAdmin() || SecurityContext.get().hasPermission(MANAGE_PERMISSION);
    }

    // =================================================================================================================
    @ApiOperation(value = "List saved scripts", notes = "All reusable scripts of the current customer. Readable by any "
            + "user (so operators see the delegable quick actions); authoring is admin-only.")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response list() {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        return Response.OK(scriptDAO.list(customerId.get()));
    }

    /** Create/update body — {id?, name, description?, body, paramsJson?, quickAction?}. */
    public static class ScriptBody {
        public Integer id;
        public String name;
        public String description;
        public String body;
        public String paramsJson;
        public boolean quickAction;
    }

    // =================================================================================================================
    @ApiOperation(value = "Create or update a saved script", notes = "Admin-only. Validates that the script's verbs are "
            + "all understood before storing it.")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response save(ScriptBody body) {
        if (!canManage()) {
            return Response.PERMISSION_DENIED();
        }
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        if (body == null || body.name == null || body.name.trim().isEmpty() || body.name.trim().length() > 100) {
            return Response.ERROR("El nombre es obligatorio (máximo 100 caracteres).");
        }
        if (body.body == null || body.body.trim().isEmpty()) {
            return Response.ERROR("El script no puede estar vacío.");
        }
        // Validate the verbs now (with no parameter substitution, placeholders are tolerated) so a broken
        // script can never be stored and then fail halfway across a fleet.
        try {
            translate(body.body, java.util.Collections.emptyMap(), true);
        } catch (ScriptError e) {
            return Response.ERROR(e.getMessage());
        }

        long now = System.currentTimeMillis();
        SavedScript script = new SavedScript();
        script.setCustomerId(customerId.get());
        script.setName(body.name.trim());
        script.setDescription(body.description == null || body.description.trim().isEmpty() ? null
                : body.description.trim().substring(0, Math.min(500, body.description.trim().length())));
        script.setBody(body.body);
        script.setParamsJson(body.paramsJson == null || body.paramsJson.trim().isEmpty() ? null : body.paramsJson.trim());
        script.setQuickAction(body.quickAction);
        script.setUpdatedAt(now);

        if (body.id != null) {
            SavedScript existing = scriptDAO.findById(customerId.get(), body.id);
            if (existing == null) {
                return Response.ERROR("error.script.not.found");
            }
            script.setId(body.id);
            script.setCreatedBy(existing.getCreatedBy());
            script.setCreatedAt(existing.getCreatedAt());
            if (!scriptDAO.update(script)) {
                return Response.ERROR("error.script.not.found");
            }
            logger.info("Saved script {} updated (customer {})", body.id, customerId.get());
        } else {
            script.setCreatedBy(SecurityContext.get().getCurrentUser().map(User::getId).orElse(null));
            script.setCreatedAt(now);
            scriptDAO.insert(script);
            logger.info("Saved script {} created (customer {})", script.getId(), customerId.get());
        }
        return Response.OK(scriptDAO.findById(customerId.get(), script.getId()));
    }

    // =================================================================================================================
    @ApiOperation(value = "Delete a saved script", notes = "Admin-only.")
    @DELETE
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response delete(@PathParam("id") int id) {
        if (!canManage()) {
            return Response.PERMISSION_DENIED();
        }
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        if (!scriptDAO.delete(customerId.get(), id)) {
            return Response.ERROR("error.script.not.found");
        }
        logger.info("Saved script {} deleted (customer {})", id, customerId.get());
        return Response.OK();
    }

    /** Run body — {deviceIds:[int], params?:{key:value}}. */
    public static class RunBody {
        public List<Integer> deviceIds;
        public Map<String, String> params;
    }

    // =================================================================================================================
    @ApiOperation(value = "Run a saved script on devices", notes = "Translates the script's verbs into agent-v1 commands "
            + "and queues them, in order, on each device. Gated by edit_devices (like queueing a command), so a delegated "
            + "quick action can be run by an operator who cannot edit the script. Body: { deviceIds, params }.")
    @POST
    @Path("/{id}/run")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response run(@PathParam("id") int id, RunBody body) {
        if (!SecurityContext.get().hasPermission("edit_devices")) {
            return Response.PERMISSION_DENIED();
        }
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        if (body == null || body.deviceIds == null || body.deviceIds.isEmpty()) {
            return Response.ERROR("error.script.run.noDevices");
        }
        SavedScript script = scriptDAO.findById(customerId.get(), id);
        if (script == null) {
            return Response.ERROR("error.script.not.found");
        }

        List<CommandSpec> specs;
        try {
            specs = translate(script.getBody(), body.params == null ? java.util.Collections.emptyMap() : body.params, false);
        } catch (ScriptError e) {
            return Response.ERROR(e.getMessage());
        }
        if (specs.isEmpty()) {
            return Response.ERROR("error.script.run.empty");
        }

        int ranDevices = 0;
        int queued = 0;
        List<Integer> skipped = new ArrayList<>();
        for (Integer deviceId : body.deviceIds) {
            if (deviceId == null) { continue; }
            Device device = unsecureDAO.getDeviceById(deviceId);
            if (device == null || device.getCustomerId() != customerId.get()
                    || !scope.canDeviceId(customerId.get(), deviceId)) {
                skipped.add(deviceId);
                continue;
            }
            long now = System.currentTimeMillis();
            for (CommandSpec spec : specs) {
                AgentCommand command = new AgentCommand();
                command.setDeviceNumber(device.getNumber());
                command.setType(spec.type);
                command.setPayload(spec.payload);
                command.setRequiresCapability(spec.requiresCapability);
                command.setStatus("pending");
                command.setCreatedAt(now);
                commandDAO.insert(command);
                queued++;
            }
            wakeHub.wake(device.getNumber(), "commands");
            ranDevices++;
        }

        logger.info("Saved script {} run on {} device(s) ({} commands, {} skipped) (customer {})",
                id, ranDevices, queued, skipped.size(), customerId.get());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("devices", ranDevices);
        result.put("queued", queued);
        result.put("skipped", skipped);
        result.put("steps", specs.size());
        return Response.OK(result);
    }

    // ============================= verb translation (server side, closed verb set) ==================================

    /** One queued command: type == requiresCapability for device.* actions (proto convention). */
    private static final class CommandSpec {
        final String type;
        final String payload;
        final String requiresCapability;
        CommandSpec(String type, String payload) {
            this.type = type;
            this.payload = payload;
            this.requiresCapability = type;
        }
    }

    /** A human-readable, localized verb error (surfaced to the console). */
    private static final class ScriptError extends Exception {
        ScriptError(String message) { super(message); }
    }

    /**
     * <p>Translate the script body into a list of commands. Mirrors the ad-hoc ScriptPanel grammar, but the
     * verb set here is CLOSED and deliberately excludes destructive device-wide actions (wipe, passcode
     * reset): a delegable quick action must not be able to brick a fleet.</p>
     *
     * @param lenient when true (authoring-time validation), unresolved {@code {{param}}} placeholders in a
     *                package argument are accepted; when false (run-time) every reference must resolve.
     */
    private static List<CommandSpec> translate(String src, Map<String, String> params, boolean lenient) throws ScriptError {
        List<CommandSpec> out = new ArrayList<>();
        ObjectMapper om = new ObjectMapper();
        String[] lines = src.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String text = lines[i].trim();
            if (text.isEmpty() || text.startsWith("#")) { continue; }
            int line = i + 1;
            String substituted = substitute(text, params);
            String[] parts = substituted.split("\\s+", 2);
            String verb = parts[0].toLowerCase(java.util.Locale.ROOT);
            String arg = parts.length > 1 ? parts[1].trim() : "";
            boolean hasPlaceholder = PARAM_REF.matcher(substituted).find();

            switch (verb) {
                case "open": {
                    if (!(lenient && hasPlaceholder) && !PKG.matcher(arg).matches()) {
                        throw err(line, "open necesita un nombre de paquete, p. ej. open co.amovil.preventa");
                    }
                    ObjectNode p = om.createObjectNode();
                    p.put("packageName", arg);
                    out.add(new CommandSpec("device.appLaunch", p.toString()));
                    break;
                }
                case "cleardata": {
                    if (!(lenient && hasPlaceholder) && !PKG.matcher(arg).matches()) {
                        throw err(line, "cleardata necesita un nombre de paquete, p. ej. cleardata co.amovil.preventa");
                    }
                    ObjectNode p = om.createObjectNode();
                    p.putArray("clearData").add(arg);
                    out.add(new CommandSpec("device.storageClean", p.toString()));
                    break;
                }
                case "message":
                case "alert": {
                    if (arg.isEmpty()) { throw err(line, "message necesita un texto"); }
                    ObjectNode p = om.createObjectNode();
                    p.put("body", arg);
                    out.add(new CommandSpec("device.alert", p.toString()));
                    break;
                }
                case "lockscreen": {
                    ObjectNode p = om.createObjectNode();
                    p.put("message", arg);
                    out.add(new CommandSpec("device.lockscreenMessage", p.toString()));
                    break;
                }
                case "lock":
                    out.add(new CommandSpec("device.lock", null));
                    break;
                case "reboot":
                    out.add(new CommandSpec("device.reboot", null));
                    break;
                case "ring": {
                    double secs = arg.isEmpty() ? 30 : parseNumber(line, arg, "ring");
                    if (secs <= 0 || secs > 600) { throw err(line, "ring lleva segundos entre 1 y 600"); }
                    ObjectNode p = om.createObjectNode();
                    p.put("durationMs", (long) Math.round(secs * 1000));
                    out.add(new CommandSpec("device.ring", p.toString()));
                    break;
                }
                case "stopring":
                    out.add(new CommandSpec("device.ringStop", null));
                    break;
                case "stop":
                    if (!arg.equalsIgnoreCase("ring")) { throw err(line, "¿quisiste decir \"stop ring\"?"); }
                    out.add(new CommandSpec("device.ringStop", null));
                    break;
                case "location": {
                    String m = arg.toLowerCase(java.util.Locale.ROOT);
                    if (!m.equals("accurate") && !m.equals("battery")) { throw err(line, "usa location accurate | battery"); }
                    ObjectNode p = om.createObjectNode();
                    p.put("mode", m.equals("accurate") ? "active" : "passive");
                    out.add(new CommandSpec("device.locationMode", p.toString()));
                    break;
                }
                default:
                    throw err(line, "acción desconocida \"" + parts[0] + "\"");
            }
        }
        return out;
    }

    private static String substitute(String text, Map<String, String> params) {
        java.util.regex.Matcher m = PARAM_REF.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            String value = params.get(key);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(value == null ? m.group(0) : value.trim()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static double parseNumber(int line, String arg, String verb) throws ScriptError {
        try {
            return Double.parseDouble(arg);
        } catch (NumberFormatException e) {
            throw err(line, verb + " lleva un número");
        }
    }

    private static ScriptError err(int line, String why) {
        return new ScriptError("Línea " + line + ": " + why);
    }
}
