package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Mobile-data section of {@link DesiredConfig} (the agent's {@code ConfigMobileData}): which apps may use mobile data
 * ({@code open} | {@code blocklist} | {@code allowlist} + {@code apps}), how it is enforced ({@code mechanism}: absent =
 * automatic, {@code dpm}, {@code vpn}) and the limits of the period ({@code period}: absent = monthly from
 * {@code cutDay}, or {@code daily}). Limits are in bytes. Built by {@link com.hmdm.util.MobileDataPolicy} from the
 * policy and the device's own override; absent = the device's mobile data is not managed.
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DesiredMobileData {
    private String mode;
    private List<String> apps;
    private String mechanism;
    private String period;
    private Integer cutDay;
    private Integer warnPercent;
    private Long deviceLimitBytes;
    private List<Limit> appLimits;
    /** Apps that keep mobile data when the device limit is spent. */
    private List<String> essential;

    @Getter
    @Setter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Limit {
        private String pkg;
        private Long bytes;
    }
}
