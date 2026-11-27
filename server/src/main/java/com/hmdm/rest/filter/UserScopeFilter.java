package com.hmdm.rest.filter;

import com.hmdm.persistence.domain.User;
import com.hmdm.rest.resource.support.UserScope;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Folder administrators (users limited to some folders) reach only an explicit list of console routes; everything
 * else answers 403, so a route added later stays closed to them until it is listed here. Routes about one device or
 * one folder are checked against the user's folders right here; routes whose body names devices or folders (moving
 * devices, bulk commands, enrollment codes…) and lists (devices, folders, the live set) are checked or filtered by their
 * resource through {@link UserScope}. Administrators pass untouched. Runs after authentication.
 */
@Singleton
public class UserScopeFilter implements Filter {

    private static final class Rule {
        final String methods;
        final Pattern path;
        /** "device" = group 1 is a device number; "deviceId" = a device id; "group" = a folder id; null = no check here. */
        final String check;

        Rule(String methods, String path, String check) {
            this.methods = methods;
            this.path = Pattern.compile("^/rest/private" + path + "$");
            this.check = check;
        }
    }

    private static final String ANY = "GET POST PUT DELETE";
    static final List<Rule> RULES = Arrays.asList(
            new Rule("GET PUT", "/users/current", null),
            new Rule(ANY, "/twofactor(/.*)?", null),
            new Rule("POST", "/devices/search", null),                      // Headwind filters by userDeviceGroupsAccess
            new Rule("POST", "/devices/deleteBulk", null),                  // resource checks the ids
            new Rule("PUT", "/devices", null),                              // resource checks the id
            new Rule("POST", "/devices/(\\d+)/description", "deviceId"),
            new Rule(ANY, "/agent/v1/devices/([^/]+)(/.*)?", "device"),
            new Rule("POST", "/agent/v1/bulk/commands", null),              // resource checks the ids
            new Rule("GET", "/agent/v1/live", null),                        // resource filters
            new Rule("GET", "/agent/v1/locations", null),                   // resource filters
            new Rule("GET", "/agent/v1/configurations/syncSummary", null),
            new Rule("GET POST", "/agent/v1/codes", null),                  // resource filters / checks the folder
            new Rule("DELETE", "/agent/v1/codes/(\\d+)", null),             // resource checks the folder
            new Rule("POST", "/agent/v1/token", null),                      // resource checks the folder
            new Rule("GET", "/agent/v1/rollout/active", null),
            new Rule("GET", "/agent/v1/remote/package", null),
            new Rule("GET", "/agent-package", null),
            new Rule("GET", "/fleet/v1/groups", null),                      // resource filters
            new Rule("POST", "/fleet/v1/groups", null),                     // resource checks the parent
            new Rule("PUT DELETE", "/fleet/v1/groups/(\\d+)", "group"),
            new Rule("PUT", "/fleet/v1/groups/(\\d+)/brand", "group"),
            new Rule("PUT", "/fleet/v1/groups/(\\d+)/accountEmail", "group"),
            new Rule("POST", "/fleet/v1/groups/(\\d+)/commands", "group"),
            new Rule("GET", "/fleet/v1/devices/summary", null),             // resource filters
            new Rule("GET", "/fleet/v1/export.xlsx", null),                 // resource filters
            new Rule("GET", "/fleet/v1/devices/(\\d+)/scope", "deviceId"),
            new Rule("POST", "/fleet/v1/devices/group", null),              // resource checks devices + folder
            new Rule("PUT", "/fleet/v1/devices/configuration", null),       // resource checks devices
            new Rule("PUT", "/fleet/v1/devices/accountEmail", null),        // resource checks devices
            new Rule("GET", "/fleet/v1/global", null),
            new Rule("GET", "/configurations/search", null),
            new Rule("GET", "/configurations/(\\d+)", null),
            new Rule("GET", "/configurations/applications/(\\d+)", null),
            new Rule("GET", "/configurations/(\\d+)/history", null),          // policy change history (resource checks access)
            new Rule("PUT", "/configurations", null),                        // create/edit: resource checks add_config / hasConfigurationAccess (theirs only); creator auto-granted
            new Rule("DELETE", "/configurations/(\\d+)", null),              // delete: resource checks copy_config + hasConfigurationAccess
            new Rule("GET", "/agent/v1/configurations/(\\d+)/compliance", null), // non-compliance list (resource scopes)
            new Rule("GET", "/applications(/.*)?", null),
            new Rule("POST", "/applications/configurations", null),          // link apps to a policy (version-0 guard; resource checks access)
            new Rule("POST", "/applications/(\\d+)/remove-from-my-configs", null), // unlink app from the editor's own policies (resource scopes to their configs)
            new Rule("GET", "/reports/v1(/.*)?", null),                      // reports: resource scopes each row to the user's own devices
            new Rule("GET POST", "/alerts/v1(/.*)?", null),                  // alerts: resource scopes each row; review checks the device's folder
            new Rule("GET PUT", "/scripts", null),                          // scripts: list scoped to creator; authoring checks manage_scripts
            new Rule("DELETE", "/scripts/(\\d+)", null),                    // delete script: resource checks manage_scripts
            new Rule("POST", "/scripts/(\\d+)/run", null),                  // run script: resource checks edit_devices + per-device scope
            // Announcements: folder roles may view and send; the resource limits recipients to the user's folders.
            new Rule("GET", "/announcements", null),
            new Rule("GET", "/announcements/(\\d+)", null),
            new Rule("POST", "/announcements", null),                        // resource scopes recipients
            new Rule("POST", "/announcements/(\\d+)/withdraw", null),
            new Rule("POST", "/web-ui-files", null),                         // upload announcement media (resource checks edit_files)
            // Users module for folder editors: the resource (DcUserResource) checks manage_users and limits every
            // user they see/create/edit/delete to a subset of their own folders. Folders come from /fleet/v1/groups
            // (already scoped), so the picker only offers folders they manage.
            new Rule("GET PUT", "/dc/users", null),
            new Rule("DELETE", "/dc/users/(\\d+)", null),
            // Geofences: the resource shows a folder editor only the zones of their folders/devices and checks every
            // folder/device a zone is assigned to; the track history is checked here against the device's folder.
            new Rule("GET", "/geofences/v1/devices/([^/]+)/(track|track/export|zones)", "device"),
            new Rule("GET POST", "/geofences/v1/zones", null),
            new Rule("PUT DELETE", "/geofences/v1/zones/(\\d+)", null),
            new Rule("GET", "/geofences/v1/(states|settings|mail)", null),
            new Rule("GET POST", "/geofences/v1/recipients", null),
            new Rule("PUT DELETE", "/geofences/v1/recipients/(\\d+)", null),
            // Factory-reset protection: a folder editor reads the defaults (the resource filters the folders), sets
            // the accounts of their own folders and sees the status of their own devices.
            new Rule("GET", "/protection/v1/defaults", null),
            new Rule("PUT", "/protection/v1/defaults/groups/(\\d+)", "group"),
            new Rule("GET", "/protection/v1/devices/([^/]+)", "device")
    );

    private final UserScope scope;

    @Inject
    public UserScopeFilter(UserScope scope) {
        this.scope = scope;
    }

    @Override
    public void init(FilterConfig filterConfig) {
    }

    @Override
    public void destroy() {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        Optional<User> user = UserScope.current();
        if (!user.isPresent() || !UserScope.isRestricted(user.get()) || !(request instanceof HttpServletRequest)) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletRequest req = (HttpServletRequest) request;
        String path = req.getRequestURI().substring(req.getContextPath().length()).replaceAll("/+$", "");
        String method = req.getMethod();
        if ("OPTIONS".equals(method) || "HEAD".equals(method)) {
            chain.doFilter(request, response);
            return;
        }
        if (!allowed(method, path, user.get().getCustomerId())) {
            ((HttpServletResponse) response).sendError(403);
            return;
        }
        chain.doFilter(request, response);
    }

    /** Whether a folder administrator may make this request (path without the context path). */
    boolean allowed(String method, String path, int customerId) {
        for (Rule r : RULES) {
            Matcher m = r.path.matcher(path);
            if (!m.matches() || !(" " + r.methods + " ").contains(" " + method + " ")) continue;
            if (r.check == null) return true;
            if ("device".equals(r.check)) return scope.canDeviceNumber(customerId, m.group(1));
            if ("deviceId".equals(r.check)) return scope.canDeviceId(customerId, Integer.parseInt(m.group(1)));
            return scope.canGroup(Integer.parseInt(m.group(1)));
        }
        return false;
    }
}
