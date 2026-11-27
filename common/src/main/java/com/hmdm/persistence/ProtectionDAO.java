/*
 *
 * DallyControl — factory-reset protection and remote wipe DAO (not part of upstream Headwind). Thin wrapper over
 * ProtectionMapper; everything is scoped by customerId except the check-in path, which starts from an
 * authenticated device number.
 *
 */

package com.hmdm.persistence;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hmdm.persistence.mapper.ProtectionMapper;
import com.hmdm.util.FrpAccounts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Singleton
public class ProtectionDAO {

    /** groupId of the company-wide default in frp_default. */
    public static final int COMPANY = 0;
    private final ProtectionMapper mapper;

    @Inject
    public ProtectionDAO(ProtectionMapper mapper) {
        this.mapper = mapper;
    }

    // --- default accounts ----------------------------------------------------------------------------------------

    public List<FrpAccounts.Account> companyDefault(int customerId) {
        return FrpAccounts.parse(mapper.findDefault(customerId, COMPANY));
    }

    public List<FrpAccounts.Account> folderDefault(int customerId, int groupId) {
        return FrpAccounts.parse(mapper.findDefault(customerId, groupId));
    }

    /** Every stored default of the customer, by groupId ({@link #COMPANY} = the company's). */
    public Map<Integer, List<FrpAccounts.Account>> defaults(int customerId) {
        Map<Integer, List<FrpAccounts.Account>> out = new LinkedHashMap<Integer, List<FrpAccounts.Account>>();
        for (Map<String, Object> row : mapper.listDefaults(customerId)) {
            List<FrpAccounts.Account> accounts = FrpAccounts.parse((String) row.get("accounts"));
            if (!accounts.isEmpty()) out.put(((Number) row.get("groupid")).intValue(), accounts);
        }
        return out;
    }

    /**
     * @param accountsJson the normalized list ({@link FrpAccounts#normalize}); null removes the default
     */
    public void saveDefault(int customerId, int groupId, String accountsJson, String by) {
        if (accountsJson == null) mapper.deleteDefault(customerId, groupId);
        else mapper.saveDefault(customerId, groupId, accountsJson, System.currentTimeMillis(), by);
    }

    public boolean groupExists(int customerId, int groupId) {
        return mapper.countGroup(customerId, groupId) > 0;
    }

    /** The accounts of the folders above a device, nearest first. */
    public List<FrpAccounts.FolderAccounts> deviceFolderDefaults(int customerId, int deviceId) {
        List<FrpAccounts.FolderAccounts> out = new ArrayList<FrpAccounts.FolderAccounts>();
        for (Map<String, Object> row : mapper.listDeviceFolderDefaults(customerId, deviceId)) {
            List<FrpAccounts.Account> accounts = FrpAccounts.parse((String) row.get("accounts"));
            if (accounts.isEmpty()) continue;
            out.add(new FrpAccounts.FolderAccounts(((Number) row.get("groupid")).intValue(),
                    (String) row.get("groupname"), accounts));
        }
        return out;
    }

    // --- re-enrollment after a reset -----------------------------------------------------------------------------

    public Map<String, Object> findDeviceByIdentity(int customerId, String serial, String imei) {
        if (serial == null && imei == null) return null;
        return mapper.findDeviceByIdentity(customerId, serial, imei);
    }

    public Map<String, Object> findTombstoneByIdentity(int customerId, String serial, String imei) {
        if (serial == null && imei == null) return null;
        return mapper.findTombstoneByIdentity(customerId, serial, imei,
                System.currentTimeMillis() - AgentCommandDAO.TOMBSTONE_TTL_MS);
    }

    // --- the wiped mark ------------------------------------------------------------------------------------------

    public String commandType(String deviceNumber, int commandId) {
        return mapper.commandType(deviceNumber, commandId);
    }

    public void markWiped(String deviceNumber, int commandId, long at) {
        mapper.markWiped(deviceNumber, commandId, at);
    }

    public void unmarkWiped(String deviceNumber) {
        mapper.unmarkWiped(deviceNumber);
    }
}
