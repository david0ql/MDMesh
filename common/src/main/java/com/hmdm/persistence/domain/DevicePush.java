package com.hmdm.persistence.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

/**
 * One row of {@code device_push}: how a device can be woken when its live channel is closed. The agent reports the
 * token and the phone's own limits (battery exemption, exact alarms); the server adds what happened to its last push.
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DevicePush {
    private String deviceNumber;
    private Long updatedAt;
    /** {@code fcm}, or null when the agent has no push channel. */
    private String provider;
    /** The registration token. Never sent to the console. */
    @JsonIgnore
    private String token;
    private Long tokenAt;
    /** {@code ready} | {@code unconfigured} | {@code unavailable} (no Google services) | {@code error}. */
    private String state;
    private String error;
    private Boolean batteryExempt;
    private Boolean exactAlarms;
    private Boolean bgRestricted;
    /** Minutes between the agent's own backup check-ins, as it runs them now. */
    private Integer pollMinutes;
    /** What triggered the agent's last check-in: push, socket, alarm, screen, unlock, power, network, worker, boot. */
    private String lastWokenBy;
    private Long lastSentAt;
    private Boolean lastSentOk;
    private String lastSendError;
    private Long lastAnsweredAt;
    /** Milliseconds from the last push to the check-in that answered it. */
    private Long lastWakeMs;
}
