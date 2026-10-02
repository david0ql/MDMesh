package com.hmdm.persistence.mapper;

import com.hmdm.persistence.domain.Application;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/** App groups (named sets of library apps) and what policies need to resolve them. */
public interface AppGroupMapper {

    @Select({"SELECT id, name, description, appIds, folders, updatedAt FROM app_group WHERE customerId = #{customerId} ORDER BY lower(name)"})
    List<Map<String, Object>> list(@Param("customerId") int customerId);

    @Select({"SELECT id, name, description, appIds, folders, updatedAt FROM app_group WHERE customerId = #{customerId} AND id = #{id}"})
    Map<String, Object> find(@Param("customerId") int customerId, @Param("id") int id);

    @Insert({"INSERT INTO app_group (customerId, name, description, appIds, updatedAt) " +
            "VALUES (#{g.customerId}, #{g.name}, #{g.description}, #{g.appIds}, #{g.updatedAt})"})
    @Options(useGeneratedKeys = true, keyProperty = "g.id", keyColumn = "id")
    void insert(@Param("g") Map<String, Object> g);

    @Update({"UPDATE app_group SET name = #{name}, description = #{description}, appIds = #{appIds}, updatedAt = #{now} " +
            "WHERE customerId = #{customerId} AND id = #{id}"})
    int update(@Param("customerId") int customerId, @Param("id") int id, @Param("name") String name,
               @Param("description") String description, @Param("appIds") String appIds, @Param("now") long now);

    @Delete({"DELETE FROM app_group WHERE customerId = #{customerId} AND id = #{id}"})
    int delete(@Param("customerId") int customerId, @Param("id") int id);

    @Update({"UPDATE app_group SET folders = #{folders}, updatedAt = #{now} WHERE customerId = #{customerId} AND id = #{id}"})
    int updateFolders(@Param("customerId") int customerId, @Param("id") int id, @Param("folders") String folders, @Param("now") long now);

    /** The device's folder and every folder above it. */
    @Select({"WITH RECURSIVE up(id, parentId, depth) AS (" +
            "SELECT g.id, g.parentId, 0 FROM groups g JOIN deviceGroups dg ON dg.groupId = g.id WHERE dg.deviceId = #{deviceId} " +
            "UNION ALL SELECT g.id, g.parentId, up.depth + 1 FROM groups g JOIN up ON g.id = up.parentId WHERE up.depth < 32) " +
            "SELECT id FROM up"})
    List<Integer> deviceFolderChain(@Param("deviceId") int deviceId);

    @Select({"SELECT id, name, dcPolicy FROM configurations WHERE customerId = #{customerId} ORDER BY lower(name)"})
    List<Map<String, Object>> policies(@Param("customerId") int customerId);

    @Select({"SELECT id, name, dcPolicy FROM configurations WHERE customerId = #{customerId} AND id = #{id}"})
    Map<String, Object> policy(@Param("customerId") int customerId, @Param("id") int id);

    @Update({"UPDATE configurations SET dcPolicy = #{dcPolicy} WHERE customerId = #{customerId} AND id = #{id}"})
    int updatePolicyDc(@Param("customerId") int customerId, @Param("id") int id, @Param("dcPolicy") String dcPolicy);

    /** App ids per group id, for the given groups of the customer. */
    @Select({"<script>SELECT id, appIds FROM app_group WHERE customerId = #{customerId} AND id IN ",
            "<foreach item='g' collection='ids' open='(' separator=',' close=')'>#{g}</foreach></script>"})
    List<Map<String, Object>> appIdsOf(@Param("customerId") int customerId, @Param("ids") List<Integer> ids);

    /** The latest version of each app, shaped like a policy app row (action = install). */
    @Select({"<script>SELECT a.id, a.name, a.pkg, a.type, a.runAfterInstall, a.system, a.customerId, a.latestVersion, ",
            "v.version, v.versionCode, v.url, v.parts, v.split, v.urlArmeabi, v.urlArm64, v.id AS usedVersionId, 1 AS action ",
            "FROM applications a JOIN applicationVersions v ON v.id = a.latestVersion JOIN customers c ON c.id = a.customerId ",
            "WHERE (a.customerId = #{customerId} OR c.master) AND a.id IN ",
            "<foreach item='i' collection='ids' open='(' separator=',' close=')'>#{i}</foreach></script>"})
    List<Application> latestApps(@Param("customerId") int customerId, @Param("ids") List<Integer> ids);

    /** Policies of the customer whose dcPolicy mentions app groups (filtered precisely in Java). */
    @Select({"SELECT id, dcPolicy FROM configurations WHERE customerId = #{customerId} AND dcPolicy LIKE '%appGroups%'"})
    List<Map<String, Object>> policiesWithGroups(@Param("customerId") int customerId);
}
