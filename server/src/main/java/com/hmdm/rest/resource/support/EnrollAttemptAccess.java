package com.hmdm.rest.resource.support;

import com.hmdm.persistence.domain.AgentEnrollAttempt;
import com.hmdm.persistence.domain.User;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Who sees which rows of the enrollment-attempts log.
 *
 * <ul>
 * <li>An attempt made with a known code belongs to that code's customer and folder: a user limited to folders sees
 * the attempts of their folders only; everyone else of that customer sees all of the customer's.</li>
 * <li>An attempt with no recognised code (a mistyped code, a QR of another server, a Device Owner that never got
 * its token) belongs to nobody. Only a full administrator sees those - never a folder editor, never a view-only
 * role - and only when they cannot be another customer's: the installation's super-administrator always, a
 * customer's administrator when the installation has that one customer.</li>
 * </ul>
 */
public final class EnrollAttemptAccess {

    /** Role ids of the product: 1 super-admin, 2 Admin (3 User = folder editor, 100 Observer, 200 Visor). */
    private static final int ROLE_SUPER_ADMIN = 1;
    private static final int ROLE_ADMIN = 2;

    private EnrollAttemptAccess() {
    }

    /** A full administrator: the super-admin or the Admin role, and not limited to folders. */
    public static boolean isFullAdmin(User user) {
        if (user == null || user.getUserRole() == null || UserScope.isRestricted(user)) {
            return false;
        }
        Integer role = user.getUserRole().getId();
        return user.getUserRole().isSuperAdmin()
                || (role != null && (role == ROLE_SUPER_ADMIN || role == ROLE_ADMIN));
    }

    /**
     * Whether [user] may see the attempts that carry no recognised code.
     *
     * @param customers how many customers this installation has
     */
    public static boolean seesUnknown(User user, int customers) {
        if (!isFullAdmin(user)) {
            return false;
        }
        return user.getUserRole().isSuperAdmin() || customers <= 1;
    }

    /**
     * The rows [user] may see out of those the query returned for their customer.
     *
     * @param groups the folders the user reaches; null = not limited to folders
     */
    public static List<AgentEnrollAttempt> visible(List<AgentEnrollAttempt> rows, User user, int customerId,
                                                   Set<Integer> groups, boolean seesUnknown) {
        final boolean fullAdmin = isFullAdmin(user);
        return rows.stream().filter(a -> {
            if (a.getCustomerId() != null && a.getCustomerId() != customerId) {
                return false;
            }
            if (a.getTokenId() == null) {
                // No recognised code. It may still be known whose phone it is (the server found the phone among one
                // customer's devices): then that customer's full administrators see it, nobody else.
                return fullAdmin && (a.getCustomerId() != null || seesUnknown);
            }
            if (a.getCustomerId() == null) {
                return false;
            }
            return groups == null || (a.getGroupId() != null && groups.contains(a.getGroupId()));
        }).collect(Collectors.toList());
    }
}
