package com.hmdm.rest.resource.support;

import com.hmdm.persistence.domain.AgentEnrollAttempt;
import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.domain.UserRole;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EnrollAttemptAccessTest {

    private static User user(int roleId, boolean superAdmin, boolean allDevices) {
        UserRole role = new UserRole();
        role.setId(roleId);
        role.setSuperAdmin(superAdmin);
        User u = new User();
        u.setUserRole(role);
        u.setAllDevicesAvailable(allDevices);
        u.setCustomerId(1);
        return u;
    }

    private static final User SUPER = user(1, true, true);
    private static final User ADMIN = user(2, false, true);
    private static final User FOLDER_EDITOR = user(3, false, false);
    private static final User VISOR = user(200, false, false);
    /** A view-only user who was given every device: still not an administrator. */
    private static final User VISOR_ALL_DEVICES = user(200, false, true);

    private static AgentEnrollAttempt row(int id, Integer customerId, Integer groupId, Integer tokenId) {
        AgentEnrollAttempt a = new AgentEnrollAttempt();
        a.setId(id);
        a.setCustomerId(customerId);
        a.setGroupId(groupId);
        a.setTokenId(tokenId);
        return a;
    }

    /**
     * 1: a code of my folder 10 · 2: a code of another folder of my customer · 3: a code of my customer without a
     * folder · 4: no code, nobody's · 5: a code of another customer · 6: no code, but the phone is one of my
     * customer's devices · 7: no code, the phone is another customer's.
     */
    private static final List<AgentEnrollAttempt> ROWS = Arrays.asList(
            row(1, 1, 10, 100), row(2, 1, 20, 101), row(3, 1, null, 102), row(4, null, null, null),
            row(5, 2, 10, 103), row(6, 1, null, null), row(7, 2, null, null));

    private static List<Integer> ids(User u, Set<Integer> groups, int customers) {
        return EnrollAttemptAccess.visible(ROWS, u, 1, groups, EnrollAttemptAccess.seesUnknown(u, customers))
                .stream().map(AgentEnrollAttempt::getId).collect(Collectors.toList());
    }

    @Test
    public void a_full_administrator_sees_the_customers_attempts_and_the_ones_without_a_code() {
        assertTrue(EnrollAttemptAccess.isFullAdmin(ADMIN));
        assertEquals(Arrays.asList(1, 2, 3, 4, 6), ids(ADMIN, null, 1));
        assertEquals(Arrays.asList(1, 2, 3, 4, 6), ids(SUPER, null, 1));
    }

    @Test
    public void a_folder_editor_sees_only_the_attempts_of_their_folders() {
        Set<Integer> mine = new HashSet<>(Collections.singletonList(10));
        assertFalse(EnrollAttemptAccess.isFullAdmin(FOLDER_EDITOR));
        assertFalse(EnrollAttemptAccess.seesUnknown(FOLDER_EDITOR, 1));
        assertEquals(Collections.singletonList(1), ids(FOLDER_EDITOR, mine, 1));
        // An editor of the Admin role who is limited to folders is still a folder editor here.
        assertEquals(Collections.singletonList(1), ids(user(2, false, false), mine, 1));
    }

    @Test
    public void the_view_only_role_never_sees_attempts_without_a_code() {
        Set<Integer> mine = new HashSet<>(Collections.singletonList(20));
        assertFalse(EnrollAttemptAccess.seesUnknown(VISOR, 1));
        assertEquals(Collections.singletonList(2), ids(VISOR, mine, 1));
        // Even with every device available: all of the customer's coded attempts, none of the unknown ones.
        assertFalse(EnrollAttemptAccess.isFullAdmin(VISOR_ALL_DEVICES));
        assertEquals(Arrays.asList(1, 2, 3), ids(VISOR_ALL_DEVICES, null, 1));
    }

    @Test
    public void with_several_customers_only_the_super_administrator_sees_attempts_that_belong_to_nobody() {
        assertFalse(EnrollAttemptAccess.seesUnknown(ADMIN, 2));
        // The administrator still sees the code-less attempt of a phone that is known to be their customer's (6).
        assertEquals(Arrays.asList(1, 2, 3, 6), ids(ADMIN, null, 2));
        assertTrue(EnrollAttemptAccess.seesUnknown(SUPER, 2));
        assertEquals(Arrays.asList(1, 2, 3, 4, 6), ids(SUPER, null, 2));
    }

    @Test
    public void a_row_of_another_customer_is_never_shown() {
        for (User u : Arrays.asList(SUPER, ADMIN, FOLDER_EDITOR, VISOR, VISOR_ALL_DEVICES)) {
            for (int other : new int[]{5, 7}) {
                assertFalse(ids(u, null, 1).contains(other));
                assertFalse(ids(u, null, 2).contains(other));
                assertFalse(ids(u, new HashSet<>(Collections.singletonList(10)), 1).contains(other));
            }
        }
    }

    @Test
    public void nobody_signed_in_sees_nothing_unknown() {
        assertFalse(EnrollAttemptAccess.isFullAdmin(null));
        assertFalse(EnrollAttemptAccess.seesUnknown(null, 1));
        assertFalse(EnrollAttemptAccess.isFullAdmin(new User()));
    }
}
