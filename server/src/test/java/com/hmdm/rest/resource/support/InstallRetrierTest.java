package com.hmdm.rest.resource.support;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.hmdm.util.InstallRetryPolicy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
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

    // --- an install is never brought back against the current intent ------------------------------------------------

    private InstallRetrier.Row failedRow() {
        List<Map<String, Object>> raw = new ArrayList<>();
        raw.add(row("d1", "com.x", 7, "failed", "FAILURE_STORAGE", 1000 * MIN));
        return InstallRetrier.rows(raw).get(0);
    }

    @Test
    public void an_app_taken_out_of_the_policy_between_the_failure_and_the_retry_is_not_installed_again() {
        InstallRetrier.Row failed = failedRow();
        // Its retry is due by the clock…
        assertEquals(1, InstallRetrier.dueGroups(
                InstallRetrier.byDeviceAndPackage(java.util.Collections.singletonList(failed)), 1010 * MIN).size());
        // …but a policy queued it and the policy no longer assigns the app to the phone: nothing is sent.
        assertNull(InstallRetrier.retryPayload(failed, new InstallRetrier.Facts(null, false, true, false)));
        // The same when the policy now says "remove it", whoever ordered the install.
        assertNull(InstallRetrier.retryPayload(failed, new InstallRetrier.Facts(null, true, true, false)));
        assertNull(InstallRetrier.retryPayload(failed, new InstallRetrier.Facts(null, true, false, false)));
    }

    @Test
    public void an_app_still_assigned_is_retried_with_what_the_policy_wants_now() {
        InstallRetrier.Row failed = failedRow();
        String newer = "{\"url\":\"https://x/b.apk\",\"packageName\":\"com.x\",\"versionCode\":8}";
        assertEquals(newer, InstallRetrier.retryPayload(failed, new InstallRetrier.Facts(newer, false, true, false)));
        // Also when the failed order was a manual one for an app the policy assigns too.
        assertEquals(newer, InstallRetrier.retryPayload(failed, new InstallRetrier.Facts(newer, false, false, false)));
    }

    @Test
    public void a_manual_order_is_retried_as_it_was_unless_the_app_was_uninstalled_afterwards() {
        InstallRetrier.Row failed = failedRow();
        assertEquals(failed.payload, InstallRetrier.retryPayload(failed, new InstallRetrier.Facts(null, false, false, false)));
        assertNull("someone uninstalled it after the order",
                InstallRetrier.retryPayload(failed, new InstallRetrier.Facts(null, false, false, true)));
    }

    @Test
    public void the_decision_table() {
        assertEquals(InstallRetryPolicy.Decision.RESEND_POLICY, InstallRetryPolicy.decide(true, false, true, false));
        assertEquals(InstallRetryPolicy.Decision.RESEND_POLICY, InstallRetryPolicy.decide(true, false, false, true));
        assertEquals(InstallRetryPolicy.Decision.SKIP_UNASSIGNED, InstallRetryPolicy.decide(false, false, true, false));
        assertEquals(InstallRetryPolicy.Decision.SKIP_REMOVED, InstallRetryPolicy.decide(false, true, false, false));
        assertEquals(InstallRetryPolicy.Decision.SKIP_REMOVED, InstallRetryPolicy.decide(true, true, false, false));
        assertEquals(InstallRetryPolicy.Decision.SKIP_REMOVED, InstallRetryPolicy.decide(false, false, false, true));
        assertEquals(InstallRetryPolicy.Decision.RESEND_SAME, InstallRetryPolicy.decide(false, false, false, false));
    }

    @Test
    public void a_newer_version_ordered_after_a_failure_starts_from_zero() {
        List<Map<String, Object>> raw = new ArrayList<>();
        for (int i = 0; i < 6; i++) raw.add(row("d1", "com.x", 7, "failed", "FAILURE_STORAGE", 1000 * MIN + i));
        raw.add(row("d1", "com.x", 8, "failed", "FAILURE_STORAGE", 2000 * MIN));
        assertEquals(1, due(raw, 2010 * MIN).size());
    }
}
