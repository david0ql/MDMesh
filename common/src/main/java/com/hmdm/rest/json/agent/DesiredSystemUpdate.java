package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

/**
 * Android system (OTA) update policy of {@link DesiredConfig}: {@code automatic} (install as soon as available),
 * {@code windowed} (install inside [fromMinutes, toMinutes) of the day) or {@code postpone} (hold 30 days).
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DesiredSystemUpdate {
    private String type;
    private Integer fromMinutes;
    private Integer toMinutes;
}
