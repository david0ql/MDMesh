package com.hmdm.notification.push;

import com.hmdm.persistence.domain.DevicePush;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <p>By which channel a device can be reached right now and how long a command should take to arrive. Pure: the
 * console shows it on the device page and in the answer to queueing a command.</p>
 * <ul>
 *     <li>{@code live} — the wake socket is open: seconds.</li>
 *     <li>{@code push} — socket closed, but the server has a push project and the device a token: the push wakes it.</li>
 *     <li>{@code poll} — neither: the command waits for the agent's next backup check-in.</li>
 * </ul>
 */
public final class WakeReach {

    public static final String LIVE = "live";
    public static final String PUSH = "push";
    public static final String POLL = "poll";

    static final int LIVE_SECONDS = 5;
    static final int PUSH_SECONDS = 20;
    static final int DEFAULT_POLL_MINUTES = 5;

    private WakeReach() {
    }

    public static String channel(boolean live, boolean pushConfigured, DevicePush row) {
        if (live) return LIVE;
        return pushConfigured && row != null && row.getToken() != null && !row.getToken().isEmpty() ? PUSH : POLL;
    }

    /** Upper end of the usual wait, in seconds. */
    public static int expectedSeconds(String channel, DevicePush row) {
        if (LIVE.equals(channel)) return LIVE_SECONDS;
        if (PUSH.equals(channel)) return PUSH_SECONDS;
        Integer poll = row == null ? null : row.getPollMinutes();
        return (poll == null || poll < 1 ? DEFAULT_POLL_MINUTES : poll) * 60;
    }

    public static Map<String, Object> view(boolean live, boolean pushConfigured, DevicePush row) {
        String channel = channel(live, pushConfigured, row);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("channel", channel);
        out.put("expectedSeconds", expectedSeconds(channel, row));
        out.put("pushConfigured", pushConfigured);
        if (row != null) {
            out.put("pushState", row.getState());
            out.put("pushError", row.getError());
            out.put("hasToken", row.getToken() != null && !row.getToken().isEmpty());
            out.put("tokenAt", row.getTokenAt());
            out.put("batteryExempt", row.getBatteryExempt());
            out.put("exactAlarms", row.getExactAlarms());
            out.put("bgRestricted", row.getBgRestricted());
            out.put("pollMinutes", row.getPollMinutes());
            out.put("lastWokenBy", row.getLastWokenBy());
            out.put("lastSentAt", row.getLastSentAt());
            out.put("lastSentOk", row.getLastSentOk());
            out.put("lastSendError", row.getLastSendError());
            out.put("lastWakeMs", row.getLastWakeMs());
        }
        // Without a battery exemption a parked phone stretches its own check-ins: the estimate is a floor, not a cap.
        out.put("mayBeLonger", !LIVE.equals(channel) && (row == null || !Boolean.TRUE.equals(row.getBatteryExempt())));
        return out;
    }
}
