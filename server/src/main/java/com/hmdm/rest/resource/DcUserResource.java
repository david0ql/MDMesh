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
import java.util.regex.Pattern;

/**
 * DallyControl's users: administrators (everything) and folder administrators (only the devices of the folders they
 * are given, with every folder below them — see {@link UserScope}). Only an administrator manages users.
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

    public DcUserResource() {
    }

    @Inject
    public DcUserResource(UserDAO userDAO, UnsecureDAO unsecureDAO, AgentCommandDAO commandDAO, UserScope scope) {
        this.userDAO = userDAO;
        this.unsecureDAO = unsecureDAO;
        this.commandDAO = commandDAO;
        this.scope = scope;
    }

    /** {id?, login, name, email, password (MD5, uppercase hex; new users and changes only), role: admin|folders, folders}. */
    public static class UserBody {
        public Integer id;
        public String login;
        public String name;
        public String email;
        public String password;
        public String role;
        public List<Integer> folders;
    }

    private Optional<User> admin() {
        Optional<User> u = SecurityContext.get().getCurrentUser();
        if (!u.isPresent() || UserScope.isRestricted(u.get())) return Optional.empty();
        if (u.get().getUserRole() == null) return Optional.empty();
        return u.get().getUserRole().isSuperAdmin() || userDAO.isOrgAdmin(u.get()) ? u : Optional.empty();
    }

    private Map<String, Object> view(User u, int me) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("login", u.getLogin());
        m.put("name", u.getName());
        m.put("email", u.getEmail());
        boolean folders = UserScope.isRestricted(u);
        m.put("role", folders ? "folders" : "admin");
        m.put("folders", folders ? scope.roots(u.getId()) : new ArrayList<Integer>());
        m.put("me", u.getId() != null && u.getId() == me);
        return m;
    }

    @ApiOperation(value = "List users")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response list() {
        Optional<User> me = admin();
        if (!me.isPresent()) return Response.PERMISSION_DENIED();
        List<Map<String, Object>> out = new ArrayList<>();
        for (User u : userDAO.findAllUsers()) out.add(view(u, me.get().getId()));
        return Response.OK(out);
    }

    @ApiOperation(value = "Create or update a user")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response save(UserBody body) {
        Optional<User> me = admin();
        if (!me.isPresent()) return Response.PERMISSION_DENIED();
        int c = me.get().getCustomerId();
        if (body == null || body.login == null || !LOGIN.matcher(body.login.trim()).matches()) {
            return Response.ERROR("El usuario debe tener de 3 a 60 caracteres: letras, números, punto, guion o @.");
        }
        String login = body.login.trim();
        String email = body.email == null || body.email.trim().isEmpty() ? null : body.email.trim();
        if (email != null && !EMAIL.matcher(email).matches()) return Response.ERROR("El correo no es válido.");
        boolean folders = "folders".equals(body.role);
        if (!folders && !"admin".equals(body.role)) return Response.ERROR("Elige el tipo de usuario.");
        List<Integer> roots = new ArrayList<>();
        if (folders) {
            if (body.folders == null || body.folders.isEmpty()) return Response.ERROR("Elige al menos una carpeta.");
            for (Integer g : body.folders) {
                if (g == null || commandDAO.findGroup(c, g) == null) return Response.ERROR("Una de las carpetas ya no existe.");
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
        UserRole role = role(folders ? "User" : "Admin");
        if (role == null) return Response.ERROR("No se encontró el rol " + (folders ? "User" : "Admin") + ".");

        User u;
        if (body.id == null) {
            if (body.password == null || body.password.isEmpty()) return Response.ERROR("Escribe una contraseña.");
            u = new User();
            u.setCustomerId(c);
        } else {
            u = userDAO.findAllUsers().stream().filter(x -> x.getId().equals(body.id)).findFirst().orElse(null);
            if (u == null) return Response.ERROR("El usuario no existe.");
            if (u.getId().equals(me.get().getId()) && folders) return Response.ERROR("No puedes quitarte a ti mismo el rol de administrador.");
            if (!folders || !UserScope.isRestricted(u)) { /* fine */ }
            if (folders && !UserScope.isRestricted(u) && admins(c) <= 1) return Response.ERROR("Debe quedar al menos un administrador.");
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
            u.setPasswordReset(false);
            u.setPasswordResetToken(null);
        }
        if (body.id == null) {
            userDAO.insert(u);
            if (u.getId() == null) u = unsecureDAO.findByLogin(login);
        } else {
            userDAO.updateUserMainDetails(u);
            if (body.password != null && !body.password.isEmpty()) userDAO.updatePassword(u);
        }
        scope.setRoots(c, u.getId(), roots);
        log.info("User {} saved by {} ({}{})", login, me.get().getLogin(), folders ? "folders " : "admin", folders ? roots : "");
        return Response.OK(view(u, me.get().getId()));
    }

    @ApiOperation(value = "Delete a user")
    @DELETE
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response delete(@PathParam("id") int id) {
        Optional<User> me = admin();
        if (!me.isPresent()) return Response.PERMISSION_DENIED();
        if (id == me.get().getId()) return Response.ERROR("No puedes eliminar tu propio usuario.");
        int c = me.get().getCustomerId();
        User u = userDAO.findAllUsers().stream().filter(x -> x.getId() == id).findFirst().orElse(null);
        if (u == null) return Response.ERROR("El usuario no existe.");
        if (!UserScope.isRestricted(u) && admins(c) <= 1) return Response.ERROR("Debe quedar al menos un administrador.");
        userDAO.deleteUser(id);
        log.info("User {} deleted by {}", u.getLogin(), me.get().getLogin());
        return Response.OK();
    }

    private int admins(int customerId) {
        int n = 0;
        for (User u : userDAO.findAllUsers()) if (!UserScope.isRestricted(u)) n++;
        return n;
    }

    private UserRole role(String name) {
        for (UserRole r : userDAO.findAllUserRoles()) if (name.equalsIgnoreCase(r.getName()) && !r.isSuperAdmin()) return r;
        return null;
    }
}
