package com.hmdm.rest.resource.support;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class InstallRetrierTest {
    private static final long MIN = 60_000L;
    private int nextId = 1;

    private Map<String, Object> row(String device, String pkg, int versionCode, String status, String detail, long completedAt) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", nextId++);
        m.put("devicenumber", device);
        m.put("type", "app.install");
        m.put("status", status);
        m.put("detail", detail);
        m.put("payload", "{\"url\":\"https://x/a.apk\",\"packageName\":\"" + pkg + "\",\"versionCode\":" + versionCode + "}");
        m.put("requirescapability", "app.silentInstall");
        m.put("createdat", completedAt - 1000);
        m.put("completedat", "pending".equals(status) || "delivered".equals(status) ? null : completedAt);
        return m;
    }

    private List<List<InstallRetrier.Row>> due(List<Map<String, Object>> raw, long now) {
        return InstallRetrier.dueGroups(InstallRetrier.byDeviceAndPackage(InstallRetrier.rows(raw)), now);
    }

    @Test
    public void a_failed_install_is_retried_only_after_its_wait() {
        List<Map<String, Object>> raw = new ArrayList<>();
        raw.add(row("d1", "com.x", 7, "failed", "FAILURE_STORAGE", 1000 * MIN));
        assertTrue(due(raw, 1005 * MIN).isEmpty());
        List<List<InstallRetrier.Row>> due = due(raw, 1010 * MIN);
        assertEquals(1, due.size());
        assertEquals("com.x", due.get(0).get(0).pkg);
        assertEquals("d1", due.get(0).get(0).deviceNumber);
    }

    @Test
    public void each_failure_in_a_row_waits_longer_and_they_stop_at_the_cap() {
        List<Map<String, Object>> raw = new ArrayList<>();
        raw.add(row("d1", "com.x", 7, "failed", "FAILURE_STORAGE", 1000 * MIN));
        raw.add(row("d1", "com.x", 7, "failed", "FAILURE_STORAGE", 1020 * MIN));
        assertTrue("second failure waits 30 min, not 10", due(raw, 1035 * MIN).isEmpty());
        assertEquals(1, due(raw, 1050 * MIN).size());
        for (int i = 0; i < 4; i++) raw.add(row("d1", "com.x", 7, "failed", "FAILURE_STORAGE", 1100 * MIN + i));
        assertTrue("six failures in a row: no more automatic retries", due(raw, 99_999 * MIN).isEmpty());
    }

    @Test
    public void a_retry_already_queued_or_a_definitive_failure_is_left_alone() {
        List<Map<String, Object>> raw = new ArrayList<>();
        raw.add(row("d1", "com.x", 7, "failed", "FAILURE_STORAGE", 1000 * MIN));
        raw.add(row("d1", "com.x", 7, "pending", null, 1011 * MIN));
        raw.add(row("d2", "com.x", 7, "failed", "INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match", 1000 * MIN));
        raw.add(row("d3", "com.x", 7, "done", null, 1000 * MIN));
        assertTrue(due(raw, 5000 * MIN).isEmpty());
    }

    @Test
    public void devices_and_packages_are_judged_separately_and_the_agent_itself_is_never_retried() {
        List<Map<String, Object>> raw = new ArrayList<>();
        raw.add(row("d1", "com.x", 7, "failed", "FAILURE_STORAGE", 1000 * MIN));
        raw.add(row("d1", "com.y", 3, "done", null, 1001 * MIN));
        raw.add(row("d2", "com.x", 7, "done", null, 1002 * MIN));
        raw.add(row("d2", "com.dallycontrol.agent", 90, "failed", "FAILURE_ABORTED", 1000 * MIN));
        List<List<InstallRetrier.Row>> due = due(raw, 2000 * MIN);
        assertEquals(1, due.size());
        assertEquals("d1", due.get(0).get(0).deviceNumber);
    }

    @Test
    public void a_newer_version_ordered_after_a_failure_starts_from_zero() {
        List<Map<String, Object>> raw = new ArrayList<>();
        for (int i = 0; i < 6; i++) raw.add(row("d1", "com.x", 7, "failed", "FAILURE_STORAGE", 1000 * MIN + i));
        raw.add(row("d1", "com.x", 8, "failed", "FAILURE_STORAGE", 2000 * MIN));
        assertEquals(1, due(raw, 2010 * MIN).size());
    }
}
