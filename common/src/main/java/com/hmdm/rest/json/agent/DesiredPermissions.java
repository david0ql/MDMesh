package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * App permissions of a {@code config.apply} document (the agent's {@code ConfigPermissions}): the default answer to
 * any runtime-permission request ({@code grant} / {@code prompt} / {@code deny}) and, per managed app, the permissions
 * the Device Owner fixes (only apps that carry a rule are listed).
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DesiredPermissions {
    private String defaultPolicy;
    private List<App> apps;

    @Getter
    @Setter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class App {
        private String packageName;
        /** Preset for every runtime permission the app declares. */
        private String all;
        /** Permission group to rule; wins over {@link #all}. */
        private Map<String, String> permissions;
        /** Special accesses the app should have. */
        private List<String> special;
    }
}
