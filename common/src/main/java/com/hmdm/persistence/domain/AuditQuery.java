/*
 *
 * DallyControl — filters of an audit trail search. Everything is optional except the customer.
 *
 */

package com.hmdm.persistence.domain;

import java.util.List;

public class AuditQuery {
    public int customerId;
    /** Rows without a customer (a failed sign-in of an unknown account, system rows) are shown too. */
    public boolean includeNoCustomer;
    public Long from;
    public Long to;
    public Integer userId;
    public String category;
    public String action;
    public String result;
    public String targetType;
    public String targetId;
    /** Rows that touched this device. */
    public String deviceNumber;
    /** Rows that touched this folder. */
    public Integer groupId;
    /** Free text over technician, action, target, IP and detail (already wrapped in %…%). */
    public String q;

    /** Folder administrator: only rows of these folders (int[] literal) or made by these users. Null = no limit. */
    public String scopeGroups;
    public List<Integer> scopeUsers;

    public int limit = 50;
    public int offset = 0;

    public int getCustomerId() { return customerId; }
    public boolean isIncludeNoCustomer() { return includeNoCustomer; }
    public Long getFrom() { return from; }
    public Long getTo() { return to; }
    public Integer getUserId() { return userId; }
    public String getCategory() { return category; }
    public String getAction() { return action; }
    public String getResult() { return result; }
    public String getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public String getDeviceNumber() { return deviceNumber; }
    public Integer getGroupId() { return groupId; }
    public String getQ() { return q; }
    public String getScopeGroups() { return scopeGroups; }
    public List<Integer> getScopeUsers() { return scopeUsers; }
    public int getLimit() { return limit; }
    public int getOffset() { return offset; }
}
