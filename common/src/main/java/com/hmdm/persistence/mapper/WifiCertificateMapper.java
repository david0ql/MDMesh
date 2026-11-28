package com.hmdm.persistence.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * Certificates of enterprise Wi-Fi networks (table {@code wifi_certificate}). {@link #list} never selects the sealed
 * material: only {@link #sealed} does, for the policy document of a phone.
 */
public interface WifiCertificateMapper {

    @Select({"SELECT id, name, kind, subject, issuer, serial, fingerprint, notBefore, notAfter, certCount, createdAt, createdBy " +
            "FROM wifi_certificate WHERE customerId = #{customerId} ORDER BY lower(name), id"})
    List<Map<String, Object>> list(@Param("customerId") int customerId);

    @Select({"SELECT id, kind, sealed FROM wifi_certificate WHERE customerId = #{customerId} AND id = #{id}"})
    Map<String, Object> sealed(@Param("customerId") int customerId, @Param("id") int id);

    @Insert({"INSERT INTO wifi_certificate (customerId, name, kind, subject, issuer, serial, fingerprint, notBefore, notAfter, " +
            "certCount, sealed, createdAt, createdBy) VALUES (#{c.customerId}, #{c.name}, #{c.kind}, #{c.subject}, #{c.issuer}, " +
            "#{c.serial}, #{c.fingerprint}, #{c.notBefore}, #{c.notAfter}, #{c.certCount}, #{c.sealed}, #{c.createdAt}, #{c.createdBy})"})
    @Options(useGeneratedKeys = true, keyProperty = "c.id", keyColumn = "id")
    void insert(@Param("c") Map<String, Object> c);

    @Delete({"DELETE FROM wifi_certificate WHERE customerId = #{customerId} AND id = #{id}"})
    int delete(@Param("customerId") int customerId, @Param("id") int id);

    /** Every policy of the customer that has Wi-Fi networks (to tell which certificates are in use). */
    @Select({"SELECT id, name, dcPolicy FROM configurations WHERE customerId = #{customerId} AND dcPolicy LIKE '%caCertIds%' " +
            "OR customerId = #{customerId} AND dcPolicy LIKE '%clientCertId%' ORDER BY lower(name)"})
    List<Map<String, Object>> policiesWithCertificates(@Param("customerId") int customerId);
}
