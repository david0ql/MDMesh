package com.hmdm.notification.push;

import com.hmdm.notification.AgentWakeHub;
import com.hmdm.persistence.domain.DevicePush;
import com.hmdm.rest.json.agent.AgentWakeConfig;
import com.hmdm.rest.json.agent.AgentWakeReport;
import com.hmdm.util.DcPolicy;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;
import java.lang.reflect.Proxy;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * When the push wake goes out, to which token, and when it does not (debounce, no project, no token) — with a
 * simulated FCM service on the other end, a manual clock and a scheduler the test steps by hand.
 */
public class PushWakerTest {

    /** Runs "now" tasks inline; keeps delayed ones until the test advances the clock. */
    private final class ManualScheduler implements PushWaker.Scheduler {
        final List<Object[]> delayed = new ArrayList<>();

        @Override
        public void run(Runnable task) {
            task.run();
        }

        @Override
        public void later(long delayMs, Runnable task) {
            delayed.add(new Object[]{now + delayMs, task});
        }

        void advance(long ms) {
            long target = now + ms;
            while (true) {
                Object[] next = null;
                for (Object[] d : delayed) {
                    if ((Long) d[0] <= target && (next == null || (Long) d[0] < (Long) next[0])) next = d;
                }
                if (next == null) break;
                delayed.remove(next);
                now = (Long) next[0];
                ((Runnable) next[1]).run();
            }
            now = target;
        }
    }

    private final class MemoryStore implements PushWaker.Store {
        final Map<String, String> tokens = new HashMap<>();
        final List<String> sendLog = new ArrayList<>();
        final List<String> dropped = new ArrayList<>();
        final List<String> answers = new ArrayList<>();

        @Override
        public String token(String deviceNumber) {
            return tokens.get(deviceNumber);
        }

        @Override
        public void recordSend(String deviceNumber, long at, boolean ok, String error) {
            sendLog.add(deviceNumber + ":" + ok);
        }

        @Override
        public void dropToken(String deviceNumber, String token, String error) {
            dropped.add(deviceNumber + ":" + token);
            tokens.remove(deviceNumber);
        }

        @Override
        public boolean recordAnswer(String deviceNumber, long at) {
            answers.add(deviceNumber);
            return true;
        }
    }

    private long now = 1_000_000L;
    private FakeFcm fcm;
    private MemoryStore store;
    private ManualScheduler scheduler;
    private PushWaker waker;

    @Before
    public void start() throws Exception {
        fcm = new FakeFcm();
        store = new MemoryStore();
        scheduler = new ManualScheduler();
        FcmSettings settings = fcm.settings();
        waker = new PushWaker(settings, new FcmClient(settings, HttpClient.newHttpClient(), ms -> { }), store, scheduler,
                () -> now, null);
        store.tokens.put("d1", "token-d1");
        store.tokens.put("d2", "token-d2");
    }

    @After
    public void stop() {
        fcm.close();
    }

    private List<String> pushedTokens() {
        List<String> out = new ArrayList<>();
        synchronized (fcm.sends) {
            for (FakeFcm.Sent s : fcm.sends) out.add(s.token());
        }
        return out;
    }

    @Test
    public void a_wake_is_pushed_to_that_devices_token() {
        assertEquals(PushWaker.Outcome.QUEUED, waker.wake("d1", false));
        assertEquals(java.util.Collections.singletonList("token-d1"), pushedTokens());
        assertEquals(java.util.Collections.singletonList("d1:true"), store.sendLog);
        assertEquals(1L, waker.status().get("sent"));
    }

    @Test
    public void without_a_push_project_nothing_is_sent_and_nothing_breaks() {
        PushWaker off = new PushWaker(FcmSettings.off(null), null, store, scheduler, () -> now, null);
        assertFalse(off.isConfigured());
        assertEquals(PushWaker.Outcome.NOT_CONFIGURED, off.wake("d1", true));
        off.escalateIfSilent("d1");
        off.onCheckIn("d1");
        scheduler.advance(10 * 60_000L);
        assertTrue(fcm.sends.isEmpty());
        assertEquals(Boolean.FALSE, off.status().get("configured"));
    }

    @Test
    public void a_device_without_a_token_is_left_to_its_periodic_check_in() {
        assertEquals(PushWaker.Outcome.NO_TOKEN, waker.wake("no-google", true));
        assertTrue(fcm.sends.isEmpty());
    }

    @Test
    public void a_burst_of_commands_is_one_push_per_device() {
        assertEquals(PushWaker.Outcome.QUEUED, waker.wake("d1", false));
        assertEquals(PushWaker.Outcome.DEBOUNCED, waker.wake("d1", false));
        now += PushWaker.DEBOUNCE_MS - 1;
        assertEquals(PushWaker.Outcome.DEBOUNCED, waker.wake("d1", false));
        // Another device is not held back by the first one.
        assertEquals(PushWaker.Outcome.QUEUED, waker.wake("d2", false));
        now += 2;
        assertEquals(PushWaker.Outcome.QUEUED, waker.wake("d1", false));
        assertEquals(java.util.Arrays.asList("token-d1", "token-d2", "token-d1"), pushedTokens());
    }

    @Test
    public void an_urgent_command_is_pushed_again_seconds_later_but_not_in_the_same_instant() {
        assertEquals(PushWaker.Outcome.QUEUED, waker.wake("d1", false));
        now += 1_000L;
        assertEquals("a double click", PushWaker.Outcome.DEBOUNCED, waker.wake("d1", true));
        now += PushWaker.URGENT_DEBOUNCE_MS;
        assertEquals(PushWaker.Outcome.QUEUED, waker.wake("d1", true));
        assertEquals(2, fcm.sends.size());
        assertEquals(PushWaker.URGENT_TTL_SEC + "s",
                fcm.sends.get(1).body.path("message").path("android").path("ttl").asText());
    }

    @Test
    public void a_push_the_phone_already_answered_does_not_hold_back_the_next_command() {
        assertEquals(PushWaker.Outcome.QUEUED, waker.wake("d1", true));
        now += 800L;
        waker.onCheckIn("d1");          // the phone woke up and took the first command
        now += 500L;                    // the operator sends another one right away
        assertEquals(PushWaker.Outcome.QUEUED, waker.wake("d1", true));
        assertEquals(2, fcm.sends.size());
    }

    @Test
    public void a_command_folded_into_an_unanswered_push_gets_its_own_push_when_the_window_closes() {
        waker.wake("d1", false);
        now += 5_000L;
        assertEquals(PushWaker.Outcome.DEBOUNCED, waker.wake("d1", false));
        assertEquals(PushWaker.Outcome.DEBOUNCED, waker.wake("d1", false));
        assertEquals(1, fcm.sends.size());
        scheduler.advance(PushWaker.DEBOUNCE_MS);
        assertEquals("exactly one more, not one per folded command", 2, fcm.sends.size());

        // ...but not when the phone reported in between: that report already carried everything queued.
        waker.wake("d2", false);
        now += 1_000L;
        assertEquals(PushWaker.Outcome.DEBOUNCED, waker.wake("d2", false));
        now += 1_000L;
        waker.onCheckIn("d2");
        scheduler.advance(PushWaker.DEBOUNCE_MS);
        assertEquals(3, fcm.sends.size());
    }

    @Test
    public void an_urgent_push_nobody_answered_is_repeated_twice_and_then_left_to_the_queue() {
        waker.wake("d1", true);
        assertEquals(1, fcm.sends.size());
        scheduler.advance(PushWaker.FOLLOW_UP_MS[0]);
        assertEquals(2, fcm.sends.size());
        scheduler.advance(PushWaker.FOLLOW_UP_MS[1]);
        assertEquals(3, fcm.sends.size());
        scheduler.advance(60 * 60_000L);
        assertEquals("no endless pushing: the command stays queued", 3, fcm.sends.size());
    }

    @Test
    public void a_check_in_answers_the_push_and_stops_the_repeats() {
        waker.wake("d1", true);
        now += 4_000L;
        waker.onCheckIn("d1");
        assertEquals(java.util.Collections.singletonList("d1"), store.answers);
        scheduler.advance(60 * 60_000L);
        assertEquals(1, fcm.sends.size());
        assertEquals(1L, waker.status().get("answered"));
    }

    @Test
    public void a_wake_over_a_socket_that_gets_no_check_in_is_repeated_as_a_push() {
        waker.escalateIfSilent("d1");
        assertTrue("not at once: the socket normally answers within seconds", fcm.sends.isEmpty());
        scheduler.advance(PushWaker.SILENT_AFTER_MS);
        assertEquals(java.util.Collections.singletonList("token-d1"), pushedTokens());

        waker.escalateIfSilent("d2");
        now += 2_000L;
        waker.onCheckIn("d2");
        scheduler.advance(PushWaker.SILENT_AFTER_MS);
        assertEquals("d2 answered over the socket: no push", 1, fcm.sends.size());
    }

    @Test
    public void a_token_the_service_no_longer_knows_is_dropped_and_the_error_is_kept_for_the_console() {
        fcm.answer(404, "{\"error\":{\"status\":\"NOT_FOUND\",\"details\":[{\"errorCode\":\"UNREGISTERED\"}]}}");
        waker.wake("d1", false);
        assertEquals(java.util.Collections.singletonList("d1:token-d1"), store.dropped);
        assertEquals(java.util.Collections.singletonList("d1:false"), store.sendLog);
        Map<String, Object> status = waker.status();
        assertEquals(1L, status.get("failed"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) status.get("recentErrors");
        assertEquals(1, errors.size());
        assertEquals("d1", errors.get(0).get("device"));
        now += PushWaker.DEBOUNCE_MS + 1;
        assertEquals(PushWaker.Outcome.NO_TOKEN, waker.wake("d1", false));
    }

    @Test
    public void lock_alarm_message_wipe_and_remote_sessions_are_urgent_and_routine_work_is_not() {
        for (String type : new String[]{"device.lock", "device.ring", "device.alert", "device.lockscreenMessage",
                "device.announce", "device.wipe", "remote.vnc.start", "device.passcodeReset", "device.reboot"}) {
            assertTrue(type, PushWaker.isUrgent(type));
        }
        for (String type : new String[]{"config.apply", "app.install", "device.diagnose", "device.storageScan", null, ""}) {
            assertFalse(String.valueOf(type), PushWaker.isUrgent(type));
        }
    }

    // ---- the hub: which channel a wake takes ----

    private Session socket(boolean open, List<String> sentTexts) {
        RemoteEndpoint.Async remote = (RemoteEndpoint.Async) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{RemoteEndpoint.Async.class}, (p, m, a) -> {
                    if ("sendText".equals(m.getName())) sentTexts.add((String) a[0]);
                    return null;
                });
        return (Session) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Session.class}, (p, m, a) -> {
            switch (m.getName()) {
                case "isOpen": return open;
                case "getAsyncRemote": return remote;
                case "hashCode": return 7;
                case "equals": return p == a[0];
                default: return null;
            }
        });
    }

    @Test
    public void a_command_for_a_device_without_live_channel_is_pushed_and_one_with_it_is_not() {
        AgentWakeHub hub = new AgentWakeHub(null);
        hub.setPushWaker(waker);
        List<String> texts = new ArrayList<>();
        hub.register("d2", socket(true, texts));

        hub.wake("d1", "commands");   // no socket
        assertEquals(java.util.Collections.singletonList("token-d1"), pushedTokens());

        hub.wake("d2", "commands");   // live socket: the socket carries it
        assertEquals(1, texts.size());
        assertEquals(1, fcm.sends.size());
        now += 3_000L;
        waker.onCheckIn("d2");
        scheduler.advance(60_000L);
        assertEquals(1, fcm.sends.size());
    }

    @Test
    public void an_urgent_command_goes_over_both_channels_at_once() {
        AgentWakeHub hub = new AgentWakeHub(null);
        hub.setPushWaker(waker);
        List<String> texts = new ArrayList<>();
        hub.register("d2", socket(true, texts));
        hub.wakeFor("d2", "device.lock");
        assertEquals(1, texts.size());
        assertEquals(java.util.Collections.singletonList("token-d2"), pushedTokens());

        hub.wakeFor("d1", "config.apply"); // routine, no socket: pushed once, as any offline wake
        assertEquals(2, fcm.sends.size());
    }

    @Test
    public void the_hub_without_push_behaves_as_before() {
        AgentWakeHub hub = new AgentWakeHub(null);
        List<String> texts = new ArrayList<>();
        hub.register("d2", socket(true, texts));
        hub.wake("d1", "commands");
        hub.wakeFor("d2", "device.lock");
        hub.wakeUrgent("d1");
        assertEquals(1, texts.size());
        assertTrue(fcm.sends.isEmpty());
    }

    // ---- what the console is told, and what the agent is told ----

    @Test
    public void reach_is_live_then_push_then_poll() {
        DevicePush withToken = new DevicePush();
        withToken.setToken("t");
        withToken.setBatteryExempt(true);
        DevicePush noToken = new DevicePush();
        noToken.setPollMinutes(3);

        assertEquals(WakeReach.LIVE, WakeReach.channel(true, true, withToken));
        assertEquals(WakeReach.PUSH, WakeReach.channel(false, true, withToken));
        assertEquals("a token is useless without a project", WakeReach.POLL, WakeReach.channel(false, false, withToken));
        assertEquals(WakeReach.POLL, WakeReach.channel(false, true, noToken));
        assertEquals(WakeReach.POLL, WakeReach.channel(false, true, null));

        assertEquals(180, WakeReach.view(false, true, noToken).get("expectedSeconds"));
        assertEquals(300, WakeReach.view(false, false, null).get("expectedSeconds"));
        assertEquals(Boolean.TRUE, WakeReach.view(false, false, null).get("mayBeLonger"));
        assertEquals(Boolean.FALSE, WakeReach.view(false, true, withToken).get("mayBeLonger"));
        assertEquals(Boolean.FALSE, WakeReach.view(true, false, null).get("mayBeLonger"));
        assertFalse("the token never leaves the server", WakeReach.view(false, true, withToken).containsValue("t"));
    }

    @Test
    public void the_agent_report_is_cleaned_before_it_is_stored() {
        AgentWakeReport r = new AgentWakeReport();
        r.setProvider("fcm");
        r.setToken("  tok  ");
        r.setState("ready");
        r.setPollMinutes(5);
        r.setWokenBy("push");
        r.setBatteryExempt(true);
        DevicePush row = WakeCheckIn.row("d1", r, 42L);
        assertEquals("tok", row.getToken());
        assertEquals(Long.valueOf(42L), row.getTokenAt());
        assertEquals("push", row.getLastWokenBy());

        r.setProvider("carrier-pigeon");
        r.setState("<script>");
        r.setWokenBy("x");
        r.setPollMinutes(999_999);
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < WakeCheckIn.MAX_TOKEN + 1; i++) huge.append('a');
        r.setToken(huge.toString());
        row = WakeCheckIn.row("d1", r, 42L);
        assertNull(row.getProvider());
        assertNull(row.getToken());
        assertNull(row.getState());
        assertNull(row.getLastWokenBy());
        assertNull(row.getPollMinutes());
    }

    @Test
    public void the_check_in_answer_carries_the_policy_schedule_and_the_public_push_values_only() {
        assertNull("no policy schedule, no project: older answer shape", WakeCheckIn.config(null, FcmSettings.off(null)));

        DcPolicy p = DcPolicy.parse("{\"wake\":{\"pollMinutes\":3,\"fastPollMinutes\":1,\"fastWindowMinutes\":15,\"alarmClock\":true}}");
        AgentWakeConfig c = WakeCheckIn.config(p.getWake(), FcmSettings.off(null));
        assertEquals(Integer.valueOf(3), c.getPollMinutes());
        assertEquals(Integer.valueOf(1), c.getFastPollMinutes());
        assertEquals(Integer.valueOf(15), c.getFastWindowMinutes());
        assertEquals(Boolean.TRUE, c.getAlarmClock());
        assertNull(c.getPush());

        c = WakeCheckIn.config(null, fcm.settings());
        assertEquals("fcm", c.getPush().getProvider());
        assertEquals("demo-project", c.getPush().getProjectId());
        assertEquals("AIza-test", c.getPush().getApiKey());
        assertEquals("123456", c.getPush().getSenderId());

        // Out-of-range values are dropped by the policy document, not passed to phones.
        assertNull(DcPolicy.parse("{\"wake\":{\"pollMinutes\":0,\"fastPollMinutes\":99}}").getWake());
        assertTrue(DcPolicy.parse("{\"wake\":{\"pollMinutes\":900}}").isEmpty());
    }
}
