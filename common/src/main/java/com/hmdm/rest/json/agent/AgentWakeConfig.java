package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * <p>How the server wants an agent to stay reachable when its live channel is closed, sent with every check-in
 * answer (older agents ignore it): the backup check-in schedule of the device's policy and, when the server has a
 * push project, what the agent needs to register with it. Carries no secret: these are the public client values of
 * the Firebase project (the same ones a {@code google-services.json} ships inside any app).</p>
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentWakeConfig {
    /** Minutes between backup check-ins (1–15); null = the agent's default. */
    private Integer pollMinutes;
    /** Minutes between check-ins during the fast window that follows activity on the phone (1–5); null = default. */
    private Integer fastPollMinutes;
    /** Length in minutes of that fast window (0 = off, up to 60); null = default. */
    private Integer fastWindowMinutes;
    /** Use an alarm-clock alarm (never deferred by Doze, shows the alarm icon); null/false = no. */
    private Boolean alarmClock;
    /** Null when the server has no push project: the agent then drops any registration it had. */
    private Push push;

    @Getter
    @Setter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Push {
        private String provider;
        private String projectId;
        private String apiKey;
        private String senderId;
        /** Firebase app id by Android package name (the release and debug agents are different apps). */
        private Map<String, String> appIds;
    }
}
