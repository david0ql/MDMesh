/*
 *
 * DallyControl — geofences DAO (new module, not part of upstream Headwind). Thin wrapper over GeofenceMapper;
 * everything is scoped by customerId except the check-in path, which starts from an authenticated device number.
 *
 */

package com.hmdm.persistence;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hmdm.persistence.domain.DeviceLocation;
import com.hmdm.persistence.domain.Geofence;
import com.hmdm.persistence.mapper.GeofenceMapper;
import com.hmdm.util.GeofenceEvaluator;
import org.mybatis.guice.transactional.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Singleton
public class GeofenceDAO {

    private final GeofenceMapper mapper;

    @Inject
    public GeofenceDAO(GeofenceMapper mapper) {
        this.mapper = mapper;
    }

    // --- zones ---------------------------------------------------------------------------------------------------

    /** Every zone of the customer with its folder and device assignments. */
    public List<Geofence> listZones(int customerId) {
        List<Geofence> zones = mapper.listZones(customerId);
        Map<Integer, Geofence> byId = new HashMap<>();
        for (Geofence z : zones) {
            z.groupIds = new ArrayList<>();
            z.deviceIds = new ArrayList<>();
            byId.put(z.id, z);
        }
        for (Map<String, Object> r : mapper.listZoneGroups(customerId)) {
            Geofence z = byId.get(intOf(r.get("zoneid")));
            if (z != null) z.groupIds.add(intOf(r.get("groupid")));
        }
        for (Map<String, Object> r : mapper.listZoneDevices(customerId)) {
            Geofence z = byId.get(intOf(r.get("zoneid")));
            if (z != null) z.deviceIds.add(intOf(r.get("deviceid")));
        }
        return zones;
    }

    public Geofence findZone(int customerId, int id) {
        for (Geofence z : listZones(customerId)) if (z.id == id) return z;
        return null;
    }

    public List<Map<String, Object>> listZoneDevices(int customerId) {
        return mapper.listZoneDevices(customerId);
    }

    /** Create (id null) or update a zone and replace its assignments. {@code resetState}: start from a new baseline. */
    @Transactional
    public Geofence saveZone(Geofence z, boolean resetState) {
        long now = System.currentTimeMillis();
        z.updatedAt = now;
        if (z.id == null) {
            z.createdAt = now;
            mapper.insertZone(z);
        } else {
            if (mapper.updateZone(z) == 0) return null;
            if (resetState) mapper.clearStates(z.id);
        }
        mapper.clearZoneGroups(z.id);
        mapper.clearZoneDevices(z.id);
        if (z.groupIds != null) for (Integer g : z.groupIds) if (g != null) mapper.addZoneGroup(z.customerId, z.id, g);
        if (z.deviceIds != null) for (Integer d : z.deviceIds) if (d != null) mapper.addZoneDevice(z.customerId, z.id, d);
        return z;
    }

    public boolean deleteZone(int customerId, int id) {
        return mapper.deleteZone(customerId, id) > 0;
    }

    public List<Geofence> zonesForDevice(String deviceNumber) {
        return mapper.zonesForDevice(deviceNumber);
    }

    // --- state ---------------------------------------------------------------------------------------------------

    public Map<Integer, GeofenceEvaluator.State> statesOfDevice(String deviceNumber) {
        Map<Integer, GeofenceEvaluator.State> out = new HashMap<>();
        for (Map<String, Object> r : mapper.statesOfDevice(deviceNumber)) {
            GeofenceEvaluator.State s = new GeofenceEvaluator.State();
            s.state = (String) r.get("state");
            s.since = longOf(r.get("since"));
            s.pending = (String) r.get("pending");
            s.pendingCount = (int) longOf(r.get("pendingcount"));
            s.pendingSince = longOf(r.get("pendingsince"));
            s.alerted = !Boolean.FALSE.equals(r.get("alerted"));
            s.lastFixAt = longOf(r.get("lastfixat"));
            out.put(intOf(r.get("zoneid")), s);
        }
        return out;
    }

    public void saveState(int zoneId, String deviceNumber, GeofenceEvaluator.State s) {
        mapper.upsertState(zoneId, deviceNumber, s.state, s.since, s.pending, s.pendingCount, s.pendingSince, s.alerted, s.lastFixAt);
    }

    public List<Map<String, Object>> listStates(int customerId) {
        return mapper.listStates(customerId);
    }

    public int purgeOrphanStates() {
        return mapper.purgeOrphanStates();
    }

    // --- alerts --------------------------------------------------------------------------------------------------

    /**
     * Insert a geofence alert at the time of the position. device_alert is unique on (device, type, time): two zones
     * crossed by the same fix would collide, so the time is nudged by a millisecond until it fits.
     */
    public boolean insertAlert(int customerId, String deviceNumber, String type, String detail, long at, int zoneId) {
        for (int i = 0; i < 16; i++) {
            if (mapper.insertAlert(customerId, deviceNumber, type, detail, at + i, zoneId) > 0) return true;
        }
        return false;
    }

    public long maxAlertId(int customerId) {
        return mapper.maxAlertId(customerId);
    }

    public List<Map<String, Object>> alertsAfter(int customerId, long afterId, long since, int limit) {
        return mapper.alertsAfter(customerId, afterId, since, limit);
    }

    // --- settings ------------------------------------------------------------------------------------------------

    /** Per-customer thresholds, with the defaults when none were saved. */
    public static final class Settings {
        public int maxAccuracyM = 100;
        public int hysteresisM = 15;
        public int confirmFixes = 2;
        /** 0 = keep the location history forever. */
        public int retentionDays = 0;
        public int emailIntervalMin = 10;

        public GeofenceEvaluator.Settings evaluator() {
            GeofenceEvaluator.Settings s = new GeofenceEvaluator.Settings();
            s.maxAccuracyM = maxAccuracyM;
            s.hysteresisM = hysteresisM;
            s.confirmFixes = confirmFixes;
            return s;
        }

        /** Keep every value inside its supported range. */
        public Settings clamped() {
            maxAccuracyM = clamp(maxAccuracyM, 5, 5000);
            hysteresisM = clamp(hysteresisM, 0, 1000);
            confirmFixes = clamp(confirmFixes, 1, 10);
            retentionDays = clamp(retentionDays, 0, 3650);
            emailIntervalMin = clamp(emailIntervalMin, 1, 1440);
            return this;
        }

        private static int clamp(int v, int min, int max) {
            return Math.max(min, Math.min(max, v));
        }
    }

    public Settings getSettings(int customerId) {
        Settings s = new Settings();
        Map<String, Object> r = mapper.getSettings(customerId);
        if (r != null) {
            s.maxAccuracyM = (int) longOf(r.get("maxaccuracym"));
            s.hysteresisM = (int) longOf(r.get("hysteresism"));
            s.confirmFixes = (int) longOf(r.get("confirmfixes"));
            s.retentionDays = (int) longOf(r.get("retentiondays"));
            s.emailIntervalMin = (int) longOf(r.get("emailintervalmin"));
        }
        return s.clamped();
    }

    public void saveSettings(int customerId, Settings s) {
        s.clamped();
        mapper.upsertSettings(customerId, s.maxAccuracyM, s.hysteresisM, s.confirmFixes, s.retentionDays, s.emailIntervalMin);
    }

    public List<Map<String, Object>> listRetention() {
        return mapper.listRetention();
    }

    public int purgeLocations(int customerId, long before) {
        return mapper.purgeLocations(customerId, before);
    }

    // --- recipients ----------------------------------------------------------------------------------------------

    public List<Map<String, Object>> listRecipients(int customerId) {
        return mapper.listRecipients(customerId);
    }

    public Map<String, Object> findRecipient(int customerId, int id) {
        return mapper.findRecipient(customerId, id);
    }

    public boolean insertRecipient(int customerId, Integer groupId, String emails, String types, boolean active) {
        return mapper.insertRecipient(customerId, groupId, emails, types, active) > 0;
    }

    public boolean updateRecipient(int customerId, int id, String emails, String types, boolean active) {
        return mapper.updateRecipient(customerId, id, emails, types, active) > 0;
    }

    public boolean deleteRecipient(int customerId, int id) {
        return mapper.deleteRecipient(customerId, id) > 0;
    }

    public List<Integer> customersToNotify() {
        return mapper.customersToNotify();
    }

    public List<Map<String, Object>> listEmailStates(int customerId) {
        return mapper.listEmailStates(customerId);
    }

    public void saveEmailState(int customerId, String email, long lastSentAt, long lastAlertId) {
        mapper.upsertEmailState(customerId, email, lastSentAt, lastAlertId);
    }

    // --- history -------------------------------------------------------------------------------------------------

    public long countTrack(String deviceNumber, long from, long to) {
        return mapper.countTrack(deviceNumber, from, to);
    }

    /** Oldest first. */
    public List<DeviceLocation> listTrack(String deviceNumber, long from, long to, int limit, long offset) {
        return mapper.listTrack(deviceNumber, from, to, limit, offset);
    }

    /** Newest first. */
    public List<DeviceLocation> listTrackDesc(String deviceNumber, long from, long to, int limit, long offset) {
        return mapper.listTrackDesc(deviceNumber, from, to, limit, offset);
    }

    public static Integer intOf(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : null;
    }

    public static long longOf(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }
}
