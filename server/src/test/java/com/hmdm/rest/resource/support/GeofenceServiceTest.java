package com.hmdm.rest.resource.support;

import com.hmdm.persistence.GeofenceDAO;
import com.hmdm.persistence.domain.DeviceLocation;
import com.hmdm.persistence.domain.Geofence;
import com.hmdm.util.GeofenceEvaluator;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The zones of a device are cached; saving or assigning a zone must be seen at once on the same node. */
public class GeofenceServiceTest {

    /** In-memory stand-in for the tables the evaluation touches. */
    private static final class FakeDao extends GeofenceDAO {
        final List<Geofence> zones = new ArrayList<>();
        final Map<Integer, GeofenceEvaluator.State> states = new HashMap<>();
        final List<String> alerts = new ArrayList<>();
        int zoneReads;

        FakeDao() {
            super(null);
        }

        @Override public List<Geofence> zonesForDevice(String deviceNumber) {
            zoneReads++;
            return new ArrayList<>(zones);
        }

        @Override public Settings getSettings(int customerId) {
            return new Settings();
        }

        @Override public Map<Integer, GeofenceEvaluator.State> statesOfDevice(String deviceNumber) {
            return new HashMap<>(states);
        }

        @Override public void saveState(int zoneId, String deviceNumber, GeofenceEvaluator.State s) {
            states.put(zoneId, s);
        }

        @Override public boolean insertAlert(int customerId, String deviceNumber, String type, String detail, long at, int zoneId) {
            alerts.add(type + ":" + detail);
            return true;
        }
    }

    private static Geofence circle(int id, Integer trackingMinutes) {
        Geofence z = new Geofence();
        z.id = id;
        z.customerId = 1;
        z.name = "Bodega";
        z.shape = "circle";
        z.centerLat = 4.7;
        z.centerLon = -74.05;
        z.radiusM = 200.0;
        z.rule = "stayInside";
        z.trackingMinutes = trackingMinutes;
        return z;
    }

    private static DeviceLocation fix(double lat, long at) {
        DeviceLocation f = new DeviceLocation();
        f.setDeviceNumber("dev-1");
        f.setLat(lat);
        f.setLon(-74.05);
        f.setAccuracy(10f);
        f.setCapturedAt(at);
        return f;
    }

    @Test
    public void zonesAreCachedAndInvalidateIsImmediate() {
        FakeDao dao = new FakeDao();
        GeofenceService service = new GeofenceService(dao, null);
        assertTrue(service.activeZones("dev-1").isEmpty());
        assertTrue(service.activeZones("dev-1").isEmpty());
        assertEquals("the second read came from the cache", 1, dao.zoneReads);

        dao.zones.add(circle(7, 3));
        assertTrue("still cached until told", service.activeZones("dev-1").isEmpty());
        service.invalidate();
        assertEquals("a saved zone applies at once", 1, service.activeZones("dev-1").size());
        assertEquals(Integer.valueOf(3), service.trackingMinutes("dev-1"));

        dao.zones.clear();
        service.invalidate();
        assertTrue("and a deleted one stops at once", service.activeZones("dev-1").isEmpty());
    }

    @Test
    public void deviceAlreadyOutsideAlertsWithTheFirstPositionText() {
        FakeDao dao = new FakeDao();
        dao.zones.add(circle(7, null));
        // The device-history event goes through the command DAO: record it next to the alert.
        GeofenceService service = new GeofenceService(dao, new com.hmdm.persistence.AgentCommandDAO(null, null, null, null) {
            @Override public void insertEvent(String deviceNumber, String type, Long ts, String detail) {
                dao.alerts.add("event:" + type + ":" + detail);
            }
        });
        service.onFix(fix(4.7 + 0.005, 1000));   // ~550 m north: outside
        assertTrue("one position is not enough", dao.alerts.isEmpty());
        service.onFix(fix(4.7 + 0.005, 2000));
        assertEquals(2, dao.alerts.size());
        assertEquals("event:geofenceExit:Fuera de la zona «Bodega» desde la primera posición (±10 m)", dao.alerts.get(0));
        assertEquals("geofenceExit:Fuera de la zona permitida «Bodega» desde la primera posición (±10 m)", dao.alerts.get(1));
        assertEquals("outside", dao.states.get(7).state);
        service.onFix(fix(4.7 + 0.005, 3000));
        assertEquals("not repeated", 2, dao.alerts.size());
    }
}
