package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

/**
 * <p>Connection diagnostics an agent piggybacks on a check-in (optional; agents up to 0.9.8 do not send it): the
 * last network failure since its previous delivered report, how many times the wake socket reconnected, and why the
 * device went silent. Lets the console explain a gap instead of guessing.</p>
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentConnectionReport {

    private Integer reconnects;
    private Failure lastFailure;
    private Stop lastStop;
    private String serverHost;
    private Boolean usingBackup;

    @Getter
    @Setter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Failure {
        /** dns | tls | timeout | connect | http | wsUpgrade | rejected | other */
        private String kind;
        private Long at;
        private String detail;
        private Integer httpCode;
        /** checkin | socket */
        private String channel;
        private String network;
        private String ssid;
        private String host;
        private String certIssuer;
        private Integer count;
    }

    @Getter
    @Setter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Stop {
        /** shutdown | powerLost | networkLost | processKilled | suspended | serverUnreachable | unknown */
        private String reason;
        private Long at;
        /** When the device came back; null in the goodbye report sent while shutting down. */
        private Long resumedAt;
        private Integer battery;
    }
}
