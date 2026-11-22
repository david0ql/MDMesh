package com.hmdm.persistence.mapper;

import com.hmdm.persistence.domain.DeviceEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** MyBatis mapper for the agent lifecycle event stream ({@link DeviceEvent}). */
public interface DeviceEventMapper {

    @Insert({"INSERT INTO device_event (deviceNumber, type, ts, detail) " +
            "VALUES (#{deviceNumber}, #{type}, #{ts}, #{detail})"})
    void insert(DeviceEvent event);

    @Select({"SELECT id, deviceNumber, type, ts, detail FROM device_event " +
            "WHERE deviceNumber = #{deviceNumber} AND ts >= #{since} ORDER BY ts DESC, id DESC LIMIT #{limit}"})
    List<DeviceEvent> list(@Param("deviceNumber") String deviceNumber,
                           @Param("since") long since, @Param("limit") int limit);

    /**
     * Counts the customer's events whose type matches a SQL LIKE pattern since a timestamp — used by the fleet
     * dashboard for recent SIM activity ({@code type LIKE 'sim%'}).
     */
    @Select({"SELECT COUNT(*) FROM device_event e JOIN devices d ON d.number = e.deviceNumber " +
            "WHERE d.customerId = #{customerId} AND e.type LIKE #{typePattern} AND e.ts >= #{since}"})
    long countByTypeLike(@Param("customerId") int customerId,
                         @Param("typePattern") String typePattern, @Param("since") long since);
}
