/*
 *
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 */

package com.hmdm.rest.json;

import java.util.List;

/**
 * Extra fleet aggregates for the DallyControl console dashboard (Resumen) that cannot be derived from the device list
 * alone: blocked (locked) devices, offline count by the configured threshold, recent SIM activity, the fleet storage
 * total, the Android version spread and the app-installation status — plus the two dashboard parameters so the console
 * can self-configure its refresh and offline cut-off.
 */
public class DashboardSummary {

    /** Devices currently reported as locked (blocked) by their agent. */
    private long blocked;
    /** Devices not seen within {@link #offlineThresholdDays} (the single, configured offline definition). */
    private long offline;
    /** SIM add/remove/swap events across the fleet within {@link #simWindowDays}. */
    private long simEventsRecent;
    /** Window (days) used for {@link #simEventsRecent}. */
    private int simWindowDays;

    /** Console auto-refresh interval, seconds. */
    private int dashboardRefreshSeconds;
    /** Offline threshold, days. */
    private int offlineThresholdDays;

    private StorageAggregate storage;

    /** Android version spread: {@code stringAttr} = release, {@code number} = device count. */
    private List<ChartItem> androidVersions;
    /** App-installation status: SUCCESS / VERSION_MISMATCH / FAILURE counts. */
    private List<ChartItem> appStatus;

    public static class StorageAggregate {
        private long freeBytes;
        private long totalBytes;
        private int devicesReporting;

        public long getFreeBytes() { return freeBytes; }
        public void setFreeBytes(long freeBytes) { this.freeBytes = freeBytes; }
        public long getTotalBytes() { return totalBytes; }
        public void setTotalBytes(long totalBytes) { this.totalBytes = totalBytes; }
        public int getDevicesReporting() { return devicesReporting; }
        public void setDevicesReporting(int devicesReporting) { this.devicesReporting = devicesReporting; }
    }

    public long getBlocked() { return blocked; }
    public void setBlocked(long blocked) { this.blocked = blocked; }

    public long getOffline() { return offline; }
    public void setOffline(long offline) { this.offline = offline; }

    public long getSimEventsRecent() { return simEventsRecent; }
    public void setSimEventsRecent(long simEventsRecent) { this.simEventsRecent = simEventsRecent; }

    public int getSimWindowDays() { return simWindowDays; }
    public void setSimWindowDays(int simWindowDays) { this.simWindowDays = simWindowDays; }

    public int getDashboardRefreshSeconds() { return dashboardRefreshSeconds; }
    public void setDashboardRefreshSeconds(int dashboardRefreshSeconds) { this.dashboardRefreshSeconds = dashboardRefreshSeconds; }

    public int getOfflineThresholdDays() { return offlineThresholdDays; }
    public void setOfflineThresholdDays(int offlineThresholdDays) { this.offlineThresholdDays = offlineThresholdDays; }

    public StorageAggregate getStorage() { return storage; }
    public void setStorage(StorageAggregate storage) { this.storage = storage; }

    public List<ChartItem> getAndroidVersions() { return androidVersions; }
    public void setAndroidVersions(List<ChartItem> androidVersions) { this.androidVersions = androidVersions; }

    public List<ChartItem> getAppStatus() { return appStatus; }
    public void setAppStatus(List<ChartItem> appStatus) { this.appStatus = appStatus; }
}
