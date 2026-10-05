package com.hmdm.persistence.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** Names customers give their library versions (dc_version_label), and which policies use each version. */
public interface DcVersionMapper {

    @Select({"SELECT v.id, v.version, v.versionCode, v.url, v.parts, l.label FROM applicationVersions v " +
            "INNER JOIN applications a ON a.id = v.applicationId " +
            "LEFT JOIN dc_version_label l ON l.versionId = v.id " +
            "WHERE v.applicationId = #{applicationId} AND a.customerId = #{customerId} ORDER BY v.versionCode DESC, v.id DESC"})
    List<Map<String, Object>> versions(@Param("customerId") int customerId, @Param("applicationId") int applicationId);

    @Select({"SELECT ca.applicationVersionId AS versionid, c.id, c.name FROM configurationApplications ca " +
            "INNER JOIN configurations c ON c.id = ca.configurationId " +
            "WHERE ca.applicationId = #{applicationId} AND c.customerId = #{customerId} AND ca.action = 1 ORDER BY c.name"})
    List<Map<String, Object>> policies(@Param("customerId") int customerId, @Param("applicationId") int applicationId);

    @Select({"SELECT l.versionId AS versionid, l.label FROM dc_version_label l " +
            "INNER JOIN applicationVersions v ON v.id = l.versionId INNER JOIN applications a ON a.id = v.applicationId " +
            "WHERE a.customerId = #{customerId}"})
    List<Map<String, Object>> labels(@Param("customerId") int customerId);

    @Select({"SELECT COUNT(*) FROM applicationVersions v INNER JOIN applications a ON a.id = v.applicationId " +
            "WHERE v.id = #{versionId} AND a.customerId = #{customerId}"})
    int owns(@Param("customerId") int customerId, @Param("versionId") int versionId);

    @Insert({"INSERT INTO dc_version_label (versionId, label) VALUES (#{versionId}, #{label}) " +
            "ON CONFLICT (versionId) DO UPDATE SET label = EXCLUDED.label"})
    void setLabel(@Param("versionId") int versionId, @Param("label") String label);

    @Delete({"DELETE FROM dc_version_label WHERE versionId = #{versionId}"})
    void clearLabel(@Param("versionId") int versionId);

    @Select({"SELECT d.applicationId FROM dc_policy_app_downgrade d INNER JOIN configurations c ON c.id = d.configurationId " +
            "WHERE d.configurationId = #{configurationId} AND c.customerId = #{customerId}"})
    List<Integer> downgrades(@Param("customerId") int customerId, @Param("configurationId") int configurationId);

    @Select({"SELECT COUNT(*) FROM dc_policy_app_downgrade WHERE configurationId = #{configurationId} AND applicationId = #{applicationId}"})
    int allowsDowngrade(@Param("configurationId") int configurationId, @Param("applicationId") int applicationId);

    @Select({"SELECT COUNT(*) FROM configurations WHERE id = #{configurationId} AND customerId = #{customerId}"})
    int ownsConfiguration(@Param("customerId") int customerId, @Param("configurationId") int configurationId);

    @Insert({"INSERT INTO dc_policy_app_downgrade (configurationId, applicationId) VALUES (#{configurationId}, #{applicationId}) ON CONFLICT DO NOTHING"})
    void allowDowngrade(@Param("configurationId") int configurationId, @Param("applicationId") int applicationId);

    @Delete({"DELETE FROM dc_policy_app_downgrade WHERE configurationId = #{configurationId} AND applicationId = #{applicationId}"})
    void forbidDowngrade(@Param("configurationId") int configurationId, @Param("applicationId") int applicationId);
}
