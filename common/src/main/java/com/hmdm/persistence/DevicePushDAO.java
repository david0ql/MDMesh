package com.hmdm.persistence;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hmdm.persistence.domain.DevicePush;
import com.hmdm.persistence.mapper.DevicePushMapper;

/** Push wake channel per device (see {@link DevicePush}). */
@Singleton
public class DevicePushDAO {

    private final DevicePushMapper mapper;

    @Inject
    public DevicePushDAO(DevicePushMapper mapper) {
        this.mapper = mapper;
    }

    public void upsertReport(DevicePush row) {
        mapper.upsertReport(row);
    }

    public DevicePush find(String deviceNumber) {
        return mapper.find(deviceNumber);
    }

    public void recordSend(String deviceNumber, long at, boolean ok, String error) {
        mapper.recordSend(deviceNumber, at, ok, error);
    }

    /** @return true when this check-in is the answer to a push still waiting for one. */
    public boolean recordAnswer(String deviceNumber, long at) {
        return mapper.recordAnswer(deviceNumber, at) > 0;
    }

    public void dropToken(String deviceNumber, String token, String error) {
        mapper.dropToken(deviceNumber, token, error);
    }

    public int countWithToken(int customerId) {
        return mapper.countWithToken(customerId);
    }
}
