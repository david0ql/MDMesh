package com.hmdm.rest.resource;

import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.UserDAO;
import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.domain.UserRole;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.resource.support.UserScope;
import com.hmdm.security.SecurityContext;
import com.hmdm.util.PasswordUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * DallyControl's users: administrators (everything) and folder administrators (only the devices of the folders they
 * are given, with every folder below them — see {@link UserScope}). Managing users needs the {@code manage_users}
 * permission. A full administrator manages every user; a folder editor who holds {@code manage_users} manages only
 * users whose folders are a subset of their own and may assign only their own folders — never a full administrator
 * and never a user or folder outside their reach. The folder isolation is enforced here on every call.
 *
 * <p>Roles: besides the three built-in kinds (administrator, folder editor, folder view-only) a user can be given any
 * custom role ({@code role: "custom"} + {@code roleId}), limited to some folders or — only when an administrator
 * creates it — to the whole fleet. Nobody can hand out more than they hold: a manager who is not an administrator
 * may only assign roles whose permissions are a subset of their own, and may not edit or delete a user whose role
 * exceeds theirs.</p>
 */
@Singleton
@Path("/private/dc/users")
@Api(tags = {"DallyControl users"})
public class DcUserResource {
    private static final Logger log = LoggerFactory.getLogger(DcUserResource.class);
    private static final Pattern LOGIN = Pattern.compile("^[A-Za-z0-9._@-]{3,60}$");
    private static final Pattern MD5 = Pattern.compile("^[0-9A-Fa-f]{32}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private UserDAO userDAO;
    private UnsecureDAO unsecureDAO;
    private AgentCommandDAO commandDAO;
    private UserScope scope;
    /** The built-in roles, by id (their names differ between a fresh database and a seeded one). */
    private int adminRoleId = 2;
    static final int EDITOR_ROLE_ID = 3;
    static final int VIEWER_ROLE_ID = 200;

    public DcUserResource() {
    }

    @Inject
    public DcUserResource(UserDAO userDAO, UnsecureDAO unsecureDAO, AgentCommandDAO commandDAO, UserScope scope) {
        this.userDAO = userDAO;
        this.unsecureDAO = unsecureDAO;
        this.commandDAO = commandDAO;
        this.scope = scope;
        this.adminRoleId = userDAO.getOrgAdminRoleId();
    }

    /** {id?, login, name, email, password (MD5, uppercase hex; new users and changes only), role: admin|folders, folders}. */
    public static class UserBody {
        public Integer id;
        public String login;
        public String name;
        public String email;
        public String password;
        public String role;
        /** With {@code role: "custom"}: the role to give (any role that is not the administrator's). */
        public Integer roleId;
        public List<Integer> folders;
    }

    /** A user who may manage console users: anyone holding the {@code manage_users} permission (super-admins too). */
    private Optional<User> manager() {
        Optional<User> u = SecurityContext.get().getCurrentUser();
        if (!u.isPresent() || u.get().getUserRole() == null) return Optional.empty();
        return SecurityContext.get().hasPermission("manage_users") ? u : Optional.empty();
    }

    /** Not limited to folders: sees every user. Whether they may also create administrators is {@link #isAdministrator}. */
    private static boolean isFullAdmin(User u) {
        return !UserScope.isRestricted(u);
    }

    /** A real administrator (super-admin, or the administrator role over the whole fleet): no limit on what they assign. */
    private boolean isAdministrator(User u) {
        if (u == null || u.getUserRole() == null) return false;
        if (u.getUserRole().isSuperAdmin()) return true;
        return !UserScope.isRestricted(u) && u.getUserRole().getId() != null && u.getUserRole().getId() == adminRoleId;
    }

    private static Integer roleIdOf(User u) {
        return u == null || u.getUserRole() == null ? null : u.getUserRole().getId();
    }

    /** Permission names of each role (the user list does not carry them). */
    private Map<Integer, Set<String>> rolePermissions() {
        Map<Integer, Set<String>> out = new java.util.HashMap<>();
        for (UserRole r : userDAO.findAllUserRoles()) {
            Set<String> names = new java.util.HashSet<>();
            if (r.getPermissions() != null) r.getPermissions().forEach(p -> { if (p != null && p.getName() != null) names.add(p.getName()); });
            out.put(r.getId(), names);
        }
        return out;
    }

    /**
     * Whether {@code me} may hand out (or touch a user holding) this role: an administrator always; anyone else only a
     * role that is neither the administrator's nor a super-admin's and whose permissions they all hold themselves.
     */
    private boolean mayAssign(User me, UserRole role, Map<Integer, Set<String>> perms) {
        if (role == null) return false;
        if (isAdministrator(me)) return true;
        if (role.isSuperAdmin() || (role.getId() != null && role.getId() == adminRoleId)) return false;
        Set<String> mine = perms.get(roleIdOf(me));
        Set<String> theirs = perms.get(role.getId());
        return theirs == null || (mine != null && mine.containsAll(theirs));
    }

    /**
     * Whether a folder editor with these reachable folders may act on this target user. True only when the target is
     * itself a folder user whose assigned folders (roots) all fall inside the editor's reach — so the target's whole
     * subtree is a subset of the editor's. A full administrator target is never in a folder editor's scope.
     * {@code myGroups == null} means the caller is a full administrator (everything allowed).
     */
    private boolean withinScope(Set<Integer> myGroups, User target) {
        if (myGroups == null) return true;
        if (!UserScope.isRestricted(target)) return false;
        List<Integer> roots = scope.roots(target.getId());
        if (roots == null || roots.isEmpty()) return false;
        for (Integer r : roots) if (!myGroups.contains(r)) return false;
        return true;
    }

    private Map<String, Object> view(User u, int me) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("login", u.getLogin());
        m.put("name", u.getName());
        m.put("email", u.getEmail());
        boolean folders = UserScope.isRestricted(u);
        String roleName = u.getUserRole() == null ? null : u.getUserRole().getName();
        Integer roleId = roleIdOf(u);
        int rid = roleId == null ? -1 : roleId;
        boolean superAdmin = u.getUserRole() != null && u.getUserRole().isSuperAdmin();
        String kind = !folders && (rid == adminRoleId || superAdmin) ? "admin"
                : folders && rid == VIEWER_ROLE_ID ? "foldersView"
                : folders && rid == EDITOR_ROLE_ID ? "folders" : "custom";
        m.put("role", kind);
        m.put("roleId", roleId);
        m.put("roleName", roleName);
        m.put("allFleet", !folders);
        m.put("folders", folders ? scope.roots(u.getId()) : new ArrayList<Integer>());
        m.put("me", u.getId() != null && u.getId() == me);
        return m;
    }

    @ApiOperation(value = "List users")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response list() {
        Optional<User> me = manager();
        if (!me.isPresent()) return Response.PERMISSION_DENIED();
        Set<Integer> myGroups = isFullAdmin(me.get()) ? null : scope.groups();
        List<Map<String, Object>> out = new ArrayList<>();
        for (User u : userDAO.findAllUsers()) {
            // Folder editors see only users inside their folders (their own account among them).
            if (myGroups != null && !withinScope(myGroups, u)) continue;
            out.add(view(u, me.get().getId()));
        }
        return Response.OK(out);
    }

    @ApiOperation(value = "Roles the caller may assign", notes = "Every role except the administrator's (that one is the "
            + "user type \"admin\"); for a manager who is not an administrator, only the roles whose permissions they hold.")
    @GET
    @Path("/roles")
    @Produces(MediaType.APPLICATION_JSON)
    public Response assignableRoles() {
        Optional<User> me = manager();
        if (!me.isPresent()) return Response.PERMISSION_DENIED();
        Map<Integer, Set<String>> perms = rolePermissions();
        List<Map<String, Object>> out = new ArrayList<>();
        for (UserRole r : userDAO.findAllUserRoles()) {
            if (r.isSuperAdmin() || r.getId() == null || r.getId() == adminRoleId || !mayAssign(me.get(), r, perms)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("name", r.getName());
            m.put("permissions", new java.util.TreeSet<>(perms.getOrDefault(r.getId(), new java.util.HashSet<>())));
            out.add(m);
        }
        out.sort((a, b) -> String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name"))));
        return Response.OK(out);
    }

    @ApiOperation(value = "Create or update a user")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response save(UserBody body) {
        Optional<User> me = manager();
        if (!me.isPresent()) return Response.PERMISSION_DENIED();
        int c = me.get().getCustomerId();
        boolean fullAdmin = isFullAdmin(me.get());
        Set<Integer> myGroups = fullAdmin ? null : scope.groups();
        if (body == null || body.login == null || !LOGIN.matcher(body.login.trim()).matches()) {
            return Response.ERROR("El usuario debe tener de 3 a 60 caracteres: letras, números, punto, guion o @.");
        }
        String login = body.login.trim();
        String email = body.email == null || body.email.trim().isEmpty() ? null : body.email.trim();
        if (email != null && !EMAIL.matcher(email).matches()) return Response.ERROR("El correo no es válido.");
        boolean viewOnly = "foldersView".equals(body.role);
        boolean custom = "custom".equals(body.role);
        boolean administrator = isAdministrator(me.get());
        // A custom role is limited to the folders given; with none it covers the whole fleet (administrators only).
        boolean folders = "folders".equals(body.role) || viewOnly
                || (custom && body.folders != null && !body.folders.isEmpty());
        if (!folders && !custom && !"admin".equals(body.role)) return Response.ERROR("Elige el tipo de usuario.");
        // A folder editor may only create/edit folder users (never full administrators).
        if (!fullAdmin && !folders) return Response.ERROR("No puedes crear ni editar administradores.");
        if (!administrator && !folders) {
            return Response.ERROR(custom ? "Solo un administrador puede dar acceso a toda la flota: elige al menos una carpeta."
                    : "No puedes crear ni editar administradores.");
        }
        List<Integer> roots = new ArrayList<>();
        if (folders) {
            if (body.folders == null || body.folders.isEmpty()) return Response.ERROR("Elige al menos una carpeta.");
            for (Integer g : body.folders) {
                if (g == null || commandDAO.findGroup(c, g) == null) return Response.ERROR("Una de las carpetas ya no existe.");
                // A folder editor may assign only folders inside their own reach.
                if (!fullAdmin && (myGroups == null || !myGroups.contains(g)))
                    return Response.ERROR("Solo puedes asignar carpetas que tú administras.");
                if (!roots.contains(g)) roots.add(g);
            }
        }
        if (body.password != null && !body.password.isEmpty() && !MD5.matcher(body.password).matches()) {
            return Response.ERROR("Contraseña inválida.");
        }
        User sameLogin = unsecureDAO.findByLogin(login);
        if (sameLogin != null && (body.id == null || !sameLogin.getId().equals(body.id))) return Response.ERROR("Ya existe un usuario con ese nombre de usuario.");
        if (email != null) {
            User sameEmail = unsecureDAO.findByEmail(email);
            if (sameEmail != null && (body.id == null || !sameEmail.getId().equals(body.id))) return Response.ERROR("Ya existe un usuario con ese correo.");
        }
        Map<Integer, Set<String>> perms = rolePermissions();
        UserRole role;
        if (custom) {
            role = body.roleId == null ? null : roleById(body.roleId);
            if (role == null) return Response.ERROR("Elige un rol.");
            if (role.getId() == adminRoleId) return Response.ERROR("Para un administrador elige el tipo «Administrador».");
        } else {
            int wanted = folders ? (viewOnly ? VIEWER_ROLE_ID : EDITOR_ROLE_ID) : adminRoleId;
            role = roleById(wanted);
            if (role == null) role = role(folders ? (viewOnly ? "Visor" : "User") : "Admin");
            if (role == null) return Response.ERROR("No se encontró el rol del tipo de usuario elegido.");
        }
        // Nobody hands out more than they hold.
        if (!mayAssign(me.get(), role, perms)) return Response.ERROR("No puedes asignar un rol con más permisos que el tuyo.");

        User u;
        if (body.id == null) {
            if (body.password == null || body.password.isEmpty()) return Response.ERROR("Escribe una contraseña.");
            u = new User();
            u.setCustomerId(c);
        } else {
            u = userDAO.findAllUsers().stream().filter(x -> x.getId().equals(body.id)).findFirst().orElse(null);
            if (u == null) return Response.ERROR("El usuario no existe.");
            // A folder editor may edit only users inside their folders, and not their own account here.
            if (!fullAdmin) {
                if (u.getId().equals(me.get().getId())) return Response.ERROR("No puedes editar tu propio usuario aquí.");
                if (!withinScope(myGroups, u)) return Response.ERROR("Ese usuario no pertenece a tus carpetas.");
            }
            // ...and nobody edits a user whose current role exceeds their own.
            if (!administrator && !u.getId().equals(me.get().getId()) && !mayAssign(me.get(), roleById(roleIdOf(u)), perms)) {
                return Response.ERROR("No puedes editar un usuario con más permisos que tú.");
            }
            boolean staysAdmin = !folders && role.getId() == adminRoleId;
            if (u.getId().equals(me.get().getId()) && isAdministrator(u) && !staysAdmin) return Response.ERROR("No puedes quitarte a ti mismo el rol de administrador.");
            if (isAdministrator(u) && !staysAdmin && admins(c) <= 1) return Response.ERROR("Debe quedar al menos un administrador.");
        }
        u.setLogin(login);
        u.setName(body.name == null || body.name.trim().isEmpty() ? login : body.name.trim());
        u.setEmail(email);
        u.setUserRole(role);
        u.setAllDevicesAvailable(!folders);
        u.setAllConfigAvailable(true);
        u.setGroups(new ArrayList<>());
        if (body.password != null && !body.password.isEmpty()) {
            u.setPassword(PasswordUtil.getHashFromMd5(body.password.toUpperCase()));
            u.setAuthToken(PasswordUtil.generateToken()); // a new password signs the user out elsewhere
            // Admin-assigned password: force the user to set their own on first login. (Not when an admin
            // changes their OWN password here.)
            boolean self = body.id != null && me.get().getId().equals(body.id);
            if (self) {
                u.setPasswordReset(false);
                u.setPasswordResetToken(null);
            } else {
                u.setPasswordReset(true);
                u.setPasswordResetToken(PasswordUtil.generateToken());
            }
        }
        if (body.id == null) {
            userDAO.insert(u);
            if (u.getId() == null) u = unsecureDAO.findByLogin(login);
        } else {
            userDAO.updateUserMainDetails(u);
            if (body.password != null && !body.password.isEmpty()) userDAO.updatePassword(u);
        }
        scope.setRoots(c, u.getId(), roots);
        log.info("User {} saved by {} (role {}, {}{})", login, me.get().getLogin(), role.getName(),
                folders ? "folders " : "whole fleet", folders ? roots : "");
        return Response.OK(view(u, me.get().getId()));
    }

    @ApiOperation(value = "Delete a user")
    @DELETE
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response delete(@PathParam("id") int id) {
        Optional<User> me = manager();
        if (!me.isPresent()) return Response.PERMISSION_DENIED();
        if (id == me.get().getId()) return Response.ERROR("No puedes eliminar tu propio usuario.");
        int c = me.get().getCustomerId();
        User u = userDAO.findAllUsers().stream().filter(x -> x.getId() == id).findFirst().orElse(null);
        if (u == null) return Response.ERROR("El usuario no existe.");
        // A folder editor may delete only users inside their folders.
        if (!isFullAdmin(me.get()) && !withinScope(scope.groups(), u))
            return Response.ERROR("Ese usuario no pertenece a tus carpetas.");
        if (!isAdministrator(me.get()) && !mayAssign(me.get(), roleById(roleIdOf(u)), rolePermissions()))
            return Response.ERROR("No puedes eliminar un usuario con más permisos que tú.");
        if (isAdministrator(u) && admins(c) <= 1) return Response.ERROR("Debe quedar al menos un administrador.");
        userDAO.deleteUser(id);
        log.info("User {} deleted by {}", u.getLogin(), me.get().getLogin());
        return Response.OK();
    }

    private int admins(int customerId) {
        int n = 0;
        for (User u : userDAO.findAllUsers()) if (isAdministrator(u)) n++;
        return n;
    }

    private UserRole roleById(Integer id) {
        if (id == null) return null;
        for (UserRole r : userDAO.findAllUserRoles()) if (id.equals(r.getId()) && !r.isSuperAdmin()) return r;
        return null;
    }

    private UserRole role(String name) {
        for (UserRole r : userDAO.findAllUserRoles()) if (name.equalsIgnoreCase(r.getName()) && !r.isSuperAdmin()) return r;
        return null;
    }
}
