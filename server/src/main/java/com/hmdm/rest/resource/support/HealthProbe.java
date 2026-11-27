package com.hmdm.rest.resource.support;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * <p>Health of this server instance for load balancers, container health checks and monitors.</p>
 *
 * <p>Runs each named dependency check with a hard time limit (a database that does not answer must not hang the
 * probe) and remembers the outcome for a short time, so an unauthenticated caller cannot turn the probe into a
 * database load generator. Reports only {@code ok}/{@code fail} per dependency: never an error text, a host name or
 * any data.</p>
 *
 * <p>An instance that is being drained (see {@link #STATUS_DRAINING}) reports itself as not ready, so the edge stops
 * sending it new requests before it is stopped during a deployment.</p>
 */
public class HealthProbe {

    public static final String STATUS_OK = "ok";
    public static final String STATUS_DOWN = "down";
    public static final String STATUS_DRAINING = "draining";

    /** The outcome of one probe: overall status plus one {@code ok}/{@code fail} per dependency. */
    public static final class Result {
        private final String status;
        private final Map<String, String> checks;

        Result(String status, Map<String, String> checks) {
            this.status = status;
            this.checks = checks;
        }

        public String getStatus() {
            return status;
        }

        public Map<String, String> getChecks() {
            return checks;
        }

        public boolean isReady() {
            return STATUS_OK.equals(status);
        }

        /** The JSON body. Keys and values are fixed words, so no escaping is needed. */
        public String toJson() {
            StringBuilder sb = new StringBuilder("{\"status\":\"").append(status).append("\",\"checks\":{");
            boolean first = true;
            for (Map.Entry<String, String> e : checks.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(e.getKey()).append("\":\"").append(e.getValue()).append('"');
            }
            return sb.append("}}").toString();
        }
    }

    private final Map<String, Callable<Boolean>> checks = new LinkedHashMap<>();
    private final Callable<Boolean> draining;
    private final long timeoutMs;
    private final long cacheMs;
    private final LongSupplier clock;
    private final ExecutorService executor;

    private Result cached;
    private long cachedAt;

    /**
     * @param draining  whether this instance is being drained (answers quickly, e.g. a file test)
     * @param timeoutMs time limit of each dependency check
     * @param cacheMs   how long an outcome is reused
     * @param clock     milliseconds clock (injectable for tests)
     */
    public HealthProbe(Callable<Boolean> draining, long timeoutMs, long cacheMs, LongSupplier clock) {
        this.draining = draining;
        this.timeoutMs = timeoutMs;
        this.cacheMs = cacheMs;
        this.clock = clock;
        // At most 4 checks in flight; a check that is stuck keeps its thread until it returns, and further probes
        // then fail fast (rejected) instead of piling up threads.
        this.executor = new ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
            Thread t = new Thread(r, "health-probe");
            t.setDaemon(true);
            return t;
        });
    }

    /** Registers a dependency check. It passes when it returns true within the time limit. */
    public HealthProbe add(String name, Callable<Boolean> check) {
        checks.put(name, check);
        return this;
    }

    /** The current health; a recent outcome is reused. Draining is always evaluated fresh. */
    public synchronized Result check() {
        long now = clock.getAsLong();
        if (cached == null || now - cachedAt >= cacheMs || now < cachedAt) {
            Map<String, String> out = new LinkedHashMap<>();
            boolean allOk = true;
            for (Map.Entry<String, Callable<Boolean>> e : checks.entrySet()) {
                boolean ok = runWithLimit(e.getValue());
                out.put(e.getKey(), ok ? "ok" : "fail");
                allOk &= ok;
            }
            cached = new Result(allOk ? STATUS_OK : STATUS_DOWN, out);
            cachedAt = now;
        }
        boolean isDraining;
        try {
            isDraining = Boolean.TRUE.equals(draining.call());
        } catch (Exception e) {
            isDraining = false;
        }
        if (isDraining && cached.isReady()) {
            return new Result(STATUS_DRAINING, cached.getChecks());
        }
        return cached;
    }

    private boolean runWithLimit(Callable<Boolean> check) {
        Future<Boolean> future;
        try {
            future = executor.submit(check);
        } catch (RejectedExecutionException e) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(future.get(timeoutMs, TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            future.cancel(true);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
