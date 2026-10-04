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
            new Rule("POST", "/fleet/v1/groups/(\\d+)/commands", "group"),
            new Rule("GET", "/fleet/v1/devices/summary", null),             // resource filters
            new Rule("GET", "/fleet/v1/devices/(\\d+)/scope", "deviceId"),
            new Rule("POST", "/fleet/v1/devices/group", null),              // resource checks devices + folder
            new Rule("PUT", "/fleet/v1/devices/configuration", null),       // resource checks devices
            new Rule("GET", "/fleet/v1/global", null),
            new Rule("GET", "/configurations/search", null),
            new Rule("GET", "/configurations/(\\d+)", null),
            new Rule("GET", "/configurations/applications/(\\d+)", null),
            new Rule("GET", "/applications(/.*)?", null)
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
