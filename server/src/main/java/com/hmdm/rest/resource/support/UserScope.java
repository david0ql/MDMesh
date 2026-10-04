package com.hmdm.rest.resource.support;

import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.mapper.UserScopeMapper;
import com.hmdm.security.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What the signed-in user may reach. An administrator reaches everything; a folder administrator (a user with
 * allDevicesAvailable = false) only the folders they were given and every folder below them, and the devices in those
 * folders. The expanded set lives in userDeviceGroupsAccess (Headwind's device queries use it too) and is rebuilt from
 * the roots whenever a user or the folder tree changes ({@link #refresh}).
 */
@Singleton
public class UserScope {
    private static final Logger log = LoggerFactory.getLogger(UserScope.class);

    private final UserScopeMapper mapper;
    private final AgentCommandDAO commandDAO;

    /** For Headwind resources that are not built with it (same pattern as AgentWakeHub.INSTANCE). */
    public static volatile UserScope INSTANCE;

    @Inject
    public UserScope(UserScopeMapper mapper, AgentCommandDAO commandDAO) {
        this.mapper = mapper;
        this.commandDAO = commandDAO;
        INSTANCE = this;
    }

    /** Whether the current user may act on these device ids (true for administrators, or when not built yet). */
    public static boolean allowsDevices(java.util.Collection<Integer> ids) {
        UserScope s = INSTANCE;
        if (s == null || !s.restricted()) return true;
        return current().map(u -> s.canDeviceIds(u.getCustomerId(), ids)).orElse(false);
    }

    public static Optional<User> current() {
        SecurityContext ctx = SecurityContext.get();
        return ctx == null ? Optional.empty() : ctx.getCurrentUser();
    }

    /** A folder administrator (limited to their folders). Super-admins and full administrators are not. */
    public static boolean isRestricted(User u) {
        return u != null && !u.isAllDevicesAvailable() && (u.getUserRole() == null || !u.getUserRole().isSuperAdmin());
    }

    public boolean restricted() {
        return current().map(UserScope::isRestricted).orElse(false);
    }

    /** The folders the current user reaches; null = all (not restricted). */
    public Set<Integer> groups() {
        Optional<User> u = current();
        if (!u.isPresent() || !isRestricted(u.get())) return null;
        return new HashSet<>(mapper.accessGroups(u.get().getId()));
    }

    public boolean canGroup(Integer groupId) {
        Set<Integer> g = groups();
        return g == null || (groupId != null && g.contains(groupId));
    }

    public boolean canDeviceNumber(int customerId, String number) {
        Set<Integer> g = groups();
        if (g == null) return true;
        for (Integer id : mapper.deviceGroupsByNumber(customerId, number)) if (g.contains(id)) return true;
        return false;
    }

    public boolean canDeviceId(int customerId, int deviceId) {
        Set<Integer> g = groups();
        if (g == null) return true;
        for (Integer id : mapper.deviceGroupsById(customerId, deviceId)) if (g.contains(id)) return true;
        return false;
    }

    public boolean canDeviceIds(int customerId, Collection<Integer> ids) {
        if (ids == null) return true;
        for (Integer id : ids) if (id == null || !canDeviceId(customerId, id)) return false;
        return true;
    }

    /** Device numbers the current user reaches; null = all. */
    public Set<String> deviceNumbers() {
        Optional<User> u = current();
        if (!u.isPresent() || !isRestricted(u.get())) return null;
        return new HashSet<>(mapper.deviceNumbers(u.get().getId()));
    }

    public List<Integer> roots(int userId) {
        return mapper.roots(userId);
    }

    /** Give a user these root folders (empty = none) and rebuild what they reach. */
    public void setRoots(int customerId, int userId, Collection<Integer> roots) {
        mapper.deleteRoots(userId);
        if (roots != null) for (Integer g : roots) if (g != null) mapper.insertRoot(userId, g);
        expand(customerId, userId);
    }

    /** Rebuild every folder administrator's reach (after folders were created, moved or deleted). */
    public void refresh(int customerId) {
        try {
            for (Integer userId : mapper.restrictedUsers(customerId)) expand(customerId, userId);
        } catch (Exception e) {
            log.warn("Could not refresh folder administrators' access for customer {}", customerId, e);
        }
    }

    private void expand(int customerId, int userId) {
        Set<Integer> all = new LinkedHashSet<>();
        for (Integer root : mapper.roots(userId)) {
            all.addAll(commandDAO.groupSubtree(customerId, root));
        }
        mapper.deleteAccess(userId);
        for (Integer g : all) mapper.insertAccess(userId, g);
    }

    public static Set<Integer> none() {
        return Collections.emptySet();
    }
}
