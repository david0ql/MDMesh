package com.hmdm.util;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.rest.json.agent.DesiredDevice;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * <p>The company Google accounts that may set a phone up again after a factory reset done from recovery (factory
 * reset protection, FRP). Android wants each account's numeric id, not its e-mail; the console keeps both so people
 * recognise the account.</p>
 *
 * <p>Where the accounts of a device come from, nearest first: its policy's own accounts, the nearest folder above
 * the device that has some, the company's default. A policy may also switch the protection off.</p>
 *
 * <p>Pure: no I/O.</p>
 */
public final class FrpAccounts {

    public static final int MAX_ACCOUNTS = 10;
    /** A Google account id: digits only (21 today; a range is accepted so a longer id never locks anyone out). */
    private static final Pattern ID = Pattern.compile("^[0-9]{6,30}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[a-z0-9]([a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,}$");
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String SOURCE_POLICY = "policy";
    public static final String SOURCE_FOLDER = "folder";
    public static final String SOURCE_COMPANY = "company";
    public static final String SOURCE_NONE = "none";
    public static final String SOURCE_OFF = "off";

    public static final String MODE_OFF = "off";

    private FrpAccounts() {
    }

    @Getter
    @Setter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Account {
        private String email;
        private String id;

        public Account() {
        }

        public Account(String email, String id) {
            this.email = email;
            this.id = id;
        }
    }

    /** The accounts of one folder, as read from the folder chain. */
    public static final class FolderAccounts {
        public final Integer groupId;
        public final String groupName;
        public final List<Account> accounts;

        public FolderAccounts(Integer groupId, String groupName, List<Account> accounts) {
            this.groupId = groupId;
            this.groupName = groupName;
            this.accounts = accounts == null ? Collections.<Account>emptyList() : accounts;
        }
    }

    /** The accounts a device gets and where they come from. */
    public static final class Effective {
        public final List<Account> accounts;
        /** {@link #SOURCE_POLICY}, {@link #SOURCE_FOLDER}, {@link #SOURCE_COMPANY}, {@link #SOURCE_NONE} or {@link #SOURCE_OFF}. */
        public final String source;
        public final Integer groupId;
        public final String groupName;

        Effective(List<Account> accounts, String source, Integer groupId, String groupName) {
            this.accounts = accounts;
            this.source = source;
            this.groupId = groupId;
            this.groupName = groupName;
        }

        public List<String> ids() {
            List<String> out = new ArrayList<String>();
            for (Account a : accounts) out.add(a.getId());
            return out;
        }
    }

    public static boolean validId(String id) {
        return id != null && ID.matcher(id.trim()).matches();
    }

    public static boolean validEmail(String email) {
        return email != null && email.length() <= 200 && EMAIL.matcher(email.trim().toLowerCase(Locale.ROOT)).matches();
    }

    /** A stored list; anything unreadable or invalid is dropped (a stored value never breaks a device). */
    public static List<Account> parse(String json) {
        if (json == null || json.trim().isEmpty()) return new ArrayList<Account>();
        try {
            return clean(JSON.readValue(json, new TypeReference<List<Account>>() { }), false);
        } catch (Exception e) {
            return new ArrayList<Account>();
        }
    }

    /**
     * What a save stores: the validated list as JSON, or null when it is empty.
     *
     * @throws IllegalArgumentException naming the first entry that is not valid (an id that is not numeric — an
     *                                  e-mail typed in its place — or a malformed e-mail)
     */
    public static String normalize(List<Account> accounts) {
        List<Account> ok = clean(accounts, true);
        if (ok.isEmpty()) return null;
        try {
            return JSON.writeValueAsString(ok);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Account> clean(List<Account> in, boolean strict) {
        List<Account> out = new ArrayList<Account>();
        if (in == null) return out;
        List<String> seen = new ArrayList<String>();
        for (Account a : in) {
            if (a == null) continue;
            String id = a.getId() == null ? "" : a.getId().trim();
            String email = a.getEmail() == null ? "" : a.getEmail().trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty() && email.isEmpty()) continue;
            if (!validId(id)) {
                if (strict) throw new IllegalArgumentException("id:" + (email.isEmpty() ? id : email));
                continue;
            }
            if (!email.isEmpty() && !validEmail(email)) {
                if (strict) throw new IllegalArgumentException("email:" + email);
                email = "";
            }
            if (seen.contains(id)) continue;
            if (out.size() >= MAX_ACCOUNTS) {
                if (strict) throw new IllegalArgumentException("max:" + MAX_ACCOUNTS);
                break;
            }
            seen.add(id);
            out.add(new Account(email.isEmpty() ? null : email, id));
        }
        return out;
    }

    /**
     * @param policy  the policy's device rules (may be null)
     * @param labels  the e-mails of the policy's own account ids (id -&gt; e-mail), may be null
     * @param folders the device's folder chain, nearest first
     * @param company the company's default accounts
     */
    public static Effective resolve(DesiredDevice policy, Map<String, String> labels, List<FolderAccounts> folders,
                                    List<Account> company) {
        if (policy != null && MODE_OFF.equals(policy.getFrpMode())) {
            return new Effective(Collections.<Account>emptyList(), SOURCE_OFF, null, null);
        }
        if (policy != null && policy.getFrpAccounts() != null && !policy.getFrpAccounts().isEmpty()) {
            List<Account> own = new ArrayList<Account>();
            for (String id : policy.getFrpAccounts()) own.add(new Account(labels == null ? null : labels.get(id), id));
            return new Effective(own, SOURCE_POLICY, null, null);
        }
        if (folders != null) {
            for (FolderAccounts f : folders) {
                if (f != null && !f.accounts.isEmpty()) return new Effective(f.accounts, SOURCE_FOLDER, f.groupId, f.groupName);
            }
        }
        if (company != null && !company.isEmpty()) return new Effective(company, SOURCE_COMPANY, null, null);
        return new Effective(Collections.<Account>emptyList(), SOURCE_NONE, null, null);
    }
}
