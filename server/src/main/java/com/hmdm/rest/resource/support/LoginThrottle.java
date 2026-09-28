package com.hmdm.rest.resource.support;

import javax.inject.Singleton;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Brute-force protection for the console login, per account. After {@link #FREE_FAILURES} consecutive failures the
 * account is locked for {@link #BASE_LOCK_MS}, doubling on every further failure up to {@link #MAX_LOCK_MS}; a successful
 * login clears it. While locked, a login is refused before the password is even checked (so a correct guess made during
 * the lock does not succeed) and the caller gets the same answer as for a wrong password.
 *
 * In memory: a restart clears it, which only ever helps a legitimate user.
 */
@Singleton
public class LoginThrottle {

    static final int FREE_FAILURES = 5;
    static final long BASE_LOCK_MS = 5 * 60 * 1000L;
    static final long MAX_LOCK_MS = 30 * 60 * 1000L;
    /** Failures older than this no longer count. */
    static final long WINDOW_MS = 30 * 60 * 1000L;

    private static final class State {
        int failures;
        long lastFailure;
        long lockedUntil;
    }

    private final ConcurrentHashMap<String, State> byLogin = new ConcurrentHashMap<>();

    private static String key(String login) {
        return login == null ? "" : login.trim().toLowerCase(Locale.ROOT);
    }

    /** True while the account is locked. */
    public boolean isLocked(String login, long now) {
        State s = byLogin.get(key(login));
        if (s == null) {
            return false;
        }
        synchronized (s) {
            return now < s.lockedUntil;
        }
    }

    /** Record a failed attempt; returns the lock end (epoch ms) when this failure locked the account, else 0. */
    public long failed(String login, long now) {
        State s = byLogin.computeIfAbsent(key(login), k -> new State());
        synchronized (s) {
            if (now - s.lastFailure > WINDOW_MS) {
                s.failures = 0;
            }
            s.failures++;
            s.lastFailure = now;
            if (s.failures >= FREE_FAILURES) {
                int over = s.failures - FREE_FAILURES;
                long lock = over >= 3 ? MAX_LOCK_MS : Math.min(MAX_LOCK_MS, BASE_LOCK_MS << over);
                s.lockedUntil = now + lock;
                return s.lockedUntil;
            }
            return 0;
        }
    }

    public void succeeded(String login) {
        byLogin.remove(key(login));
    }
}
