package com.hmdm.util;

import com.hmdm.persistence.domain.AgentCommand;
import org.junit.Test;

import static com.hmdm.util.CommandPolicy.Action.EXPIRE;
import static com.hmdm.util.CommandPolicy.Action.KEEP;
import static com.hmdm.util.CommandPolicy.Action.REQUEUE;
import static com.hmdm.util.CommandPolicy.Action.SUPERSEDE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CommandPolicyTest {
    private static final long NOW = 2_000_000_000_000L;
    private static final long MIN = 60_000L;
    private static final long HOUR = 60 * MIN;
    private static final long DAY = 24 * HOUR;

    private static AgentCommand pending(String type, long age) {
        AgentCommand c = new AgentCommand();
        c.setType(type);
        c.setStatus("pending");
        c.setCreatedAt(NOW - age);
        c.setDeliveries(0);
        return c;
    }

    private static AgentCommand delivered(String type, long age, long held, int deliveries) {
        AgentCommand c = pending(type, age);
        c.setStatus("delivered");
        c.setDeliveredAt(NOW - held);
        c.setDeliveries(deliveries);
        return c;
    }

    private static CommandPolicy.Action decide(AgentCommand c) {
        return CommandPolicy.decide(c, NOW, false, false);
    }

    @Test public void wipe_and_lock_wait_for_the_phone_without_limit() {
        assertEquals(KEEP, decide(pending("device.wipe", 400 * DAY)));
        assertEquals(KEEP, decide(pending("device.lock", 400 * DAY)));
    }

    @Test public void security_actions_outlive_the_old_sixty_minutes() {
        for (String t : new String[]{"device.alert", "device.lockscreenMessage", "device.passcodeReset",
                "kiosk.enter", "kiosk.exit", "device.ring", "device.reboot"}) {
            assertEquals(t, KEEP, decide(pending(t, 61 * MIN)));
            assertEquals(t, KEEP, decide(pending(t, 23 * HOUR)));
        }
    }

    @Test public void each_wait_ends_at_its_own_time() {
        assertEquals(KEEP, decide(pending("device.alert", 29 * DAY)));
        assertEquals(EXPIRE, decide(pending("device.alert", 31 * DAY)));
        assertEquals(EXPIRE, decide(pending("device.passcodeReset", 31 * DAY)));
        assertEquals(EXPIRE, decide(pending("kiosk.exit", 31 * DAY)));
        assertEquals(EXPIRE, decide(pending("device.ring", 25 * HOUR)));
        assertEquals(EXPIRE, decide(pending("device.reboot", 25 * HOUR)));
        assertEquals(KEEP, decide(pending("app.install", 29 * DAY)));
        assertEquals(EXPIRE, decide(pending("app.install", 31 * DAY)));
    }

    @Test public void remote_sessions_expire_fast() {
        assertEquals(KEEP, decide(pending("remote.vnc.start", 4 * MIN)));
        assertEquals(EXPIRE, decide(pending("remote.vnc.start", 6 * MIN)));
        assertEquals(EXPIRE, decide(pending("remote.vnc.stop", 6 * MIN)));
        assertEquals(EXPIRE, decide(pending("remote.inputSetup", 6 * MIN)));
    }

    @Test public void unlisted_types_keep_the_old_hour() {
        assertEquals(KEEP, decide(pending("device.appLaunch", 59 * MIN)));
        assertEquals(EXPIRE, decide(pending("device.appLaunch", 61 * MIN)));
        assertEquals(EXPIRE, decide(pending("x.unknown", 61 * MIN)));
        assertEquals(EXPIRE, decide(pending("config.apply", 61 * MIN)));
    }

    @Test public void the_type_is_matched_whatever_its_case() {
        assertEquals(KEEP, decide(pending("DEVICE.WIPE", 400 * DAY)));
        assertEquals(KEEP, decide(pending(" device.lockscreenmessage ", 2 * DAY)));
    }

    @Test public void a_delivered_command_without_answer_goes_back_after_two_minutes() {
        for (String t : new String[]{"device.lock", "device.wipe", "device.alert", "device.lockscreenMessage",
                "device.ring", "device.passcodeReset", "kiosk.enter", "kiosk.exit"}) {
            assertEquals(t, KEEP, decide(delivered(t, 10 * MIN, 90_000L, 1)));
            assertEquals(t, REQUEUE, decide(delivered(t, 10 * MIN, 3 * MIN, 1)));
        }
    }

    @Test public void a_command_delivered_days_ago_to_a_phone_that_went_dark_is_sent_again_not_expired() {
        assertEquals(REQUEUE, decide(delivered("device.lock", 3 * DAY, 3 * DAY, 1)));
        assertEquals(REQUEUE, decide(delivered("device.wipe", 90 * DAY, 90 * DAY, 1)));
        assertEquals(REQUEUE, decide(delivered("device.alert", 3 * DAY, 3 * DAY, 1)));
    }

    @Test public void redelivery_stops_at_the_limit_and_then_the_command_expires() {
        assertEquals(REQUEUE, decide(delivered("device.lock", HOUR, 3 * MIN, 9)));
        assertEquals(KEEP, decide(delivered("device.lock", HOUR, 3 * MIN, 10)));
        assertEquals(EXPIRE, decide(delivered("device.lock", 8 * HOUR, 7 * HOUR, 10)));
        assertEquals(KEEP, decide(delivered("device.alert", HOUR, 3 * MIN, 5)));
        assertEquals(KEEP, decide(delivered("device.ring", HOUR, 3 * MIN, 3)));
    }

    @Test public void redelivery_stops_once_the_wait_of_the_type_is_over() {
        assertEquals(KEEP, decide(delivered("device.ring", 25 * HOUR, HOUR, 1)));
        assertEquals(EXPIRE, decide(delivered("device.ring", 2 * DAY, 7 * HOUR, 1)));
    }

    @Test public void types_that_are_not_safe_to_repeat_are_never_sent_twice() {
        for (String t : new String[]{"remote.vnc.start", "remote.vnc.stop", "device.appLaunch", "device.openStore",
                "device.display", "device.clearCache", "device.storageClean", "device.announce", "x.unknown"}) {
            assertEquals(t, KEEP, decide(delivered(t, 10 * MIN, 5 * MIN, 1)));
            assertEquals(t, EXPIRE, decide(delivered(t, 8 * HOUR, 7 * HOUR, 1)));
        }
    }

    @Test public void a_reboot_is_sent_again_only_to_an_agent_that_remembers_what_it_ran() {
        AgentCommand reboot = delivered("device.reboot", 10 * MIN, 3 * MIN, 1);
        assertEquals(KEEP, CommandPolicy.decide(reboot, NOW, false, false));
        assertEquals(REQUEUE, CommandPolicy.decide(reboot, NOW, true, false));
        assertEquals(KEEP, CommandPolicy.decide(delivered("device.reboot", 10 * MIN, 3 * MIN, 2), NOW, true, false));
    }

    @Test public void a_lost_state_change_is_not_replayed_over_a_newer_one() {
        assertEquals(SUPERSEDE, CommandPolicy.decide(delivered("kiosk.enter", 10 * MIN, 3 * MIN, 1), NOW, false, true));
        assertEquals(SUPERSEDE, CommandPolicy.decide(delivered("device.passcodeReset", 10 * MIN, 3 * MIN, 1), NOW, false, true));
        assertEquals(SUPERSEDE, CommandPolicy.decide(delivered("device.lockscreenMessage", 10 * MIN, 3 * MIN, 1), NOW, false, true));
        // A message or a lock stands alone: a newer one does not replace it.
        assertEquals(REQUEUE, CommandPolicy.decide(delivered("device.alert", 10 * MIN, 3 * MIN, 1), NOW, false, true));
        assertEquals(REQUEUE, CommandPolicy.decide(delivered("device.lock", 10 * MIN, 3 * MIN, 1), NOW, false, true));
    }

    @Test public void families_group_the_commands_that_set_the_same_state() {
        assertTrue(CommandPolicy.familyTypes("kiosk.enter").contains("kiosk.exit"));
        assertTrue(CommandPolicy.familyTypes("device.ringStop").contains("device.ring"));
        assertEquals(1, CommandPolicy.familyTypes("device.passcodeReset").size());
        assertTrue(CommandPolicy.familyTypes("device.lock").isEmpty());
        assertTrue(CommandPolicy.familyTypes("x.unknown").isEmpty());
    }

    @Test public void policy_and_installs_keep_their_previous_resend() {
        assertEquals(KEEP, decide(delivered("config.apply", 10 * MIN, 2 * MIN, 1)));
        assertEquals(REQUEUE, decide(delivered("config.apply", 10 * MIN, 4 * MIN, 40)));
        assertEquals(KEEP, decide(delivered("app.install", 20 * MIN, 9 * MIN, 1)));
        assertEquals(REQUEUE, decide(delivered("app.install", 20 * MIN, 11 * MIN, 40)));
    }

    @Test public void closed_or_broken_rows_are_left_alone() {
        AgentCommand done = pending("device.lock", DAY);
        done.setStatus("done");
        assertEquals(KEEP, decide(done));
        assertEquals(KEEP, decide(null));
        AgentCommand noDeliveredAt = pending("device.lock", DAY);
        noDeliveredAt.setStatus("delivered");
        assertEquals(KEEP, decide(noDeliveredAt));
        assertFalse(CommandPolicy.of(null).redelivers());
    }
}
