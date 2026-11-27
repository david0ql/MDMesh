package com.hmdm.util;

import com.hmdm.persistence.domain.AgentCommand;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class CommandQueueSweepTest {
    private static final long NOW = 2_000_000_000_000L;
    private static final long MIN = 60_000L;
    private static final String DEV = "dev-1";

    /** An in-memory queue that behaves like the table. */
    private static final class FakeQueue implements CommandQueueSweep.Queue {
        final List<AgentCommand> rows = new ArrayList<>();
        final Map<Integer, String> details = new HashMap<>();

        AgentCommand add(String type, String status, long ageMs, Long heldMs, int deliveries) {
            AgentCommand c = new AgentCommand();
            c.setId(rows.size() + 1);
            c.setDeviceNumber(DEV);
            c.setType(type);
            c.setStatus(status);
            c.setCreatedAt(NOW - ageMs);
            c.setDeliveredAt(heldMs == null ? null : NOW - heldMs);
            c.setDeliveries(deliveries);
            rows.add(c);
            return c;
        }

        @Override public List<AgentCommand> listOpen(String deviceNumber) {
            List<AgentCommand> out = new ArrayList<>();
            for (AgentCommand c : rows) {
                if ("pending".equals(c.getStatus()) || "delivered".equals(c.getStatus())) out.add(c);
            }
            return out;
        }

        @Override public void expire(Integer id, long now, String detail) {
            AgentCommand c = rows.get(id - 1);
            c.setStatus("expired");
            c.setCompletedAt(now);
            details.put(id, detail);
        }

        @Override public void requeue(Integer id) {
            AgentCommand c = rows.get(id - 1);
            c.setStatus("pending");
            c.setDeliveredAt(null);
        }

        @Override public boolean newerDelivered(String deviceNumber, Integer id, Set<String> types) {
            for (AgentCommand c : rows) {
                if (c.getId() > id && types.contains(c.getType().toLowerCase())
                        && !"pending".equals(c.getStatus()) && !"cancelled".equals(c.getStatus())
                        && !"expired".equals(c.getStatus())) {
                    return true;
                }
            }
            return false;
        }
    }

    @Test public void a_phone_back_after_two_days_still_gets_its_lock_message_and_wipe() {
        FakeQueue q = new FakeQueue();
        AgentCommand lock = q.add("device.lock", "pending", 2 * 24 * 60 * MIN, null, 0);
        AgentCommand msg = q.add("device.lockscreenMessage", "pending", 2 * 24 * 60 * MIN, null, 0);
        AgentCommand wipe = q.add("device.wipe", "pending", 2 * 24 * 60 * MIN, null, 0);
        AgentCommand launch = q.add("device.appLaunch", "pending", 2 * 24 * 60 * MIN, null, 0);

        CommandQueueSweep.Outcome out = CommandQueueSweep.run(q, DEV, false, NOW);

        assertEquals("pending", lock.getStatus());
        assertEquals("pending", msg.getStatus());
        assertEquals("pending", wipe.getStatus());
        assertEquals("expired", launch.getStatus());
        assertEquals(CommandQueueSweep.DETAIL_EXPIRED_PENDING, q.details.get(launch.getId()));
        assertEquals(1, out.expired);
    }

    @Test public void a_delivered_lock_nobody_confirmed_is_handed_out_again() {
        FakeQueue q = new FakeQueue();
        AgentCommand lock = q.add("device.lock", "delivered", 10 * MIN, 3 * MIN, 1);

        CommandQueueSweep.Outcome out = CommandQueueSweep.run(q, DEV, false, NOW);

        assertEquals("pending", lock.getStatus());
        assertNull(lock.getDeliveredAt());
        assertEquals(1, out.requeued);
    }

    @Test public void a_lost_kiosk_enter_is_not_replayed_after_a_newer_exit_reached_the_phone() {
        FakeQueue q = new FakeQueue();
        AgentCommand enter = q.add("kiosk.enter", "delivered", 10 * MIN, 5 * MIN, 1);
        q.add("kiosk.exit", "done", 4 * MIN, 4 * MIN, 1);

        CommandQueueSweep.Outcome out = CommandQueueSweep.run(q, DEV, false, NOW);

        assertEquals("expired", enter.getStatus());
        assertEquals(CommandQueueSweep.DETAIL_SUPERSEDED, q.details.get(enter.getId()));
        assertEquals(1, out.superseded);
        assertEquals(0, out.requeued);
    }

    @Test public void a_lost_kiosk_enter_is_replayed_when_the_newer_exit_is_still_waiting() {
        FakeQueue q = new FakeQueue();
        AgentCommand enter = q.add("kiosk.enter", "delivered", 10 * MIN, 5 * MIN, 1);
        AgentCommand exit = q.add("kiosk.exit", "pending", 4 * MIN, null, 0);

        CommandQueueSweep.run(q, DEV, false, NOW);

        // Both go out in id order: enter, then exit — the final state is the newest order.
        assertEquals("pending", enter.getStatus());
        assertEquals("pending", exit.getStatus());
    }

    @Test public void a_reboot_without_answer_stays_put_for_an_agent_without_ledger() {
        FakeQueue q = new FakeQueue();
        AgentCommand reboot = q.add("device.reboot", "delivered", 10 * MIN, 5 * MIN, 1);

        CommandQueueSweep.run(q, DEV, false, NOW);
        assertEquals("delivered", reboot.getStatus());

        CommandQueueSweep.run(q, DEV, true, NOW);
        assertEquals("pending", reboot.getStatus());
    }

    @Test public void reading_the_history_closes_overdue_commands_but_hands_nothing_out_again() {
        FakeQueue q = new FakeQueue();
        AgentCommand lock = q.add("device.lock", "delivered", 10 * MIN, 5 * MIN, 1);
        AgentCommand launch = q.add("device.appLaunch", "pending", 2 * 60 * MIN, null, 0);

        CommandQueueSweep.Outcome out = CommandQueueSweep.expireOnly(q, DEV, NOW);

        assertEquals("delivered", lock.getStatus());
        assertEquals("expired", launch.getStatus());
        assertEquals(0, out.requeued);
    }

    @Test public void a_delivered_command_out_of_retries_ends_as_expired_with_its_reason() {
        FakeQueue q = new FakeQueue();
        AgentCommand alert = q.add("device.alert", "delivered", 8 * 60 * MIN, 7 * 60 * MIN, 5);

        CommandQueueSweep.run(q, DEV, false, NOW);

        assertEquals("expired", alert.getStatus());
        assertEquals(CommandQueueSweep.DETAIL_EXPIRED_DELIVERED, q.details.get(alert.getId()));
    }
}
