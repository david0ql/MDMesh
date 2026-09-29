package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

/** A Wi-Fi network of {@link DesiredConfig} the agent saves on the device (the agent's ConfigWifi). */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DesiredWifi {
    private String ssid;
    private String password;
    private String security;
    private Boolean hidden;
}
