package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * App-policy section of {@link DesiredConfig} (the agent's ConfigAppPolicy). In {@code allowlist} mode the agent
 * suspends every user-installed app outside {@code allowed} and the resolved {@code roles}.
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DesiredAppPolicy {
    private String mode;
    private List<String> allowed;
    private List<String> roles;
    private Boolean hidePlayStore;
}
