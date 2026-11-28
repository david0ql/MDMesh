/*
 *
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hmdm.persistence.mapper;

import com.hmdm.persistence.domain.AgentCommand;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectKey;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * <p>MyBatis mapper for the opaque agent v1 command queue ({@link AgentCommand}). The server never
 * interprets {@code type}/{@code payload}; this mapper only stores and forwards them.</p>
 */
public interface AgentCommandMapper {

    @Insert({"INSERT INTO agentCommand (deviceNumber, type, payload, requiresCapability, status, createdAt, deliveredAt, " +
            "issuerId, issuerName, issuerIp) " +
            "VALUES (#{deviceNumber}, #{type}, #{payload}, #{requiresCapability}, #{status}, #{createdAt}, #{deliveredAt}, " +
            "#{issuerId}, #{issuerName}, #{issuerIp})"})
    @SelectKey(statement = "SELECT currval('agentcommand_id_seq')", keyColumn = "id", keyProperty = "id",
            before = false, resultType = int.class)
    void insert(AgentCommand command);

    /**
     * The phone runs them in this order. Installs of big public apps (Google's, WhatsApp…) go after everything else, so a
     * slow download — or one HyperOS refuses — never holds up the company's own app (same rule as ConfigAppInstaller).
     */
    @Select({"SELECT * FROM agentCommand WHERE deviceNumber = #{deviceNumber} AND status = 'pending' ORDER BY " +
            "CASE WHEN type = 'app.install' AND payload ~ '\"packageName\"\\s*:\\s*\"(com\\.google\\.|com\\.android\\.|com\\.microsoft\\.|com\\.azure\\.|com\\.whatsapp\"|com\\.waze\")' " +
            "THEN 1 ELSE 0 END, id"})
    List<AgentCommand> listPending(@Param("deviceNumber") String deviceNumber);

    @Select({"SELECT * FROM agentCommand WHERE deviceNumber = #{deviceNumber} AND id = #{id}"})
    AgentCommand findByDeviceAndId(@Param("deviceNumber") String deviceNumber, @Param("id") Integer id);

    /**
     * Atomically claim a pending command for delivery. Returns 1 if THIS caller claimed it, 0 if a
     * concurrent check-in already did — so a command is delivered to exactly one check-in.
     */
    @Update({"UPDATE agentCommand SET status = 'delivered', deliveredAt = #{deliveredAt}, deliveries = deliveries + 1 " +
            "WHERE id = #{id} AND status = 'pending'"})
    int claimForDelivery(@Param("id") Integer id, @Param("deliveredAt") Long deliveredAt);

    /**
     * Record a terminal result, but only if the command isn't already GENUINELY terminal — first
     * real result wins; a late/duplicate ack can't overwrite a done/failed (+ its detail). A
     * device-reported result DOES overwrite 'expired': expiry is the server's guess, the device's
     * report is the truth (a slow install may complete after the lazy expiry flipped it).
     * Ownership rides the WHERE (id + deviceNumber) so no pre-SELECT is needed.
     */
    @Update({"UPDATE agentCommand SET status = #{status}, detail = #{detail}, completedAt = #{completedAt} " +
            "WHERE id = #{id} AND deviceNumber = #{deviceNumber} " +
            "AND status NOT IN ('done','failed','unsupported')"})
    void markResultWithTime(@Param("deviceNumber") String deviceNumber, @Param("id") Integer id,
                            @Param("status") String status, @Param("detail") String detail,
                            @Param("completedAt") Long completedAt);

    // --- The queue sweep (see CommandPolicy: how long each type waits and which ones are handed out again) -----------

    /** The device's open commands, without their payloads: what the sweep decides on. */
    @Select({"SELECT id, deviceNumber, type, status, createdAt, deliveredAt, deliveries FROM agentCommand " +
            "WHERE deviceNumber = #{deviceNumber} AND status IN ('pending','delivered') ORDER BY id"})
    List<AgentCommand> listOpen(@Param("deviceNumber") String deviceNumber);

    /** Close an open command as expired (its wait is over, or a newer command replaced it). */
    @Update({"UPDATE agentCommand SET status = 'expired', completedAt = #{now}, detail = COALESCE(#{detail}, detail) " +
            "WHERE id = #{id} AND status IN ('pending','delivered')"})
    int expireOpen(@Param("id") Integer id, @Param("now") long now, @Param("detail") String detail);

    /** A delivered command the device never answered goes back to the queue: the next check-in hands it out again. */
    @Update({"UPDATE agentCommand SET status = 'pending', deliveredAt = NULL WHERE id = #{id} AND status = 'delivered'"})
    int requeueDelivered(@Param("id") Integer id);

    /**
     * Whether a command newer than {@code id}, of one of {@code types} (comma-separated, lower case), already
     * reached the device.
     */
    @Select({"SELECT COUNT(*) FROM agentCommand WHERE deviceNumber = #{deviceNumber} AND id > #{id} " +
            "AND lower(type) = ANY(string_to_array(#{types}, ',')) AND status IN ('delivered','done','failed','unsupported')"})
    int countNewerDelivered(@Param("deviceNumber") String deviceNumber, @Param("id") Integer id,
                            @Param("types") String types);

    /**
     * An administrator withdraws a command that has not run yet. A result the device still reports afterwards wins
     * over the cancellation (see {@link #markResultWithTime}): what the phone did is the truth.
     */
    @Update({"UPDATE agentCommand SET status = 'cancelled', completedAt = #{now}, detail = #{detail} " +
            "WHERE id = #{id} AND deviceNumber = #{deviceNumber} AND status IN ('pending','delivered')"})
    int cancelOpen(@Param("deviceNumber") String deviceNumber, @Param("id") Integer id,
                   @Param("now") long now, @Param("detail") String detail);

    // --- Remote wipe: the device that received it and went silent ---------------------------------------------------

    /**
     * Mark as wiped the customer's devices that received a {@code device.wipe} and have not reported since
     * (a wiped phone cannot answer). The mark carries the delivery time and who sent the wipe.
     */
    @Update({"UPDATE devices d SET wipedAt = c.deliveredAt, wipedBy = c.issuerName FROM agentCommand c " +
            "WHERE c.deviceNumber = d.number AND d.customerId = #{customerId} AND d.wipedAt IS NULL " +
            "AND lower(c.type) = 'device.wipe' AND c.status IN ('delivered','done') AND c.deliveredAt IS NOT NULL " +
            "AND c.deliveredAt < #{silentSince} AND d.lastUpdate <= c.deliveredAt + #{slack}"})
    int markWipedDevices(@Param("customerId") int customerId, @Param("silentSince") long silentSince,
                         @Param("slack") long slack);

    /** The wipe of a device marked as wiped is over: the phone will never answer it. */
    @Update({"UPDATE agentCommand c SET status = 'done', completedAt = c.deliveredAt, detail = #{detail} FROM devices d " +
            "WHERE d.number = c.deviceNumber AND d.customerId = #{customerId} AND d.wipedAt IS NOT NULL " +
            "AND lower(c.type) = 'device.wipe' AND c.status = 'delivered' AND c.deliveredAt <= d.wipedAt"})
    int closeWipesOfWipedDevices(@Param("customerId") int customerId, @Param("detail") String detail);

    /** A device that reports again after its mark was not wiped (or was enrolled again): the mark goes away. */
    @Update({"UPDATE devices SET wipedAt = NULL, wipedBy = NULL WHERE customerId = #{customerId} " +
            "AND wipedAt IS NOT NULL AND lastUpdate > wipedAt + #{slack}"})
    int unmarkReportingDevices(@Param("customerId") int customerId, @Param("slack") long slack);

    @Select({"SELECT number, wipedAt AS wipedat, wipedBy AS wipedby FROM devices " +
            "WHERE customerId = #{customerId} AND wipedAt IS NOT NULL"})
    List<java.util.Map<String, Object>> listWipedDevices(@Param("customerId") int customerId);

    /**
     * A phone that enrolls again must not get the wipe that was meant for its previous life: the delivered one is
     * what wiped it (done), a still pending one is withdrawn.
     */
    @Update({"UPDATE agentCommand SET completedAt = CASE WHEN status = 'delivered' THEN deliveredAt ELSE #{now} END, " +
            "detail = CASE WHEN status = 'delivered' THEN #{doneDetail} ELSE #{cancelDetail} END, " +
            "status = CASE WHEN status = 'delivered' THEN 'done' ELSE 'cancelled' END " +
            "WHERE deviceNumber = #{deviceNumber} AND lower(type) = 'device.wipe' AND status IN ('pending','delivered')"})
    int closeWipesOnReenroll(@Param("deviceNumber") String deviceNumber, @Param("now") long now,
                             @Param("doneDetail") String doneDetail, @Param("cancelDetail") String cancelDetail);

    @Select({"SELECT * FROM agentCommand WHERE deviceNumber = #{deviceNumber} AND createdAt >= #{since} " +
            "ORDER BY id DESC LIMIT #{limit}"})
    List<AgentCommand> listHistory(@Param("deviceNumber") String deviceNumber,
                                   @Param("since") long since, @Param("limit") int limit);

    @Select({"SELECT COUNT(*) FROM agentCommand WHERE deviceNumber = #{deviceNumber} AND type = #{type} " +
            "AND payload = #{payload} AND status IN ('pending','delivered')"})
    int countOpenIdentical(@Param("deviceNumber") String deviceNumber, @Param("type") String type,
                           @Param("payload") String payload);

    /** Commands the device holds but has not answered yet, other than an install of the agent itself. */
    @Select({"SELECT COUNT(*) FROM agentCommand WHERE deviceNumber = #{deviceNumber} AND status = 'delivered' " +
            "AND NOT (type = 'app.install' AND regexp_replace(payload, '\\s', '', 'g') LIKE '%\"packageName\":\"com.dallycontrol.agent%')"})
    int countInFlight(@Param("deviceNumber") String deviceNumber);

    @Select({"SELECT COUNT(*) FROM agentCommand WHERE deviceNumber = #{deviceNumber} AND type = #{type} AND status IN ('pending','delivered')"})
    int countOpenOfType(@Param("deviceNumber") String deviceNumber, @Param("type") String type);

    @Select({"SELECT * FROM agentCommand WHERE deviceNumber = #{deviceNumber} AND type = #{type} ORDER BY id DESC LIMIT 1"})
    AgentCommand findLatestOfType(@Param("deviceNumber") String deviceNumber, @Param("type") String type);

    /**
     * A wipe the device announced ("wiping now": status accepted, device marked by the check-in) and that was never
     * followed by another report is over: it ran. One whose device keeps reporting stays as announced and its mark
     * goes away ({@link #unmarkReportingDevices}).
     */
    @Update({"UPDATE agentCommand c SET status = 'done', detail = #{detail} FROM devices d " +
            "WHERE d.number = c.deviceNumber AND d.customerId = #{customerId} AND d.wipedAt IS NOT NULL " +
            "AND lower(c.type) = 'device.wipe' AND c.status = 'accepted' AND d.wipedAt < #{silentSince} " +
            "AND d.lastUpdate <= d.wipedAt + #{slack}"})
    int closeAnnouncedWipes(@Param("customerId") int customerId, @Param("silentSince") long silentSince,
                            @Param("slack") long slack, @Param("detail") String detail);
}
