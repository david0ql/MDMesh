package com.hmdm.persistence.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Folder administrators: the folders a user was given (roots, userFolderRoots) and the folders they reach
 * (userDeviceGroupsAccess: the roots plus every folder below them, kept expanded so Headwind's own device queries
 * filter the same way).
 */
public interface UserScopeMapper {

    @Select({"SELECT groupId FROM userFolderRoots WHERE userId = #{userId} ORDER BY groupId"})
    List<Integer> roots(@Param("userId") int userId);

    @Delete({"DELETE FROM userFolderRoots WHERE userId = #{userId}"})
    void deleteRoots(@Param("userId") int userId);

    @Insert({"INSERT INTO userFolderRoots (userId, groupId) VALUES (#{userId}, #{groupId}) ON CONFLICT DO NOTHING"})
    void insertRoot(@Param("userId") int userId, @Param("groupId") int groupId);

    @Select({"SELECT id FROM users WHERE customerId = #{customerId} AND allDevicesAvailable = FALSE"})
    List<Integer> restrictedUsers(@Param("customerId") int customerId);

    @Select({"SELECT DISTINCT groupId FROM userDeviceGroupsAccess WHERE userId = #{userId}"})
    List<Integer> accessGroups(@Param("userId") int userId);

    @Delete({"DELETE FROM userDeviceGroupsAccess WHERE userId = #{userId}"})
    void deleteAccess(@Param("userId") int userId);

    @Insert({"INSERT INTO userDeviceGroupsAccess (userId, groupId) VALUES (#{userId}, #{groupId})"})
    void insertAccess(@Param("userId") int userId, @Param("groupId") int groupId);

    @Select({"SELECT dg.groupId FROM deviceGroups dg INNER JOIN devices d ON d.id = dg.deviceId " +
            "WHERE d.number = #{number} AND d.customerId = #{customerId}"})
    List<Integer> deviceGroupsByNumber(@Param("customerId") int customerId, @Param("number") String number);

    @Select({"SELECT dg.groupId FROM deviceGroups dg INNER JOIN devices d ON d.id = dg.deviceId " +
            "WHERE d.id = #{id} AND d.customerId = #{customerId}"})
    List<Integer> deviceGroupsById(@Param("customerId") int customerId, @Param("id") int id);

    @Select({"SELECT DISTINCT d.number FROM devices d INNER JOIN deviceGroups dg ON dg.deviceId = d.id " +
            "INNER JOIN userDeviceGroupsAccess a ON a.groupId = dg.groupId WHERE a.userId = #{userId}"})
    List<String> deviceNumbers(@Param("userId") int userId);
}
