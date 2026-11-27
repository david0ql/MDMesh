package com.hmdm.rest.resource.support;

import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.GeofenceDAO;
import com.hmdm.persistence.domain.DeviceLocation;
import com.hmdm.persistence.domain.Geofence;
import com.hmdm.util.GeofenceEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DallyControl — evaluates geofences on the server every time a position is stored.
 *
 * Runs for each fix the check-in actually inserted (the buffered trail first, oldest to newest, then the current
 * position), so a phone that reconnects after hours offline replays its route through the same state machine and
 * the events carry the time of the position, not of the upload. It works with every agent version: the agent only
 * reports positions, the zones never reach the phone.
 *
 * Cost: a device with no zone pays one cached lookup. The zones that apply to a device are cached for a minute and
 * dropped whenever a zone is edited on this node.
 */
@Singleton
public class GeofenceService {
    private static final Logger logger = LoggerFactory.getLogger(GeofenceService.class);

    /** For resources that are not built with it (same pattern as AgentWakeHub.INSTANCE / UserScope.INSTANCE). */
    public static volatile GeofenceService INSTANCE;

    private static final long CACHE_TTL_MS = 60_000L;
    private static final int CACHE_MAX = 50_000;
    /** A state that only moved its lastFixAt is rewritten at most this often. */
    private static final long TOUCH_EVERY_MS = 5 * 60_000L;

    private final GeofenceDAO dao;
    private final AgentCommandDAO commandDAO;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Object[] locks = new Object[64];

    private static final class Cached {
        final long at;
        final List<Geofence> zones;
        final List<GeofenceEvaluator.Zone> parsed;
        final GeofenceEvaluator.Settings settings;

        Cached(long at, List<Geofence> zones, List<GeofenceEvaluator.Zone> parsed, GeofenceEvaluator.Settings settings) {
            this.at = at;
            this.zones = zones;
            this.parsed = parsed;
            this.settings = settings;
        }
    }

    @Inject
    public GeofenceService(GeofenceDAO dao, AgentCommandDAO commandDAO) {
        this.dao = dao;
        this.commandDAO = commandDAO;
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
        INSTANCE = this;
    }

    /** Forget what was cached (a zone, its assignments or the thresholds changed). */
    public void invalidate() {
        cache.clear();
    }

    private Cached zonesOf(String deviceNumber) {
        long now = System.currentTimeMillis();
        Cached c = cache.get(deviceNumber);
        if (c != null && now - c.at < CACHE_TTL_MS) return c;
        List<Geofence> zones = new ArrayList<>();
        List<GeofenceEvaluator.Zone> parsed = new ArrayList<>();
        for (Geofence z : dao.zonesForDevice(deviceNumber)) {
            GeofenceEvaluator.Zone p = z.toZone();
            if (p == null) continue;
            zones.add(z);
            parsed.add(p);
        }
        GeofenceEvaluator.Settings settings = zones.isEmpty() ? null : dao.getSettings(zones.get(0).customerId).evaluator();
        c = new Cached(now, zones, parsed, settings);
        if (cache.size() > CACHE_MAX) cache.clear();
        cache.put(deviceNumber, c);
        return c;
    }

    /** The active zones that apply to this device (inherited from its folders, or assigned to it). */
    public List<Geofence> activeZones(String deviceNumber) {
        try {
            return zonesOf(deviceNumber).zones;
        } catch (Exception e) {
            logger.warn("Zones of {} not read: {}", deviceNumber, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * The shortest position interval (minutes) asked by the device's active zones, or null when none asks for one.
     * The configuration document uses it to raise the phone's reporting frequency while a geofence watches it.
     */
    public Integer trackingMinutes(String deviceNumber) {
        Integer best = null;
        for (Geofence z : activeZones(deviceNumber)) {
            Integer m = z.trackingMinutes;
            if (m != null && m >= 1 && m <= 1440 && (best == null || m < best)) best = m;
        }
        return best;
    }

    /** A position was stored for the device: run it through every zone that applies. Never throws. */
    public void onFix(DeviceLocation fix) {
        if (fix == null || fix.getDeviceNumber() == null) return;
        String number = fix.getDeviceNumber();
        try {
            Cached c = zonesOf(number);
            if (c.zones.isEmpty()) return;
            synchronized (locks[(number.hashCode() & 0x7fffffff) % locks.length]) {
                Map<Integer, GeofenceEvaluator.State> states = dao.statesOfDevice(number);
                for (int i = 0; i < c.zones.size(); i++) {
                    Geofence zone = c.zones.get(i);
                    GeofenceEvaluator.State prev = states.get(zone.id);
                    // Positions arrive oldest first; one older than what this zone already saw would rewind it.
                    if (prev != null && fix.getCapturedAt() <= prev.lastFixAt) continue;
                    GeofenceEvaluator.Outcome o = GeofenceEvaluator.evaluate(c.parsed.get(i), prev, fix.getLat(), fix.getLon(),
                            fix.getAccuracy(), fix.getCapturedAt(), c.settings);
                    if (o.ignoredLowAccuracy) continue;
                    if (changed(prev, o.next)) dao.saveState(zone.id, number, o.next);
                    if (o.event != null) {
                        commandDAO.insertEvent(number, "enter".equals(o.event) ? GeofenceEvaluator.ALERT_ENTER : GeofenceEvaluator.ALERT_EXIT,
                                o.eventAt, eventDetail(zone, o, fix));
                    }
                    if (o.alertType != null) {
                        dao.insertAlert(zone.customerId, number, o.alertType, alertDetail(zone, o, fix), o.alertAt, zone.id);
                        logger.info("Geofence alert {} for {} in zone {} ({})", o.alertType, number, zone.id, zone.name);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Geofence evaluation failed for {}: {}", number, e.getMessage());
        }
    }

    private static boolean changed(GeofenceEvaluator.State prev, GeofenceEvaluator.State next) {
        if (prev == null) return true;
        return !eq(prev.state, next.state) || !eq(prev.pending, next.pending) || prev.pendingCount != next.pendingCount
                || prev.alerted != next.alerted || next.lastFixAt - prev.lastFixAt >= TOUCH_EVERY_MS;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String accuracyText(DeviceLocation fix) {
        return fix.getAccuracy() == null ? "" : " (±" + Math.round(fix.getAccuracy()) + " m)";
    }

    private static String eventDetail(Geofence zone, GeofenceEvaluator.Outcome o, DeviceLocation fix) {
        return ("enter".equals(o.event) ? "Entró a la zona «" : "Salió de la zona «") + zone.name + "»" + accuracyText(fix);
    }

    /** Spanish text of the alert, e.g. "Salió de la zona «Bodega» (±12 m)". */
    static String alertDetail(Geofence zone, GeofenceEvaluator.Outcome o, DeviceLocation fix) {
        boolean keepOut = GeofenceEvaluator.RULE_KEEP_OUT.equals(zone.rule);
        boolean enter = GeofenceEvaluator.ALERT_ENTER.equals(o.alertType);
        String what;
        if (keepOut) {
            what = enter ? "Entró a la zona restringida «" : "Salió de la zona restringida «";
        } else {
            what = enter ? "Regresó a la zona «" : "Salió de la zona permitida «";
        }
        return what + zone.name + "»" + accuracyText(fix);
    }
}
