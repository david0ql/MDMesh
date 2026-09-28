package com.hmdm.persistence.domain;

/**
 * One device with what decides its configuration: its own (pinned or not), its group's and the group itself.
 * The effective configuration is the device's own when pinned, else the group's, else the global default.
 */
public class DeviceScopeRow {
    private Integer id;
    private String number;
    private Integer configurationId;
    private boolean configurationPinned;
    private Integer groupId;
    private String groupName;
    private Integer groupConfigurationId;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getNumber() { return number; }
    public void setNumber(String number) { this.number = number; }
    public Integer getConfigurationId() { return configurationId; }
    public void setConfigurationId(Integer configurationId) { this.configurationId = configurationId; }
    public boolean isConfigurationPinned() { return configurationPinned; }
    public void setConfigurationPinned(boolean configurationPinned) { this.configurationPinned = configurationPinned; }
    public Integer getGroupId() { return groupId; }
    public void setGroupId(Integer groupId) { this.groupId = groupId; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    public Integer getGroupConfigurationId() { return groupConfigurationId; }
    public void setGroupConfigurationId(Integer groupConfigurationId) { this.groupConfigurationId = groupConfigurationId; }
}
