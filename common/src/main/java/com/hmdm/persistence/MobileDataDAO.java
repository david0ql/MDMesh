/*
 *
 * DallyControl — mobile data per app DAO (new module, not part of upstream Headwind).
 *
 * Thin wrapper over {@link MobileDataMapper}; callers scope by customer / device before calling.
 *
 */

package com.hmdm.persistence;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hmdm.persistence.mapper.MobileDataMapper;

import java.util.List;
import java.util.Map;

/** DAO of a device's mobile-data override and the consumption of the limits' period. */
@Singleton
public class MobileDataDAO {

    private final MobileDataMapper mapper;

    @Inject
    public MobileDataDAO(MobileDataMapper mapper) {
        this.mapper = mapper;
    }

    /** The stored override JSON of a device, or null. */
    public String getOverride(int deviceId) {
        return mapper.getOverride(deviceId);
    }

    /** Store (or, with null, remove) a device's override. */
    public void saveOverride(int deviceId, String settings) {
        if (settings == null) mapper.clearOverride(deviceId);
        else mapper.setOverride(deviceId, settings, System.currentTimeMillis());
    }

    public List<Map<String, Object>> usageByApp(String deviceNumber, String fromDay) {
        return mapper.usageByApp(deviceNumber, fromDay);
    }

    public List<Map<String, Object>> usageOfConfiguration(int customerId, int configurationId, String fromDay) {
        return mapper.usageOfConfiguration(customerId, configurationId, fromDay);
    }

    public List<Map<String, Object>> managedDevices(int customerId) {
        return mapper.managedDevices(customerId);
    }
}
