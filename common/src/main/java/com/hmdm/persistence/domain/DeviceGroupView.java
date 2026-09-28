package com.hmdm.persistence.domain;

/** A group (company) with its configuration and how many devices it holds. */
public class DeviceGroupView {
    private Integer id;
    private String name;
    /** Parent folder; null = top level. */
    private Integer parentId;
    /** The group's own configuration (null = inherits). */
    private Integer configurationId;
    private String configurationName;
    /** What its devices run: its own configuration or the nearest ancestor's (null = the global one). */
    private Integer effectiveConfigurationId;
    private String effectiveConfigurationName;
    private int deviceCount;

    public Integer getParentId() { return parentId; }
    public void setParentId(Integer parentId) { this.parentId = parentId; }
    public Integer getEffectiveConfigurationId() { return effectiveConfigurationId; }
    public void setEffectiveConfigurationId(Integer effectiveConfigurationId) { this.effectiveConfigurationId = effectiveConfigurationId; }
    public String getEffectiveConfigurationName() { return effectiveConfigurationName; }
    public void setEffectiveConfigurationName(String effectiveConfigurationName) { this.effectiveConfigurationName = effectiveConfigurationName; }

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getConfigurationId() { return configurationId; }
    public void setConfigurationId(Integer configurationId) { this.configurationId = configurationId; }
    public String getConfigurationName() { return configurationName; }
    public void setConfigurationName(String configurationName) { this.configurationName = configurationName; }
    public int getDeviceCount() { return deviceCount; }
    public void setDeviceCount(int deviceCount) { this.deviceCount = deviceCount; }
}
