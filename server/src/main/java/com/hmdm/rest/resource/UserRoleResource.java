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

import com.hmdm.persistence.GroupDAO;
import com.hmdm.persistence.UserDAO;
import com.hmdm.persistence.UserRoleDAO;
import com.hmdm.persistence.domain.Group;
import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.domain.UserRole;
import com.hmdm.persistence.domain.UserRolePermission;
import com.hmdm.rest.json.LookupItem;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.Authorization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.List;
import java.util.stream.Collectors;

@Api(tags = {"UserRole"}, authorizations = {@Authorization("Bearer Token")})
@Singleton
@Path("/private/roles")
public class UserRoleResource {
    private UserRoleDAO userRoleDAO;

    /** Roles the console itself assigns (folder editor 3, observer 100, folder view-only 200): never deleted. */
    private static final java.util.Set<Integer> BUILT_IN_ROLES = new java.util.HashSet<>(java.util.Arrays.asList(1, 2, 3, 100, 200));

    /**
     * <p>A logger to be used for logging the events.</p>
     */
    private static final Logger log = LoggerFactory.getLogger(UserRoleResource.class);

    /**
     * <p>A constructor required by Swagger.</p>
     */
    public UserRoleResource() {
    }

    @Inject
    public UserRoleResource(UserRoleDAO userRoleDAO) {
        this.userRoleDAO = userRoleDAO;
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Get all permissions",
            notes = "Gets the list of all permissions",
            response = UserRolePermission.class,
            responseContainer = "List"
    )
    @GET
    @Path("/permissions")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getPermissions() {
        return Response.OK(this.userRoleDAO.getPermissionsList());
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Get all roles",
            notes = "Get the list of all user roles",
            response = UserRole.class,
            responseContainer = "List"
    )
    @GET
    @Path("/all")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllRoles() {
        return Response.OK(this.userRoleDAO.findAll());
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Create or update user role",
            notes = "Create a new user role (if id is not provided) or update existing one otherwise."
    )
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateRole(UserRole userRole) {
        if (!userRoleDAO.hasAccess()) {
            log.error("Unauthorized attempt to update a user role by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }

        if (userRole == null || userRole.getName() == null || userRole.getName().trim().isEmpty()) {
            return Response.ERROR("El rol necesita un nombre.");
        }
        userRole.setName(userRole.getName().trim());
        if (userRole.getName().length() > 50) {
            return Response.ERROR("El nombre del rol admite hasta 50 caracteres.");
        }
        // The administrator roles always keep full access: editing them could lock every administrator out.
        if (userRole.getId() != null) {
            UserRole current = this.userRoleDAO.findById(userRole.getId());
            if (current == null) {
                return Response.OBJECT_NOT_FOUND_ERROR();
            }
            if (current.isSuperAdmin() || userRoleDAO.isAdminRole(current.getId())) {
                return Response.ERROR("El rol de administrador no se puede modificar.");
            }
        }
        userRole.setSuperAdmin(false);
        // Only permissions that can be handed out (never the super-administrator's own).
        if (userRole.getPermissions() != null) {
            java.util.Set<Integer> assignable = this.userRoleDAO.getPermissionsList().stream()
                    .filter(p -> !p.isSuperAdmin()).map(p -> p.getId()).collect(Collectors.toSet());
            userRole.setPermissions(userRole.getPermissions().stream()
                    .filter(p -> p != null && assignable.contains(p.getId()))
                    .collect(Collectors.toList()));
        }

        UserRole dbUserRole = this.userRoleDAO.findByName(userRole.getName());
        if (dbUserRole != null && !dbUserRole.getId().equals(userRole.getId())) {
            return Response.DUPLICATE_ENTITY("error.duplicate.role");
        } else {
            if (userRole.getId() == null) {
                this.userRoleDAO.insert(userRole);
            } else {
                this.userRoleDAO.update(userRole);
            }
            java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("id", userRole.getId());
            out.put("name", userRole.getName());
            return Response.OK(out);
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Delete user role",
            notes = "Delete an existing user role"
    )
    @DELETE
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response removeUserRole(@PathParam("id") @ApiParam("User role ID") Integer id) {
        if (!userRoleDAO.hasAccess()) {
            log.error("Unauthorized attempt to remove a user role by user " +
                    SecurityContext.get().getCurrentUserName());
            return Response.PERMISSION_DENIED();
        }
        UserRole current = id == null ? null : this.userRoleDAO.findById(id);
        if (current == null) {
            return Response.OBJECT_NOT_FOUND_ERROR();
        }
        if (current.isSuperAdmin() || userRoleDAO.isAdminRole(id) || BUILT_IN_ROLES.contains(id)) {
            return Response.ERROR("Este rol viene con el sistema y no se puede eliminar.");
        }
        int users = this.userRoleDAO.countUsers(id);
        if (users > 0) {
            return Response.ERROR("El rol está asignado a " + users + (users == 1 ? " usuario" : " usuarios")
                    + ": cámbiales el rol antes de eliminarlo.");
        }
        this.userRoleDAO.delete(id);
        return Response.OK();
    }
}
