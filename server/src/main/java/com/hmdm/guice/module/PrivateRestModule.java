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

package com.hmdm.guice.module;

import com.google.inject.Scopes;
import com.google.inject.servlet.ServletModule;
import com.hmdm.rest.filter.PrivateIPFilter;
import com.hmdm.rest.resource.*;
import com.hmdm.rest.filter.AuthFilter;
import com.hmdm.security.jwt.JWTFilter;

public class PrivateRestModule extends ServletModule {
    public PrivateRestModule() {
    }

    protected void configureServlets() {
        this.filter("/rest/private/*").through(JWTFilter.class);
        this.filter("/rest/private/*").through(AuthFilter.class);
        this.filter("/rest/private/*").through(PrivateIPFilter.class);
        // Folder administrators: only their folders' devices and an explicit list of routes (after authentication).
        this.filter("/rest/private/*").through(com.hmdm.rest.filter.UserScopeFilter.class);
        this.bind(DeviceResource.class);
        this.bind(GroupResource.class);
        this.bind(ConfigurationResource.class);
        this.bind(CustomerResource.class);
        this.bind(SettingsResource.class);
        this.bind(ApplicationResource.class);
        this.bind(IconResource.class);
        this.bind(UserResource.class);
        this.bind(FilesResource.class);
        this.bind(IconFileResource.class);
        this.bind(HintResource.class);
        this.bind(UserRoleResource.class);
        this.bind(AgentAdminResource.class);
        this.bind(DcUserResource.class);
        this.bind(DcVersionResource.class);
        this.bind(com.hmdm.rest.resource.FleetResource.class);
        this.bind(com.hmdm.rest.resource.AnnouncementResource.class);
        this.bind(com.hmdm.rest.resource.AgentPackageResource.class);
        this.bind(com.hmdm.rest.resource.AppGroupResource.class);
        this.bind(FDroidResource.class);
        this.bind(com.hmdm.rest.resource.AppInstallResource.class);
        // Eager: its timer (automatic retry of failed installs) must run from boot, not from the first request.
        this.bind(com.hmdm.rest.resource.support.InstallRetrier.class).asEagerSingleton();
        this.bind(com.hmdm.rest.resource.ReportScheduleResource.class);
        // Eager so AgentWakeHub.INSTANCE is set at boot, before any device WebSocket connects.
        this.bind(com.hmdm.notification.AgentWakeHub.class).asEagerSingleton();
        // Geofences: the resource, and the evaluator eager so GeofenceService.INSTANCE exists before the first check-in.
        this.bind(com.hmdm.rest.resource.GeofenceResource.class);
        this.bind(com.hmdm.rest.resource.support.GeofenceService.class).asEagerSingleton();
        this.bind(com.hmdm.rest.resource.support.GeofenceTasks.class).asEagerSingleton();
        // Mobile data per app: the device resource, and the service eager so MobileDataService.INSTANCE exists before
        // the first check-in asks for a device's override.
        this.bind(com.hmdm.rest.resource.MobileDataResource.class);
        this.bind(com.hmdm.rest.resource.support.MobileDataService.class).asEagerSingleton();
        // Audit trail: the read-only API, and the retention purge eager so it schedules itself at boot.
        this.bind(com.hmdm.rest.resource.AuditTrailResource.class);
        this.bind(com.hmdm.audit.AuditPurgeTask.class).asEagerSingleton();
        this.bind(com.hmdm.rest.resource.HardwareResource.class);
        // Certificates of enterprise Wi-Fi networks (uploaded sealed, sent to phones inside their policy).
        this.bind(com.hmdm.rest.resource.WifiCertificateResource.class);
    }
}
