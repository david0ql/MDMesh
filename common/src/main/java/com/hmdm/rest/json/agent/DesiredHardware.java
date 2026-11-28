package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * Hardware and ports section of {@code config.apply}: {@code rules} (rule key → {@code allow} | {@code block}, see
 * {@link com.hmdm.util.HardwarePolicy}) and {@code restrictions} (validated extra {@code UserManager.DISALLOW_*}
 * values). Agents that do not know the section ignore it.
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DesiredHardware {
    private Map<String, String> rules;
    private List<String> restrictions;
}
