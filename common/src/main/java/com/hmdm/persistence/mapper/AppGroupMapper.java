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

    @Select({"SELECT id, name, description, appIds, updatedAt FROM app_group WHERE customerId = #{customerId} ORDER BY lower(name)"})
    List<Map<String, Object>> list(@Param("customerId") int customerId);

    @Select({"SELECT id, name, description, appIds, updatedAt FROM app_group WHERE customerId = #{customerId} AND id = #{id}"})
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
