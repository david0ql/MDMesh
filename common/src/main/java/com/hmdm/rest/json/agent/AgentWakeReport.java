package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

/**
 * <p>What an agent tells about the ways it can be woken, piggybacked on a check-in (optional; agents before the push
 * channel do not send it): its push token, and the phone's own limits on background work.</p>
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentWakeReport {
    /** {@code fcm}; null when the agent has no push channel. */
    private String provider;
    /** Registration token; null while there is none. */
    private String token;
    /** ready | unconfigured | unavailable | error */
    private String state;
    private String error;
    /** The agent is exempt from battery optimization (Doze does not hold its network or alarms). */
    private Boolean batteryExempt;
    /** The agent may schedule exact alarms. */
    private Boolean exactAlarms;
    /** The person (or the maker's battery manager) restricted the agent's background activity. */
    private Boolean bgRestricted;
    /** Minutes between backup check-ins, as scheduled now. */
    private Integer pollMinutes;
    /** What triggered this check-in: push | socket | alarm | screen | unlock | power | network | worker | boot | other. */
    private String wokenBy;
}
