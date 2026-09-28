package com.hmdm.persistence.domain;

/** Insert parameter for a new group; {@link #id} is filled in by the insert. */
public class DeviceGroupInsert {
    private Integer id;
    private String name;
    private int customerId;
    private Integer configurationId;
    private Integer parentId;

    public Integer getParentId() { return parentId; }
    public void setParentId(Integer parentId) { this.parentId = parentId; }

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getCustomerId() { return customerId; }
    public void setCustomerId(int customerId) { this.customerId = customerId; }
    public Integer getConfigurationId() { return configurationId; }
    public void setConfigurationId(Integer configurationId) { this.configurationId = configurationId; }
}
