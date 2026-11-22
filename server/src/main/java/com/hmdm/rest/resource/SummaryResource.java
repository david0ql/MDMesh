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

package com.hmdm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.persistence.CommonDAO;
import com.hmdm.persistence.DeviceDAO;
import com.hmdm.persistence.domain.DeviceSummaryRequest;
import com.hmdm.persistence.domain.Settings;
import com.hmdm.persistence.domain.SummaryConfigItem;
import com.hmdm.persistence.mapper.DeviceEventMapper;
import com.hmdm.persistence.mapper.DeviceStateMapper;
import com.hmdm.rest.json.ChartItem;
import com.hmdm.rest.json.DashboardSummary;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.SummaryResponse;
import com.hmdm.security.SecurityContext;
import com.hmdm.service.DeviceApplicationsStatus;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Authorization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

@Api(tags = {"Summary"}, authorizations = {@Authorization("Bearer Token")})
@Singleton
@Path("/private/summary")
public class SummaryResource {

    private static final Logger log = LoggerFactory.getLogger(SummaryResource.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Fallbacks when the console parameters have never been set. */
    private static final int DEFAULT_REFRESH_SECONDS = 30;
    private static final int DEFAULT_OFFLINE_DAYS = 1;
    private static final int DEFAULT_SIM_WINDOW_DAYS = 7;
    private static final long DAY_MS = 86400L * 1000L;

    private DeviceDAO deviceDAO;
    private DeviceStateMapper deviceStateMapper;
    private DeviceEventMapper deviceEventMapper;
    private CommonDAO commonDAO;

    /**
     * <p>A constructor required by Swagger.</p>
     */
    public SummaryResource() {
    }

    @Inject
    public SummaryResource(DeviceDAO deviceDAO, DeviceStateMapper deviceStateMapper,
                           DeviceEventMapper deviceEventMapper, CommonDAO commonDAO) {
        this.deviceDAO = deviceDAO;
        this.deviceStateMapper = deviceStateMapper;
        this.deviceEventMapper = deviceEventMapper;
        this.commonDAO = commonDAO;
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Get device statistics",
            notes = "Get statistics of device enrollment",
            response = SummaryResponse.class
    )
    @GET
    @Path("/devices")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getDeviceStats() {
        SummaryResponse summaryResponse = new SummaryResponse();

        summaryResponse.setInstallSummary(deviceDAO.getInstallSummary());
        if (summaryResponse.getInstallSummary() == null) {
            log.error("Failed to get installation statistics!");
            return Response.INTERNAL_ERROR();
        }

        summaryResponse.setStatusSummary(deviceDAO.getStatusSummary());
        if (summaryResponse.getStatusSummary() == null) {
            log.error("Failed to get device status statistics!");
            return Response.INTERNAL_ERROR();
        }

        summaryResponse.setDevicesTotal(deviceDAO.getTotalDevicesCount());
        summaryResponse.setDevicesEnrolled(deviceDAO.countEnrolled(0l));
        summaryResponse.setDevicesEnrolledLastMonth(deviceDAO.countEnrolled(
                System.currentTimeMillis() - 30 * 86400 * 1000l));

        // Top 5 configs by devices
        DeviceSummaryRequest condition = new DeviceSummaryRequest();
        condition.setMinOnlineTime(0l);
        List<SummaryConfigItem> topConfigs = deviceDAO.getSummaryByConfig(condition, null);

        summaryResponse.setTopConfigs(new LinkedList<>());
        for (SummaryConfigItem summaryConfigItem : topConfigs) {
            summaryResponse.getTopConfigs().add(summaryConfigItem.getName());
        };

        // Offline devices
        long now = System.currentTimeMillis();
        condition.setMaxOnlineTime(now - 3600*1000l);
        List<SummaryConfigItem> offline = deviceDAO.getSummaryByConfig(condition, topConfigs);
        summaryResponse.setStatusOfflineByConfig(new LinkedList<>());
        for (SummaryConfigItem item : offline) {
            summaryResponse.getStatusOfflineByConfig().add(item.getCounter());
        }

        // Idle devices
        condition.setMinOnlineTime(now - 3600*4000l);
        condition.setMaxOnlineTime(now - 3600*1000l);
        List<SummaryConfigItem> idle = deviceDAO.getSummaryByConfig(condition, topConfigs);
        summaryResponse.setStatusIdleByConfig(new LinkedList<>());
        for (SummaryConfigItem item : idle) {
            summaryResponse.getStatusIdleByConfig().add(item.getCounter());
        }

        // Online devices
        condition.setMinOnlineTime(now - 3600*1000l);
        condition.setMaxOnlineTime(null);
        List<SummaryConfigItem> online = deviceDAO.getSummaryByConfig(condition, topConfigs);
        summaryResponse.setStatusOnlineByConfig(new LinkedList<>());
        for (SummaryConfigItem item : online) {
            summaryResponse.getStatusOnlineByConfig().add(item.getCounter());
        }

        // Application status: failed
        condition.setMinOnlineTime(0l);
        condition.setMaxOnlineTime(null);
        condition.setAppStatus(DeviceApplicationsStatus.FAILURE);
        List<SummaryConfigItem> failed = deviceDAO.getSummaryByConfig(condition, topConfigs);
        summaryResponse.setAppFailureByConfig(new LinkedList<>());
        for (SummaryConfigItem item : failed) {
            summaryResponse.getAppFailureByConfig().add(item.getCounter());
        }

        // Application status: mismatch
        condition.setAppStatus(DeviceApplicationsStatus.VERSION_MISMATCH);
        List<SummaryConfigItem> mismatch = deviceDAO.getSummaryByConfig(condition, topConfigs);
        summaryResponse.setAppMismatchByConfig(new LinkedList<>());
        for (SummaryConfigItem item : mismatch) {
            summaryResponse.getAppMismatchByConfig().add(item.getCounter());
        }

        // Application status: success
        condition.setAppStatus(DeviceApplicationsStatus.SUCCESS);
        List<SummaryConfigItem> success = deviceDAO.getSummaryByConfig(condition, topConfigs);
        summaryResponse.setAppSuccessByConfig(new LinkedList<>());
        for (SummaryConfigItem item : success) {
            summaryResponse.getAppSuccessByConfig().add(item.getCounter());
        }

        summaryResponse.setDevicesEnrolledMonthly(deviceDAO.getDevicesEnrolledMonthly());

        return Response.OK(summaryResponse);
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Get dashboard fleet aggregates",
            notes = "Blocked devices, offline count by the configured threshold, recent SIM activity, fleet storage " +
                    "total, Android version spread and app-installation status, plus the console dashboard parameters.",
            response = DashboardSummary.class
    )
    @GET
    @Path("/dashboard")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getDashboard(@QueryParam("simDays") Integer simDays) {
        try {
            Integer customerId = SecurityContext.get().getCurrentUser().map(u -> u.getCustomerId()).orElse(null);
            if (customerId == null) {
                return Response.PERMISSION_DENIED();
            }

            Settings settings = this.commonDAO.getSettings();
            int refreshSeconds = settings != null && settings.getDashboardRefreshSeconds() != null
                    && settings.getDashboardRefreshSeconds() > 0
                    ? settings.getDashboardRefreshSeconds() : DEFAULT_REFRESH_SECONDS;
            int offlineDays = settings != null && settings.getOfflineThresholdDays() != null
                    && settings.getOfflineThresholdDays() > 0
                    ? settings.getOfflineThresholdDays() : DEFAULT_OFFLINE_DAYS;
            int simWindow = simDays != null && simDays > 0 ? simDays : DEFAULT_SIM_WINDOW_DAYS;

            long now = System.currentTimeMillis();

            DashboardSummary out = new DashboardSummary();
            out.setDashboardRefreshSeconds(refreshSeconds);
            out.setOfflineThresholdDays(offlineDays);
            out.setSimWindowDays(simWindow);

            out.setBlocked(this.deviceStateMapper.countLocked(customerId));
            out.setOffline(this.deviceDAO.countOfflineDevices(now - (long) offlineDays * DAY_MS));
            out.setSimEventsRecent(this.deviceEventMapper.countByTypeLike(
                    customerId, "sim%", now - (long) simWindow * DAY_MS));

            // Android version spread.
            List<ChartItem> androidVersions = new LinkedList<>();
            for (Map<String, Object> row : this.deviceStateMapper.countByAndroidRelease(customerId)) {
                ChartItem item = new ChartItem();
                Object label = row.get("label");
                String s = label == null ? "" : String.valueOf(label);
                item.setStringAttr(s.isEmpty() ? "—" : s);
                Object counter = row.get("counter");
                item.setNumber(counter == null ? 0 : ((Number) counter).intValue());
                androidVersions.add(item);
            }
            out.setAndroidVersions(androidVersions);

            // Fleet storage total: sum free/total from each device's last census JSON.
            DashboardSummary.StorageAggregate storage = new DashboardSummary.StorageAggregate();
            long free = 0, total = 0;
            int reporting = 0;
            for (Map<String, Object> row : this.deviceStateMapper.telemetryRows(customerId)) {
                Object tel = row.get("telemetry");
                if (tel == null) continue;
                try {
                    JsonNode node = JSON.readTree(String.valueOf(tel));
                    JsonNode hw = node.path("hardware");
                    JsonNode dyn = node.path("dynamic");
                    Long t = hw.hasNonNull("totalStorageBytes") ? hw.get("totalStorageBytes").asLong() : null;
                    Long f = dyn.hasNonNull("freeStorageBytes") ? dyn.get("freeStorageBytes").asLong() : null;
                    if (t != null && t > 0) {
                        total += t;
                        free += (f != null ? f : 0);
                        reporting++;
                    }
                } catch (Exception ignore) {
                    // Skip devices whose telemetry is absent or malformed.
                }
            }
            storage.setFreeBytes(free);
            storage.setTotalBytes(total);
            storage.setDevicesReporting(reporting);
            out.setStorage(storage);

            // App-installation status (reuse the per-app-status summary already computed for the overview).
            out.setAppStatus(this.deviceDAO.getInstallSummary());

            return Response.OK(out);
        } catch (Exception e) {
            log.error("Failed to compute dashboard summary", e);
            return Response.INTERNAL_ERROR();
        }
    }

}
