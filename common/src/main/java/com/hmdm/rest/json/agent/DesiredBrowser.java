package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Managed-browser section of {@link DesiredConfig} (the agent's ConfigBrowser): Chrome's managed URL lists.
 * {@code mode}: {@code open}, {@code allowlist} (only {@code allow}), {@code blocklist} (all but {@code block}).
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DesiredBrowser {
    private String mode;
    private List<String> allow;
    private List<String> block;
    /** Company page: Chrome's home page and where address-bar searches go. */
    private String homeUrl;
}
