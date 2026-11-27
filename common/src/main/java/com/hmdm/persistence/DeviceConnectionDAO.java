package com.hmdm.persistence;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hmdm.persistence.domain.DeviceConnection;
import com.hmdm.persistence.mapper.DeviceConnectionMapper;

import java.util.List;
import java.util.Map;

/** Connection diagnostics per device (see {@link DeviceConnection}). */
@Singleton
public class DeviceConnectionDAO {

    private final DeviceConnectionMapper mapper;

    @Inject
    public DeviceConnectionDAO(DeviceConnectionMapper mapper) {
        this.mapper = mapper;
    }

    public void upsert(DeviceConnection row) {
        mapper.upsert(row);
    }

    public DeviceConnection find(String deviceNumber) {
        return mapper.find(deviceNumber);
    }

    public List<Map<String, Object>> listForCustomer(int customerId) {
        return mapper.listForCustomer(customerId);
    }
}
