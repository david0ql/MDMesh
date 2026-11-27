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

import javax.inject.Inject;
import javax.inject.Singleton;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import javax.inject.Named;

import com.hmdm.notification.PushService;
import com.hmdm.persistence.*;
import com.hmdm.persistence.domain.*;
import com.hmdm.rest.json.*;
import com.hmdm.rest.resource.support.UserScope;
import com.hmdm.security.SecurityContext;
import com.hmdm.security.SecurityException;
import com.hmdm.util.FileUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.Authorization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.hmdm.util.FileExistsException;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

@Api(tags = {"Application"}, authorizations = {@Authorization("Bearer Token")})
@Singleton
@Path("/private/applications")
public class ApplicationResource {

    // A logging service
    private static final Logger logger  = LoggerFactory.getLogger(ApplicationResource.class);
    private File baseDirectory;
    private ApplicationDAO applicationDAO;
    private ConfigurationDAO configurationDAO;
    private PushService pushService;

    /**
     * <p>A constructor required by Swagger.</p>
     */
    public ApplicationResource() {
    }

    private com.hmdm.persistence.AgentCommandDAO agentCommandDAO;
    private com.hmdm.rest.resource.support.ConfigAppInstaller configAppInstaller;
    private com.hmdm.rest.resource.support.PolicyApps policyApps;
    private UserScope scope;

    @Inject
    public ApplicationResource(ApplicationDAO applicationDAO,
                               ConfigurationDAO configurationDAO,
                               PushService pushService,
                               @Named("files.directory") String filesDirectory,
                               com.hmdm.persistence.AgentCommandDAO agentCommandDAO,
                               com.hmdm.rest.resource.support.ConfigAppInstaller configAppInstaller,
                               com.hmdm.rest.resource.support.PolicyApps policyApps,
                               UserScope scope) {
        this.scope = scope;
        this.policyApps = policyApps;
        this.agentCommandDAO = agentCommandDAO;
        this.configAppInstaller = configAppInstaller;
        this.applicationDAO = applicationDAO;
        this.configurationDAO = configurationDAO;
        this.pushService = pushService;
        this.baseDirectory = new File(filesDirectory);

        if (!this.baseDirectory.exists()) {
            this.baseDirectory.mkdirs();
        }
    }

    @ApiOperation(value = "Look up a Play Store app", notes = "Name and icon of an app on the Play Store, by package name or link.")
    @GET
    @Path("/play")
    @Produces(MediaType.APPLICATION_JSON)
    public Response lookupPlayStore(@javax.ws.rs.QueryParam("q") String q) {
        String pkg = com.hmdm.util.PlayStoreLookup.packageOf(q);
        if (pkg == null) {
            return Response.ERROR("error.play.package.invalid");
        }
        try {
            com.hmdm.util.PlayStoreLookup.Result r = com.hmdm.util.PlayStoreLookup.fetch(pkg);
            if (r.name == null) {
                return Response.ERROR("error.play.not.found");
            }
            java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("packageName", r.packageName);
            out.put("name", r.name);
            out.put("icon", r.icon);
            return Response.OK(out);
        } catch (java.io.FileNotFoundException e) {
            return Response.ERROR("error.play.not.found");
        } catch (Exception e) {
            logger.warn("Play Store lookup failed for {}: {}", pkg, e.getMessage());
            return Response.ERROR("error.play.unreachable");
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Get all applications",
            notes = "Gets the list of all available applications",
            response = Application.class,
            responseContainer = "List"
    )
    @GET
    @Path("/search")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllApplications(@javax.ws.rs.QueryParam("all") Boolean all) {
        if (!SecurityContext.get().hasPermission("applications")) {
            logger.error("Unauthorized attempt to access application list by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        // The folder-scoped library view (Apps page) shows only the apps used by the editor's folders. But adding
        // apps to a policy needs the whole customer library, so the policy editor's picker passes ?all=true to get
        // it — the pick is still safe: they can only link to configurations they have access to.
        if (!Boolean.TRUE.equals(all) && scope != null && scope.restricted()) {
            return Response.OK(this.applicationDAO.getAllApplicationsScoped());
        }
        return Response.OK(this.applicationDAO.getAllApplications());
    }

 // =================================================================================================================
    @ApiOperation(
            value = "Search applications",
            notes = "Search applications meeting the specified filter value",
            response = Application.class,
            responseContainer = "List"
    )
    @GET
    @Path("/search/{value}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response searchApplications(@PathParam("value") @ApiParam("A filter value") String value) {
        if (!SecurityContext.get().hasPermission("applications")) {
            logger.error("Unauthorized attempt to access application list by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        if (scope != null && scope.restricted()) {
            return Response.OK(this.applicationDAO.getAllApplicationsByValueScoped(value));
        }
        return Response.OK(this.applicationDAO.getAllApplicationsByValue(value));
    }

    // =================================================================================================================

    /**
     * <p>Gets the list of application ids/names matching the specified filter for autocompletions.</p>
     *
     * @param filter a filter to be used for filtering the records.
     * @return a response with list of devices matching the specified filter.
     */
    @ApiOperation(value = "Get app ids and names for autocompletions")
    @POST
    @Path("/autocomplete")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getApplicationsForAutocomplete(String filter) {
        try {
            List<LookupItem> applications
                    = this.applicationDAO.getApplicationPkgLookup(filter, 10);
            return Response.OK(applications);
        } catch (Exception e) {
            logger.error("Failed to search the applications due to unexpected error. Filter: {}", filter, e);
            return Response.INTERNAL_ERROR();
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Get application versions",
            notes = "Gets the list of versions for specified application",
            response = ApplicationVersion.class,
            responseContainer = "List"
    )
    @GET
    @Path("/{id}/versions")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllApplicationVersions(@PathParam("id") @ApiParam("Application ID") Integer id) {
        if (!SecurityContext.get().hasPermission("applications")) {
            logger.error("Unauthorized attempt to access application version list by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        try {
            return Response.OK(this.applicationDAO.getApplicationVersions(id));
        } catch (Exception e) {
            logger.error("Failed to retrieve the versions for application #{}", id, e);
            return Response.INTERNAL_ERROR();
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Get application",
            notes = "Gets the details for specified application",
            response = Application.class
    )
    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getApplication(@PathParam("id") @ApiParam("Application ID") Integer id) {
        if (!SecurityContext.get().hasPermission("applications")) {
            logger.error("Unauthorized attempt to access application list by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        try {
            return Response.OK(this.applicationDAO.findById(id));
        } catch (Exception e) {
            logger.error("Failed to retrieve the details for application #{}", id, e);
            return Response.INTERNAL_ERROR();
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Create or update Android application",
            notes = "Create a new Android application (if id is not provided) or update existing one otherwise."
    )
    @Path("/android")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateApplication(Application application) {
        if (!SecurityContext.get().hasPermission("edit_applications")) {
            logger.error("Unauthorized attempt to update application by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        try {
            if (application.getId() == null) {
                final int appId = this.applicationDAO.insertApplication(application);
                application = this.applicationDAO.findById(appId);
                return Response.OK(application);
            } else {
                this.applicationDAO.updateApplication(application);
                if (application.getUrl() != null && application.getLatestVersion() != null && !application.isSplit()) {
                    ApplicationVersion version = applicationDAO.findApplicationVersionById(application.getLatestVersion());
                    if (version != null) {
                        version.setUrl(application.getUrl());
                        applicationDAO.updateApplicationVersion(version);
                        logger.info("Application " + application.getPkg() + " updated to version " + version.getVersion() +
                                ", user " + SecurityContext.get().getCurrentUserName());
                    }
                }
                return Response.OK();
            }
        } catch (DuplicateApplicationException e) {
            logger.error("Failed to create or update application", e);
            return Response.DUPLICATE_APPLICATION();
        } catch (RecentApplicationVersionExistsException e) {
            logger.error("Failed to create or update application", e);
            return Response.RECENT_APPLICATION_VERSION_EXISTS();
        } catch (CommonAppAccessException e) {
            logger.error("Failed to create or update application", e);
            return Response.COMMON_APPLICATION_ACCESS_PROHIBITED();
        } catch (FileExistsException e) {
            logger.error("Failed to create or update application", e);
            return Response.FILE_EXISTS();
        } catch (Exception e) {
            logger.error("Failed to create or update application", e);
            return Response.INTERNAL_ERROR();
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Create or update Web-page application",
            notes = "Create a new Web-page application (if id is not provided) or update existing one otherwise."
    )
    @Path("/web")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateWebApplication(Application application) {
        if (!SecurityContext.get().hasPermission("edit_applications")) {
            logger.error("Unauthorized attempt to update application by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        try {
            if (application.getId() == null) {
                final int appId = this.applicationDAO.insertWebApplication(application);
                application = this.applicationDAO.findById(appId);
                return Response.OK(application);
            } else {
                // TODO : ISV : Handle the scenario for inserting new version for the same package here
                this.applicationDAO.updateWebApplication(application);
                if (application.getUrl() != null && application.getLatestVersion() != null) {
                    ApplicationVersion version = applicationDAO.findApplicationVersionById(application.getLatestVersion());
                    if (version != null) {
                        version.setUrl(application.getUrl());
                        applicationDAO.updateApplicationVersion(version);
                    }
                }

                return Response.OK();
            }
        } catch (DuplicateApplicationException e) {
            logger.error("Failed to create or update application", e);
            return Response.DUPLICATE_APPLICATION();
        } catch (RecentApplicationVersionExistsException e) {
            logger.error("Failed to create or update application", e);
            return Response.RECENT_APPLICATION_VERSION_EXISTS();
        } catch (CommonAppAccessException e) {
            logger.error("Failed to create or update application", e);
            return Response.COMMON_APPLICATION_ACCESS_PROHIBITED();
        } catch (FileExistsException e) {
            logger.error("Failed to create or update application", e);
            return Response.FILE_EXISTS();
        } catch (Exception e) {
            logger.error("Failed to create or update application", e);
            return Response.INTERNAL_ERROR();
        }
    }

    /**
     * DallyControl: configurations always run the latest version of their apps. When the new version is the app's
     * latest, move every configuration using the app to it and queue the install on their devices (the agent skips
     * devices already at that version before downloading). Best-effort: the version itself is already saved.
     */
    private void rollOutLatestVersion(ApplicationVersion version) {
        if (version == null || version.getId() == null || agentCommandDAO == null) {
            return;
        }
        try {
            Application app = applicationDAO.findById(version.getApplicationId());
            if (app == null || !version.getId().equals(app.getLatestVersion())) {
                return;
            }
            // A group of named builds: policies keep the build they were assigned (Apps → the group → Asignar).
            java.util.List<Integer> configs = applicationDAO.isVersionGroup(app.getId())
                    ? new java.util.ArrayList<>()
                    : agentCommandDAO.moveConfigurationsToAppVersion(app.getId(), version.getId());
            int queued = 0;
            for (Integer configurationId : configs) {
                queued += configAppInstaller.enqueueForConfiguration(configurationId);
            }
            // Policies that get the app through an app group (always its latest version).
            if (policyApps != null) {
                java.util.Set<Integer> viaGroups = policyApps.policiesUsing(app.getCustomerId(), policyApps.groupsWithApp(app.getCustomerId(), app.getId()));
                viaGroups.removeAll(configs);
                for (Integer configurationId : viaGroups) queued += configAppInstaller.enqueueForConfiguration(configurationId);
                // …and devices that get it through an app group assigned to their folder.
                queued += configAppInstaller.enqueueForFolders(app.getCustomerId(),
                        policyApps.foldersUsing(app.getCustomerId(), policyApps.groupsWithApp(app.getCustomerId(), app.getId())));
            }
            logger.info("Application {} version {} is the latest: {} configuration(s) moved to it, {} install(s) queued",
                    app.getPkg(), version.getVersion(), configs.size(), queued);
        } catch (Exception e) {
            logger.warn("Could not roll out application version {}", version.getId(), e);
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Create or update application version",
            notes = "Create a new application version (if id is not provided) or update existing one otherwise."
    )
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/versions")
    public Response updateApplicationVersion(ApplicationVersion applicationVersion) {
        if (!SecurityContext.get().hasPermission("edit_application_versions")) {
            logger.error("Unauthorized attempt to update application version by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        try {
            if (applicationVersion.getId() == null) {
                // Here only "url" is coming, we may need to change it to urlArmeabi or urlArm64 if arch is set
                this.applicationDAO.insertApplicationVersion(applicationVersion);
                applicationVersion = this.applicationDAO.findApplicationVersionById(applicationVersion.getId());
                rollOutLatestVersion(applicationVersion);
                return Response.OK(applicationVersion);
            } else {
                logger.info("Application " + applicationVersion.getApplicationId() + " version updated: " + applicationVersion.getVersion() +
                        ", user " + SecurityContext.get().getCurrentUserName());
                this.applicationDAO.updateApplicationVersion(applicationVersion);
                return Response.OK();
            }
        } catch (DuplicateApplicationException e) {
            logger.error("Failed to create or update application version", e);
            return Response.DUPLICATE_APPLICATION();
        } catch (RecentApplicationVersionExistsException e) {
            logger.error("Failed to create or update application version", e);
            return Response.RECENT_APPLICATION_VERSION_EXISTS();
        } catch (ApplicationVersionPackageMismatchException e) {
            logger.error("Failed to create or update application version", e);

            Map<String, String> args = new HashMap<>();
            args.put("expected", e.getExpectedPackageName());
            args.put("actual", e.getActualPackageName());

            return Response.ERROR("error.application.version.pkg.mismatch", args);
        } catch (CommonAppAccessException e) {
            logger.error("Failed to create or update application version", e);
            return Response.COMMON_APPLICATION_ACCESS_PROHIBITED();
        } catch (FileExistsException e) {
            logger.error("Failed to create or update application version", e);
            return Response.FILE_EXISTS();
        } catch (Exception e) {
            logger.error("Failed to create or update application version", e);
            return Response.INTERNAL_ERROR();
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Delete application",
            notes = "Delete an existing application"
    )
    @DELETE
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response removeApplication(@PathParam("id") @ApiParam("Application ID") Integer id) {
        if (!SecurityContext.get().hasPermission("edit_applications")) {
            logger.error("Unauthorized attempt to remove application by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        try {
            Application application = applicationDAO.findById(id);
            if (application != null) {
                logger.info("Application '{}' ({}) is removed by user {}",
                        application.getName(), application.getPkg(), SecurityContext.get().getCurrentUserName());
                this.applicationDAO.removeApplicationById(id, true);
                return Response.OK();
            } else {
                logger.error("Cannot find application by id #{}", id);
                return Response.INTERNAL_ERROR();
            }
        } catch (SecurityException e) {
            logger.error("Prohibited to delete application #{} by current user", id, e);
            return Response.PERMISSION_DENIED();
        } catch (ApplicationReferenceExistsException e) {
            logger.error("Prohibited to delete application #{} as it is still referenced in configurations", id, e);
            return Response.APPLICATION_CONFIG_REFERENCE_EXISTS();
        } catch (Exception e) {
            logger.error("Failed to delete application #{} due to unexpected error", id, e);
            return Response.INTERNAL_ERROR();
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Delete application version",
            notes = "Delete an existing application version"
    )
    @DELETE
    @Path("/versions/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response removeApplicationVersion(@PathParam("id") @ApiParam("Application Version ID") Integer id) {
        if (!SecurityContext.get().hasPermission("edit_application_versions")) {
            logger.error("Unauthorized attempt to delete application version by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        try {
            ApplicationVersion version = applicationDAO.findApplicationVersionById(id);
            if (version != null) {
                Application application = applicationDAO.findById(version.getApplicationId());
                if (application != null) {
                    logger.info("Version {} of application '{}' ({}) is removed by user {}",
                            version.getVersion(), application.getName(), application.getPkg(),
                            SecurityContext.get().getCurrentUserName());
                } else {
                    logger.info("Version {} of unexisting application {} is removed by user {}",
                            version.getVersion(), version.getApplicationId(), SecurityContext.get().getCurrentUserName());
                }
                this.applicationDAO.removeApplicationVersionByIdWithAPKFile(id);
                return Response.OK();
            } else {
                logger.error("Cannot find application version by id #{}", id);
                return Response.INTERNAL_ERROR();
            }
        } catch (SecurityException e) {
            logger.error("Prohibited to delete application version #{} by current user", id, e);
            return Response.PERMISSION_DENIED();
        } catch (ApplicationReferenceExistsException e) {
            logger.error("Prohibited to delete application version #{} as it is still referenced in configurations", id, e);
            return Response.APPLICATION_CONFIG_REFERENCE_EXISTS();
        } catch (Exception e) {
            logger.error("Failed to delete application version #{} due to unexpected error", id, e);
            return Response.INTERNAL_ERROR();
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Get application configurations",
            notes = "Gets the list of configurations using requested application",
            response = ApplicationConfigurationLink.class,
            responseContainer = "List"
    )
    @GET
    @Path("/configurations/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getApplicationConfigurations(@PathParam("id") @ApiParam("Application ID") Integer id) {
        if (!SecurityContext.get().hasPermission("applications")) {
            logger.error("Unauthorized attempt to get application configurations by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        return Response.OK(this.applicationDAO.getApplicationConfigurations(id));
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Get application version configurations",
            notes = "Gets the list of configurations using requested application version",
            response = ApplicationConfigurationLink.class,
            responseContainer = "List"
    )
    @GET
    @Path("/version/{id}/configurations")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getApplicationVersionConfigurations(
            @PathParam("id") @ApiParam("Application Version ID") Integer id
    ) {
        if (!SecurityContext.get().hasPermission("applications")) {
            logger.error("Unauthorized attempt to get application version configurations by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        try {
            return Response.OK(this.applicationDAO.getApplicationVersionConfigurations(id));
        } catch (Exception e) {
            logger.error("Failed to get list of application version configurations", e);
            return Response.INTERNAL_ERROR();
        }
    }

    /**
     * DallyControl guard against the "version 0" trap: an app whose latest version has no installable artifact
     * (a Play Store / on-the-phone placeholder — "Sin APK", versionCode 0, no url/parts) can be linked to a
     * configuration with action=install, but the agent has nothing to download, so it silently never installs.
     * Web-page apps are a shortcut/URL, not an APK, so they are exempt.
     *
     * @return true when the app's latest version carries something the agent can install.
     */
    private boolean isInstallableApp(Application app) {
        if (app == null) {
            return false;
        }
        if (app.isSystem()) {
            return true; // preinstalled on the device: the link allows/enables it (enableSystemApp), no APK to download
        }
        if (app.getType() == ApplicationType.web) {
            return true; // not APK-based; the APK guard does not apply
        }
        if (app.getLatestVersion() == null) {
            return false;
        }
        ApplicationVersion v = applicationDAO.findApplicationVersionById(app.getLatestVersion());
        if (v == null) {
            return false;
        }
        return notBlank(v.getUrl()) || notBlank(v.getParts())
                || notBlank(v.getUrlArmeabi()) || notBlank(v.getUrlArm64());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Update application configurations",
            notes = "Updates the list of configurations using requested application"
    )
    @POST
    @Path("/configurations")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateApplicationConfigurations(LinkConfigurationsToAppRequest request) {
        if (!SecurityContext.get().hasPermission("edit_applications")) {
            logger.error("Unauthorized attempt to update application configurations by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        try {
            User user = SecurityContext.get().getCurrentUser().get();
            if (!user.isAllConfigAvailable()) {
                // Remove all configurations unavailable to user
                request.getConfigurations().removeIf(c ->
                        user.getConfigurations().stream().filter(uc -> uc.getId() == c.getConfigurationId()).findFirst() == null);
            }
            // Avoid access to objects of another customer
            request.getConfigurations().removeIf(c -> {
                // findById will raise a SecurityException if attempting to access an object of another customer
                // So actually this code is a bit redundant, but it guards access to own objects anyway
                Application application = applicationDAO.findById(c.getApplicationId());
                Configuration configuration = configurationDAO.getConfigurationById(c.getConfigurationId());
                return application.getCustomerId() != user.getCustomerId() ||
                       configuration.getCustomerId() != user.getCustomerId();
            });

            // Guard against the "version 0" trap: refuse to link an app for installation (action=1) when its latest
            // version has no installable APK/bundle — otherwise it is assigned but silently never installs. Non-install
            // links (hide=0, remove=2) are always allowed.
            java.util.Set<String> noApk = new java.util.LinkedHashSet<>();
            for (ApplicationConfigurationLink link : request.getConfigurations()) {
                if (link.getAction() != 1) {
                    continue;
                }
                Application app = applicationDAO.findById(link.getApplicationId());
                if (app != null && !isInstallableApp(app)) {
                    noApk.add(app.getName() == null ? app.getPkg() : app.getName());
                }
            }
            if (!noApk.isEmpty()) {
                return Response.ERROR("Estas apps no tienen APK y no se pueden instalar desde una política: "
                        + String.join(", ", noApk)
                        + ". Primero obtén el APK (botón «Obtener APK» en Apps) o despliégalas desde la Play Store.");
            }

            logger.info("Application configurations updated by user " + SecurityContext.get().getCurrentUserName());
            this.applicationDAO.updateApplicationConfigurations(request);

            for (ApplicationConfigurationLink configurationLink : request.getConfigurations()) {
                if (configurationLink.isNotify()) {
                    this.pushService.notifyDevicesOnUpdate(configurationLink.getConfigurationId());
                }
            }

            return Response.OK();
        } catch (Exception e) {
            logger.error("Unexpected error when updating application configurations", e);
            return Response.INTERNAL_ERROR();
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Remove an app from the current user's policies",
            notes = "Unlinks the app from every policy (configuration) the current user can reach. Does NOT delete the " +
                    "app from the library. A folder editor only affects their own folders' policies; an admin affects " +
                    "all of the customer's policies."
    )
    @POST
    @Path("/{appId}/remove-from-my-configs")
    @Produces(MediaType.APPLICATION_JSON)
    public Response removeApplicationFromMyConfigurations(@PathParam("appId") @ApiParam("Application ID") Integer appId) {
        if (!SecurityContext.get().hasPermission("edit_applications")) {
            logger.error("Unauthorized attempt to unlink application from policies by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        try {
            // getAllConfigurations() is already scoped: a folder editor only gets the policies they can reach, an
            // admin gets every policy of the customer. We never fall back to customerId, so a folder editor can only
            // ever unlink the app from their own folders' policies.
            final List<Integer> configIds = new LinkedList<>();
            for (Configuration c : this.configurationDAO.getAllConfigurations()) {
                if (c.getId() != null) {
                    configIds.add(c.getId());
                }
            }
            final int affected = this.applicationDAO.removeApplicationFromConfigurations(appId, configIds);
            logger.info("Application #{} unlinked from {} of the user's policies by user {}",
                    appId, affected, SecurityContext.get().getCurrentUserName());
            Map<String, Object> out = new HashMap<>();
            out.put("affected", affected);
            return Response.OK(out);
        } catch (SecurityException e) {
            logger.error("Prohibited to unlink application #{} from policies by current user", appId, e);
            return Response.PERMISSION_DENIED();
        } catch (Exception e) {
            logger.error("Failed to unlink application #{} from the user's policies", appId, e);
            return Response.INTERNAL_ERROR();
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Update application version configurations",
            notes = "Updates the list of configurations using requested application version"
    )
    @POST
    @Path("/version/configurations")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateApplicationVersionConfigurations(LinkConfigurationsToAppVersionRequest request) {
        if (!SecurityContext.get().hasPermission("edit_application_versions")) {
            logger.error("Unauthorized attempt to update application version configurations by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        try {
            User user = SecurityContext.get().getCurrentUser().get();
            if (!user.isAllConfigAvailable()) {
                // Remove all configurations unavailable to user
                request.getConfigurations().removeIf(c ->
                        user.getConfigurations().stream().filter(uc -> uc.getId() == c.getConfigurationId()).findFirst() == null);
            }
            logger.info("Application version configurations updated by user " +
                    SecurityContext.get().getCurrentUserName());
            this.applicationDAO.updateApplicationVersionConfigurations(request, user);
            for (ApplicationVersionConfigurationLink configurationLink : request.getConfigurations()) {
                if (configurationLink.isNotify()) {
                    this.pushService.notifyDevicesOnUpdate(configurationLink.getConfigurationId());
                }
            }

            return Response.OK();
        } catch (Exception e) {
            logger.error("Unexpected error when updating application configurations", e);
            return Response.INTERNAL_ERROR();
        }
    }

    @ApiOperation(value = "", hidden = true)
    @GET
    @Path("/admin/search")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllAdminApplications() {
        return Response.OK(this.applicationDAO.getAllAdminApplications());
    }

    @ApiOperation(value = "", hidden = true)
    @GET
    @Path("/admin/search/{value}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response searchAdminApplications(@PathParam("value") String value) {
        return Response.OK(this.applicationDAO.getAllAdminApplicationsByValue(value));
    }

    @ApiOperation(value = "", hidden = true)
    @GET
    @Path("/admin/common/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response turnApplicationIntoCommon(@PathParam("id") Integer id) {
        try {
            logger.info("Turn application into common: " + id);
            this.applicationDAO.turnApplicationIntoCommon(id);
            return Response.OK();
        } catch (DuplicateApplicationException e) {
            logger.error("Failed to turn application with ID: {} into common", id, e);
            return Response.DUPLICATE_APPLICATION();
        } catch (Exception e) {
            logger.error("Failed to turn application with ID: {} into common", id, e);
            return Response.INTERNAL_ERROR();
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Validate application package",
            notes = "Validate the application package ID for uniqueness",
            response = Application.class,
            responseContainer = "List"
    )
    @Path("/validatePkg")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response validateApplication(Application application) {
        try {
            final List<Application> sameNameApps = this.applicationDAO.getApplicationsForName(application);
            for (Application app : sameNameApps) {
                if (!app.getPkg().equalsIgnoreCase(application.getPkg())) {
                    return Response.ERROR("error.app.name.exists");
                }
            }
            final List<Application> otherApps = this.applicationDAO.getApplicationsForPackageID(application);
            return Response.OK(otherApps);
        } catch (Exception e) {
            logger.error("Failed to validate application", e);
            return Response.INTERNAL_ERROR();
        }
    }
}
