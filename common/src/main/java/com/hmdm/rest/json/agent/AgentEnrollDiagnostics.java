package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

/**
 * <p>What the agent knows about its own enrollment when it calls {@code /enroll}: the provisioning path it came
 * through and the failures it had before this call (it could not report them while the server was unreachable).</p>
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentEnrollDiagnostics {

    /** qr-modern (Android 12+), qr-legacy, adb, code (typed on the phone) or unknown. */
    private String path;

    /** Enroll calls made so far, this one included. */
    private Integer attempts;

    /** Reason code of the last failure before this call (clock, no-network, captive-portal, tls, timeout, ...). */
    private String lastError;

    private String lastErrorDetail;

    private Long lastErrorAt;

    public AgentEnrollDiagnostics() {
    }
}
