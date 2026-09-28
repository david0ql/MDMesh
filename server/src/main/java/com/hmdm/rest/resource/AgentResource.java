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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.AgentEnrollmentTokenDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.AgentEnrollmentToken;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.domain.DeviceState;
import com.hmdm.rest.json.agent.AgentDeviceState;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.agent.AgentCapabilities;
import com.hmdm.rest.json.agent.AgentCheckInRequest;
import com.hmdm.rest.json.agent.AgentCheckInResponse;
import com.hmdm.rest.json.agent.AgentCommandResult;
import com.hmdm.rest.json.agent.AgentEnrollRequest;
import com.hmdm.rest.json.agent.AgentEnrollResponse;
import com.hmdm.rest.json.agent.AgentProtocol;
import com.hmdm.util.AgentCapabilityTokens;
import com.hmdm.util.CryptoUtil;
import com.hmdm.util.DesiredConfigBuilder;
import com.hmdm.util.RemoteSessions;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * <p>Public-facing resource for the from-scratch Android agent v1 protocol. Mirrors
 * {@code proto/endpoints.md}. Command {@code type}/{@code payload} are entirely opaque: this
 * resource never switches on them. Delivery is gated only by capability-token set membership
 * (see {@link AgentCapabilityTokens}).</p>
 */
@Singleton
@Path("/public/agent/v1")
@Api(tags = {"Agent v1"})
public class AgentResource {

    private static final Logger logger = LoggerFactory.getLogger(AgentResource.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Accepted command-result statuses (mirrors proto CommandStatus). Anything else is ignored. */
    private static final Set<String> ALLOWED_RESULT_STATUS = new HashSet<>(Arrays.asList(
            "accepted", "done", "failed", "unsupported", "expired"));

    // Ingestion caps: an authenticated device is still untrusted input. These bound what a single
    // check-in (potentially every 5s in foreground mode) can write into the database.
    private static final int MAX_EVENTS_PER_CHECKIN = 100;
    private static final int MAX_EVENT_DETAIL_CHARS = 4000;
    private static final int MAX_TELEMETRY_CHARS = 256 * 1024;

    private UnsecureDAO unsecureDAO;
    private AgentEnrollmentTokenDAO tokenDAO;
    private AgentCommandDAO commandDAO;
    private com.hmdm.rest.resource.support.ConfigAppInstaller configAppInstaller;
    private com.hmdm.rest.resource.support.ConfigReconciler configReconciler;

    /**
     * <p>A constructor required by Swagger.</p>
     */
    public AgentResource() {
    }

    @Inject
    public AgentResource(UnsecureDAO unsecureDAO,
                         AgentEnrollmentTokenDAO tokenDAO,
                         AgentCommandDAO commandDAO,
                         com.hmdm.rest.resource.support.ConfigAppInstaller configAppInstaller,
                         com.hmdm.rest.resource.support.ConfigReconciler configReconciler) {
        this.unsecureDAO = unsecureDAO;
        this.tokenDAO = tokenDAO;
        this.commandDAO = commandDAO;
        this.configAppInstaller = configAppInstaller;
        this.configReconciler = configReconciler;
    }

    // =================================================================================================================
    @ApiOperation(value = "Enroll an agent", notes = "Validates a single-use token and registers the device.")
    @POST
    @Path("/enroll")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response enroll(AgentEnrollRequest request) {
        if (request == null || request.getEnrollToken() == null || request.getEnrollToken().trim().isEmpty()) {
            return Response.ERROR("error.agent.token.invalid");
        }

        // A single-use token is matched exactly; a folder's reusable code however it was typed (case, dashes, spaces).
        AgentEnrollmentToken token = tokenDAO.findByToken(request.getEnrollToken().trim());
        if (token == null) {
            String code = com.hmdm.util.EnrollmentCodes.normalize(request.getEnrollToken());
            token = code == null ? null : tokenDAO.findByToken(code);
            if (token != null && !token.isReusable()) {
                token = null;
            }
        }
        if (token == null) {
            return Response.ERROR("error.agent.token.invalid");
        }
        final boolean reusable = token.isReusable();
        // Friendly pre-checks for good error messages; the CLAIM below is the actual guard.
        if (reusable && token.isRevoked()) {
            return Response.ERROR("error.agent.token.invalid");
        }
        if (!reusable && token.isUsed()) {
            return Response.ERROR("error.agent.token.used");
        }
        if (token.getExpiresAt() != null && token.getExpiresAt() < System.currentTimeMillis()) {
            return Response.ERROR("error.agent.token.expired");
        }
        // Atomically consume the token BEFORE creating anything: with N concurrent enrolls
        // (freshly-provisioned agents fire several racing check-in engines) exactly one wins;
        // the blind mark-used-after-create used here before let every racer mint its own
        // device row, leaving permanent ghost devices. A reusable code only counts the use.
        if (reusable ? !tokenDAO.claimReusable(token.getId()) : !tokenDAO.claim(token.getId())) {
            return Response.ERROR(reusable ? "error.agent.token.invalid" : "error.agent.token.used");
        }

        String deviceId = UUID.randomUUID().toString();
        Device device = null;
        try {
            // The device lands in the token's customer and group (company). Its configuration is the one the
            // token pins, else its group's, else the global default (settings) — device > group > global.
            Integer groupId = token.getGroupId();
            com.hmdm.persistence.domain.DeviceGroupView group =
                    groupId == null ? null : commandDAO.findGroup(token.getCustomerId(), groupId);
            // Folders nest: a sub-folder without its own configuration runs its nearest ancestor's.
            Integer inherited = group == null ? null : group.getEffectiveConfigurationId();
            boolean pinned = token.getConfigurationId() != null;
            device = unsecureDAO.createNewDeviceForToken(deviceId, token.getCustomerId(),
                    pinned ? token.getConfigurationId() : inherited, group == null ? null : groupId);
            if (device != null && pinned) {
                commandDAO.updateDevicePinned(device.getId(), true);
            }
            if (device == null) {
                // Named error (not a generic internal one) — this exact failure cost a debugging
                // session: valid token, reachable server, no device row.
                logger.warn("Agent enroll: on-demand device creation is disabled by settings (token {})", token.getId());
                return Response.ERROR("error.agent.enrollment.disabled");
            }

            // Mint a per-device secret; persist only its SHA-256 hash. The plaintext is
            // returned exactly once (below) and is required as a bearer token on /checkin.
            String deviceSecret = UUID.randomUUID().toString().replace("-", "")
                    + UUID.randomUUID().toString().replace("-", "");
            commandDAO.updateDeviceSecretHash(deviceId, CryptoUtil.getSHA256String(deviceSecret));
            commandDAO.updateDeviceCapabilities(deviceId, capabilitiesJson(request.getCapabilities()));
            // Record the agent's stable hardware id so duplicate enrollments of the same physical
            // device can be detected/flagged in the admin UI (we still create a fresh row per enroll).
            if (request.getHardwareId() != null && !request.getHardwareId().trim().isEmpty()) {
                commandDAO.updateHardwareId(deviceId, request.getHardwareId().trim());
            }
            commandDAO.touchLastUpdate(deviceId);

            String configurationName = null;
            if (device.getConfigurationId() != null) {
                Configuration configuration = unsecureDAO.getConfigurationById(device.getConfigurationId());
                if (configuration != null) {
                    configurationName = configuration.getName();
                }
            }

            // Best-effort: queue the configuration's action=install apps so a config acts as a
            // golden image. Failures are logged inside; enrollment itself must not fail on this.
            int queuedApps = configAppInstaller.enqueueConfigApps(device);

            logger.info("Agent enrolled device {} (configuration {}, {} config apps queued)",
                    deviceId, device.getConfigurationId(), queuedApps);
            return Response.OK(new AgentEnrollResponse(deviceId, configurationName, deviceSecret));
        } finally {
            // A server-side failure (settings rejection, SQL error) must not burn the single-use
            // token — the agent retries and succeeds once the operator fixes the condition.
            if (device == null) {
                if (reusable) {
                    tokenDAO.releaseReusable(token.getId());
                } else {
                    tokenDAO.release(token.getId());
                }
            }
        }
    }

    /** A desired-state revision is a lowercase hex SHA-256 (the column is VARCHAR(64)). */
    private static final java.util.regex.Pattern REVISION_PATTERN = java.util.regex.Pattern.compile("^[0-9a-f]{64}$");

    /** @return the reported revision when well-formed, else null (an oversized value would break the upsert). */
    static String validRevision(String deviceNumber, String reported) {
        if (reported == null) return null;
        if (REVISION_PATTERN.matcher(reported).matches()) return reported;
        logger.debug("Device {} reported a malformed appliedConfigRevision ({} chars) — ignored",
                deviceNumber, reported.length());
        return null;
    }

    // =================================================================================================================
    @ApiOperation(value = "Agent check-in", notes = "Refreshes capabilities, acks results, returns gated commands.")
    @POST
    @Path("/checkin")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response checkin(@HeaderParam("Authorization") String authorization,
                            @javax.ws.rs.core.Context javax.servlet.http.HttpServletRequest httpRequest,
                            AgentCheckInRequest request) {
        if (request == null || request.getDeviceId() == null) {
            return Response.ERROR("error.agent.device.unknown");
        }

        String deviceNumber = request.getDeviceId();
        Device device = unsecureDAO.getDeviceByNumber(deviceNumber);
        // One indistinguishable error for unknown-device AND bad-secret: a distinguishable pair
        // is an enumeration oracle for probing valid device numbers on a public endpoint.
        if (device == null) {
            return Response.ERROR("error.agent.unauthorized");
        }

        // Authenticate the device by its per-device secret BEFORE any state change.
        // The body-supplied deviceId is untrusted until this passes (prevents IDOR/spoofing).
        if (!authenticate(authorization, deviceNumber)) {
            return Response.ERROR("error.agent.unauthorized");
        }

        // Stamp last-seen so the admin UI shows the device online.
        commandDAO.touchLastUpdate(deviceNumber);

        // Backfill the stable hardware id for already-enrolled devices (set once / when it changes).
        if (request.getHardwareId() != null && !request.getHardwareId().trim().isEmpty()
                && !request.getHardwareId().trim().equals(device.getHardwareId())) {
            commandDAO.updateHardwareId(deviceNumber, request.getHardwareId().trim());
        }

        // Refresh the stored capability matrix (kept fresh every check-in). Computed once here and
        // reused for command gating below — no need to read back what we just wrote.
        String capsJson = null;
        if (request.getCapabilities() != null) {
            capsJson = capabilitiesJson(request.getCapabilities());
            commandDAO.updateDeviceCapabilities(deviceNumber, capsJson);
        }

        // Persist the latest device-state snapshot (powers the admin console).
        String appliedRevision = validRevision(deviceNumber,
                request.getState() == null ? null : request.getState().getAppliedConfigRevision());
        if (request.getState() != null) {
            AgentDeviceState s = request.getState();
            DeviceState row = new DeviceState();
            row.setDeviceNumber(deviceNumber);
            row.setBattery(s.getBattery());
            row.setCharging(s.getCharging());
            row.setLocked(s.getLocked());
            row.setKioskActive(s.getKioskActive());
            row.setAndroidRelease(s.getAndroidRelease());
            row.setLastBootAt(s.getLastBootAt());
            row.setAgentVersion(s.getAgentVersion());
            row.setPowerMode(s.getPowerMode());
            row.setAppliedConfigRevision(appliedRevision);
            row.setAppliedConfigAt(null);
            // The server (not the device) knows the public IP — inject it into the census JSON.
            JsonNode tel = request.getTelemetry();
            if (tel != null && tel.isObject()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) tel).put("publicIp", clientIp(httpRequest));
            }
            // The location trail (fixes buffered between check-ins) goes to device_location, not the snapshot.
            JsonNode trail = null;
            JsonNode dyn = tel == null ? null : tel.path("dynamic");
            if (dyn != null && dyn.isObject() && dyn.has("trail")) {
                trail = ((com.fasterxml.jackson.databind.node.ObjectNode) dyn).remove("trail");
            }
            // An authenticated device can still be hostile/buggy: cap the stored blob so a single
            // client can't bloat device_state via the 5s foreground poll.
            String telJson = tel == null ? null : tel.toString();
            if (telJson != null && telJson.length() > MAX_TELEMETRY_CHARS) {
                logger.warn("Device {} telemetry oversized ({} chars) — dropped", deviceNumber, telJson.length());
                telJson = null;
            }
            row.setTelemetry(telJson);
            row.setUpdatedAt(System.currentTimeMillis());
            commandDAO.upsertState(row);
            // Mirror the Android version into the device row (infojson) so the device LIST — which
            // reads infojson->>'androidVersion', not the state table — shows it.
            String androidRelease = s.getAndroidRelease();
            if (androidRelease != null && !androidRelease.trim().isEmpty()) {
                commandDAO.updateAndroidVersion(deviceNumber, androidRelease.trim());
            }
            // Append the buffered trail (oldest first), then the reported location (dynamic.location), to the
            // device's breadcrumb trail. Inserts only keep fixes newer than what is stored, so a resent trail is a no-op.
            recordTrail(deviceNumber, trail);
            recordLocation(deviceNumber, tel);
        }

        // Ingest buffered lifecycle events into the timeline — capped, so one check-in can't
        // flood device_event (each entry is an INSERT with unbounded TEXT detail otherwise).
        if (request.getEvents() != null) {
            int ingested = 0;
            for (com.hmdm.rest.json.agent.AgentTelemetryEvent e : request.getEvents()) {
                if (e == null || e.getType() == null) {
                    continue;
                }
                if (++ingested > MAX_EVENTS_PER_CHECKIN) {
                    logger.warn("Device {} sent >{} events in one check-in — rest dropped",
                            deviceNumber, MAX_EVENTS_PER_CHECKIN);
                    break;
                }
                long ts = e.getTs() == null ? System.currentTimeMillis() : e.getTs();
                String detail = e.getDetail();
                if (detail != null && detail.length() > MAX_EVENT_DETAIL_CHARS) {
                    detail = detail.substring(0, MAX_EVENT_DETAIL_CHARS);
                }
                commandDAO.insertEvent(deviceNumber, e.getType(), ts, detail);
            }
        }

        // Ack results from previously-delivered commands.
        if (request.getResults() != null) {
            for (AgentCommandResult result : request.getResults()) {
                if (result == null || result.getCommandId() == null || result.getStatus() == null) {
                    continue;
                }
                // Allowlist the status so a device can't write arbitrary values into the queue.
                if (!ALLOWED_RESULT_STATUS.contains(result.getStatus())) {
                    continue;
                }
                Integer commandId = parseCommandId(result.getCommandId());
                if (commandId == null) {
                    continue;
                }
                // Ownership is enforced inside the UPDATE's WHERE (id + deviceNumber) — no
                // per-result pre-SELECT needed on the hot path.
                commandDAO.markResultWithTime(deviceNumber, commandId, result.getStatus(),
                        result.getDetail(), System.currentTimeMillis());
            }
        }

        // Lazily expire commands nobody acted on before delivering more. Undelivered (pending)
        // commands age out by creation (60 min — the doze-proof heartbeat ~10 min reaches a parked
        // device well before that). DELIVERED commands age by delivery time with a much longer
        // leash (6 h): the device HAS them — a slow install on metered network must not be
        // expired out from under its own genuine result.
        commandDAO.expireStale(deviceNumber, 60L * 60L * 1000L, 6L * 60L * 60L * 1000L);

        // Gate pending commands by capability-token set membership, reusing the matrix this very
        // request carried (fall back to the stored copy only when the agent omitted it).
        Set<String> deviceTokens = AgentCapabilityTokens.flatten(
                capsJson != null ? capsJson : commandDAO.getDeviceCapabilities(deviceNumber));
        long now = System.currentTimeMillis();

        if (appliedRevision == null && AgentCapabilityTokens.isAllowed(DesiredConfigBuilder.CAPABILITY, deviceTokens)) {
            // The check-in omitted it (state block absent, or invalid value) — fall back to the last
            // stored value so a missing field never masquerades as drift. Only capable agents pay
            // for this read: the value is consumed solely by reconcile, which ignores the others.
            com.hmdm.persistence.domain.DeviceState stored = commandDAO.getState(deviceNumber);
            appliedRevision = stored == null ? null : stored.getAppliedConfigRevision();
        }

        // Desired-state reconciliation: queue config.apply when the device drifted from its configuration, so the
        // command rides THIS response instead of waiting for the next cycle.
        configReconciler.reconcile(device, deviceTokens, appliedRevision, now);

        List<AgentCommand> pending = commandDAO.listPending(deviceNumber);
        List<com.hmdm.rest.json.agent.AgentCommand> commands = new ArrayList<>();
        for (AgentCommand stored : pending) {
            if (!AgentCapabilityTokens.isAllowed(stored.getRequiresCapability(), deviceTokens)) {
                continue;
            }
            // Atomically claim it so a concurrent check-in can't deliver the same command twice.
            if (commandDAO.claimForDelivery(stored.getId(), now)) {
                commands.add(toWire(stored));
            }
        }

        return Response.OK(new AgentCheckInResponse(commands));
    }

    // =================================================================================================================
    /**
     * Gate for the device side of the encrypted remote-control tunnel (ADR 0010): Caddy's {@code forward_auth}
     * calls this before letting a WebSocket on {@code /remote/device/} through to the repeater. Answers with a
     * bare HTTP status (forward_auth reads only the status): 204 when the device's secret checks out and a
     * {@code remote.vnc.start} was queued for it within {@link RemoteSessions#TUNNEL_WINDOW_MILLIS}; 401 otherwise.
     * One status for unknown device and bad secret, as in {@link #checkin}.
     */
    @ApiOperation(value = "Remote tunnel auth", notes = "forward_auth gate for the device's encrypted remote-control tunnel.")
    @GET
    @Path("/remote/tunnel")
    public javax.ws.rs.core.Response remoteTunnelAuth(@HeaderParam("Authorization") String authorization,
                                                      @HeaderParam(REMOTE_DEVICE_HEADER) String deviceNumber) {
        if (deviceNumber == null || deviceNumber.trim().isEmpty()
                || !authenticate(authorization, deviceNumber.trim())) {
            return javax.ws.rs.core.Response.status(javax.ws.rs.core.Response.Status.UNAUTHORIZED).build();
        }
        AgentCommand start = commandDAO.findLatestOfType(deviceNumber.trim(), RemoteSessions.COMMAND_START);
        long now = System.currentTimeMillis();
        if (start == null || start.getCreatedAt() == null || now - start.getCreatedAt() > RemoteSessions.TUNNEL_WINDOW_MILLIS) {
            return javax.ws.rs.core.Response.status(javax.ws.rs.core.Response.Status.FORBIDDEN).build();
        }
        return javax.ws.rs.core.Response.noContent().build();
    }

    /** Header the agent names itself with on the tunnel's WebSocket handshake (the secret is in Authorization). */
    public static final String REMOTE_DEVICE_HEADER = "X-DallyControl-Device";

    /**
     * Verifies the {@code Authorization: Bearer <deviceSecret>} header against the
     * SHA-256 hash stored at enrollment. Constant-time compare; fails closed when the
     * header is missing or the device has no stored secret.
     */
    private boolean authenticate(String authorization, String deviceNumber) {
        String presented = bearer(authorization);
        if (presented == null) {
            return false;
        }
        String expectedHash = commandDAO.getDeviceSecretHash(deviceNumber);
        if (expectedHash == null) {
            return false;
        }
        return CryptoUtil.constantTimeEquals(CryptoUtil.getSHA256String(presented), expectedHash);
    }

    private static String bearer(String authorization) {
        if (authorization == null) {
            return null;
        }
        String s = authorization.trim();
        if (s.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = s.substring(7).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }

    private com.hmdm.rest.json.agent.AgentCommand toWire(AgentCommand stored) {
        com.hmdm.rest.json.agent.AgentCommand wire = new com.hmdm.rest.json.agent.AgentCommand();
        wire.setProtocolVersion(AgentProtocol.VERSION);
        wire.setCommandId(stored.getId() == null ? null : String.valueOf(stored.getId()));
        wire.setType(stored.getType());
        wire.setRequiresCapability(stored.getRequiresCapability());
        if (stored.getCreatedAt() != null) {
            wire.setIssuedAt(String.valueOf(stored.getCreatedAt()));
        }
        wire.setPayload(parsePayload(stored.getPayload()));
        return wire;
    }

    private static JsonNode parsePayload(String payload) {
        if (payload == null || payload.trim().isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readTree(payload);
        } catch (Exception e) {
            return null;
        }
    }

    private static String capabilitiesJson(AgentCapabilities capabilities) {
        if (capabilities == null || capabilities.getTree() == null) {
            return null;
        }
        return capabilities.getTree().toString();
    }

    /** Pull dynamic.location out of the telemetry JSON and append it to the device's trail. */
    /** At most this many trail fixes per check-in (a day at one every 5 minutes). */
    private static final int MAX_TRAIL_FIXES = 288;

    private void recordTrail(String deviceNumber, JsonNode trail) {
        if (trail == null || !trail.isArray()) {
            return;
        }
        java.util.List<JsonNode> fixes = new java.util.ArrayList<>();
        for (JsonNode f : trail) {
            if (f != null && f.hasNonNull("lat") && f.hasNonNull("lon") && f.hasNonNull("capturedAt")) {
                fixes.add(f);
            }
        }
        fixes.sort(java.util.Comparator.comparingLong(f -> f.get("capturedAt").asLong()));
        int from = Math.max(0, fixes.size() - MAX_TRAIL_FIXES);
        for (JsonNode f : fixes.subList(from, fixes.size())) {
            recordFix(deviceNumber, f);
        }
    }

    private void recordLocation(String deviceNumber, JsonNode tel) {
        if (tel == null) {
            return;
        }
        recordFix(deviceNumber, tel.path("dynamic").path("location"));
    }

    private void recordFix(String deviceNumber, JsonNode loc) {
        if (loc.isMissingNode() || loc.isNull() || !loc.hasNonNull("lat") || !loc.hasNonNull("lon")) {
            return;
        }
        double lat = loc.get("lat").asDouble();
        double lon = loc.get("lon").asDouble();
        if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
            return;
        }
        try {
            com.hmdm.persistence.domain.DeviceLocation row = new com.hmdm.persistence.domain.DeviceLocation();
            row.setDeviceNumber(deviceNumber);
            row.setLat(lat);
            row.setLon(lon);
            if (loc.hasNonNull("accuracyM")) {
                row.setAccuracy((float) loc.get("accuracyM").asDouble());
            }
            if (loc.hasNonNull("provider")) {
                row.setProvider(loc.get("provider").asText());
            }
            row.setCapturedAt(loc.hasNonNull("capturedAt") ? loc.get("capturedAt").asLong() : System.currentTimeMillis());
            commandDAO.recordLocation(row);
        } catch (Exception e) {
            logger.warn("Failed to record location for {}: {}", deviceNumber, e.getMessage());
        }
    }

    private static Integer parseCommandId(String commandId) {
        try {
            return Integer.valueOf(commandId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The device's public IP as seen by the server. Forwarding headers are client-controllable,
     * so they're honored ONLY when the direct peer is a local/private address — i.e. our own
     * fronting proxy (Caddy/cloudflared on this host or LAN). A device reaching Tomcat directly
     * cannot forge the recorded publicIp.
     */
    private static String clientIp(javax.servlet.http.HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String remote = request.getRemoteAddr();
        if (isLocalOrPrivate(remote)) {
            String cf = request.getHeader("CF-Connecting-IP");
            if (cf != null && !cf.trim().isEmpty()) {
                return cf.trim();
            }
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.trim().isEmpty()) {
                // First hop is the original client.
                return xff.split(",")[0].trim();
            }
        }
        return remote;
    }

    /** Loopback / RFC1918 / link-local / IPv6 ULA — the address space our own proxy lives in. */
    private static boolean isLocalOrPrivate(String addr) {
        if (addr == null) {
            return false;
        }
        try {
            java.net.InetAddress a = java.net.InetAddress.getByName(addr);
            return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()
                    || (a.getAddress().length == 16 && (a.getAddress()[0] & 0xfe) == 0xfc);
        } catch (Exception e) {
            return false;
        }
    }
}
