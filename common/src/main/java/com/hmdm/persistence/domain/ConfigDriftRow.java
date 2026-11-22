package com.hmdm.persistence.domain;

import lombok.Getter;
import lombok.Setter;

/**
 * One device of a configuration with everything needed to tell whether it has applied the
 * configuration's current desired-state revision — used to list the devices out of compliance.
 */
@Getter
@Setter
public class ConfigDriftRow {
    private String deviceNumber;
    private String description;
    private String capabilitiesJson;
    private String appliedConfigRevision;
    private Long appliedConfigAt;
    private Long lastUpdate;
}
