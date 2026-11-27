package com.hmdm.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.rest.json.agent.DesiredConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * <p>"Hardware and ports" of a policy: which device functions are blocked (camera, USB debugging, USB data, NFC,
 * microphone, …), the per-device override on top of it, the validated free list of Android user restrictions, and the
 * per-device status built from what the agent reported. Pure: no DAO, no clock.</p>
 *
 * <p>The Android mechanism of every rule lives in the agent ({@code HardwareCatalog.kt}); the keys and the first
 * Android API level of each one are mirrored here so the console can say "since Android N" and the server never sends
 * a key the agent does not know.</p>
 */
public final class HardwarePolicy {
    public static final String ALLOW = "allow";
    public static final String BLOCK = "block";

    /** How a rule travels in {@code config.apply}. */
    public enum Kind {
        /** {@code hardware.rules.<key>} (agents that know the hardware section). */
        RULE,
        /** {@code policies.<key>} (the on/off toggles every agent since 0.8 applies). */
        TOGGLE,
        /** {@code device.tethering}: shown in the status, set by its own option / per-device override. */
        DEVICE
    }

    public static final class Rule {
        public final String key;
        public final Kind kind;
        /** First Android API level that honours the rule. */
        public final int minSdk;

        Rule(String key, Kind kind, int minSdk) {
            this.key = key;
            this.kind = kind;
            this.minSdk = minSdk;
        }
    }

    public static final String CAMERA = "camera";
    public static final String TETHERING = "tethering";

    /** Every rule, in the order the console shows them. */
    public static final List<Rule> RULES = Collections.unmodifiableList(Arrays.asList(
            new Rule(CAMERA, Kind.RULE, 21),
            new Rule("screenshots", Kind.TOGGLE, 21),
            new Rule("usbStorage", Kind.TOGGLE, 21),
            new Rule("usbDebug", Kind.RULE, 21),
            new Rule("usbData", Kind.RULE, 31),
            new Rule("physicalMedia", Kind.RULE, 21),
            new Rule(TETHERING, Kind.DEVICE, 21),
            new Rule("bluetooth", Kind.TOGGLE, 26),
            new Rule("bluetoothSharing", Kind.RULE, 26),
            new Rule("bluetoothConfig", Kind.RULE, 21),
            new Rule("nfc", Kind.RULE, 35),
            new Rule("nfcBeam", Kind.RULE, 22),
            new Rule("wifiDirect", Kind.RULE, 33),
            new Rule("airplaneMode", Kind.RULE, 28),
            new Rule("networkReset", Kind.RULE, 23),
            new Rule("microphone", Kind.RULE, 21),
            new Rule("locationConfig", Kind.RULE, 28),
            new Rule("locationSharing", Kind.RULE, 21),
            new Rule("dateTime", Kind.RULE, 21),
            new Rule("unknownSources", Kind.RULE, 21),
            new Rule("printing", Kind.RULE, 28),
            new Rule("contentCapture", Kind.RULE, 29),
            new Rule("assistContent", Kind.RULE, 35)));

    private static final Map<String, Rule> BY_KEY = new LinkedHashMap<String, Rule>();

    /**
     * The free "Restricciones de Android" list: {@code UserManager} constant name → value, and value → first API
     * level. Restrictions with their own option in the console are not here on purpose (see {@link #OWN_OPTION}).
     */
    private static final Map<String, Integer> FREE = new LinkedHashMap<String, Integer>();
    private static final Map<String, String> FREE_BY_CONSTANT = new LinkedHashMap<String, String>();
    /** Restrictions a dedicated option manages: refused in the free list so two options never fight. */
    private static final Set<String> OWN_OPTION = new TreeSet<String>(Arrays.asList(
            "no_debugging_features", "no_physical_media", "no_bluetooth_sharing", "no_config_bluetooth", "no_outgoing_beam",
            "no_near_field_communication_radio", "no_unmute_microphone", "no_airplane_mode", "no_wifi_direct", "no_printing",
            "no_network_reset", "no_config_date_time", "no_install_unknown_sources", "no_install_unknown_sources_globally",
            "no_config_location", "no_share_location", "no_content_capture", "no_content_suggestions", "no_assist_content",
            "no_config_tethering", "no_factory_reset", "no_install_apps", "no_bluetooth", "no_usb_file_transfer"));

    private static void free(String constant, String value, int minSdk) {
        FREE.put(value, minSdk);
        FREE_BY_CONSTANT.put(constant, value);
    }

    static {
        for (Rule r : RULES) BY_KEY.put(r.key, r);
        free("DISALLOW_SMS", "no_sms", 21);
        free("DISALLOW_OUTGOING_CALLS", "no_outgoing_calls", 21);
        free("DISALLOW_CONFIG_WIFI", "no_config_wifi", 21);
        free("DISALLOW_CONFIG_MOBILE_NETWORKS", "no_config_mobile_networks", 21);
        free("DISALLOW_CONFIG_VPN", "no_config_vpn", 21);
        free("DISALLOW_CONFIG_CELL_BROADCASTS", "no_config_cell_broadcasts", 21);
        free("DISALLOW_CONFIG_CREDENTIALS", "no_config_credentials", 21);
        free("DISALLOW_MODIFY_ACCOUNTS", "no_modify_accounts", 21);
        free("DISALLOW_ADD_USER", "no_add_user", 21);
        free("DISALLOW_REMOVE_USER", "no_remove_user", 21);
        free("DISALLOW_UNINSTALL_APPS", "no_uninstall_apps", 21);
        free("DISALLOW_APPS_CONTROL", "no_control_apps", 21);
        free("DISALLOW_ADJUST_VOLUME", "no_adjust_volume", 21);
        free("DISALLOW_CREATE_WINDOWS", "no_create_windows", 21);
        free("DISALLOW_CROSS_PROFILE_COPY_PASTE", "no_cross_profile_copy_paste", 21);
        free("DISALLOW_SAFE_BOOT", "no_safe_boot", 23);
        free("DISALLOW_FUN", "no_fun", 23);
        free("DISALLOW_DATA_ROAMING", "no_data_roaming", 24);
        free("DISALLOW_SET_WALLPAPER", "no_set_wallpaper", 24);
        free("DISALLOW_SET_USER_ICON", "no_set_user_icon", 24);
        free("DISALLOW_AUTOFILL", "no_autofill", 26);
        free("DISALLOW_USER_SWITCH", "no_user_switch", 28);
        free("DISALLOW_SYSTEM_ERROR_DIALOGS", "no_system_error_dialogs", 28);
        free("DISALLOW_CONFIG_LOCALE", "no_config_locale", 28);
        free("DISALLOW_CONFIG_BRIGHTNESS", "no_config_brightness", 28);
        free("DISALLOW_CONFIG_SCREEN_TIMEOUT", "no_config_screen_timeout", 28);
        free("DISALLOW_AMBIENT_DISPLAY", "no_ambient_display", 28);
        free("DISALLOW_CONFIG_PRIVATE_DNS", "no_config_private_dns", 29);
        free("DISALLOW_ADD_WIFI_CONFIG", "no_add_wifi_config", 33);
        free("DISALLOW_CHANGE_WIFI_STATE", "no_change_wifi_state", 33);
        free("DISALLOW_WIFI_TETHERING", "no_wifi_tethering", 33);
        free("DISALLOW_SHARING_ADMIN_CONFIGURED_WIFI", "no_sharing_admin_configured_wifi", 33);
        free("DISALLOW_CELLULAR_2G", "no_cellular_2g", 34);
        free("DISALLOW_ULTRA_WIDEBAND_RADIO", "no_ultra_wideband_radio", 34);
        free("DISALLOW_CONFIG_DEFAULT_APPS", "no_config_default_apps", 34);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private HardwarePolicy() {}

    public static Rule rule(String key) {
        return key == null ? null : BY_KEY.get(key);
    }

    /** The free list: restriction value → first Android API level. */
    public static Map<String, Integer> freeRestrictions() {
        return Collections.unmodifiableMap(FREE);
    }

    /**
     * Known keys with a valid value only, sorted; null when none.
     *
     * @param policyLevel true for what a policy stores (only {@link Kind#RULE}: the toggles and tethering have their
     *                    own policy fields); false for a per-device override (rules and toggles)
     */
    public static Map<String, String> clean(Map<String, ?> in, boolean policyLevel) {
        if (in == null) return null;
        Map<String, String> out = new TreeMap<String, String>();
        for (Map.Entry<String, ?> e : in.entrySet()) {
            Rule r = rule(e.getKey() == null ? null : e.getKey().trim());
            if (r == null || r.kind == Kind.DEVICE || (policyLevel && r.kind != Kind.RULE) || e.getValue() == null) continue;
            String v = String.valueOf(e.getValue()).trim().toLowerCase(Locale.ROOT);
            if (ALLOW.equals(v) || BLOCK.equals(v)) out.put(r.key, v);
        }
        return out.isEmpty() ? null : out;
    }

    /** A stored per-device override (JSON object) → cleaned map; empty for null/invalid. */
    public static Map<String, String> parseOverride(String json) {
        if (json == null || json.trim().isEmpty()) return Collections.emptyMap();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = JSON.readValue(json, Map.class);
            Map<String, String> c = clean(raw, false);
            return c == null ? Collections.<String, String>emptyMap() : c;
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    public static String toJson(Map<String, String> rules) {
        try {
            return JSON.writeValueAsString(new TreeMap<String, String>(rules));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** What the free "Restricciones de Android" field holds: the accepted values and what was refused. */
    public static final class Restrictions {
        /** Accepted {@code no_*} values, sorted and unique. */
        public final List<String> accepted;
        /** Entries that are not on the list (typos, unknown or dangerous keys): never sent to a device. */
        public final List<String> unknown;
        /** Entries that have their own option in the console: not sent from here either. */
        public final List<String> ownOption;

        Restrictions(List<String> accepted, List<String> unknown, List<String> ownOption) {
            this.accepted = accepted;
            this.unknown = unknown;
            this.ownOption = ownOption;
        }
    }

    /**
     * Parses the comma / space / line separated field. Each entry may be the {@code UserManager} constant
     * ({@code DISALLOW_SMS}) or its value ({@code no_sms}).
     */
    public static Restrictions parseRestrictions(String field) {
        Set<String> accepted = new TreeSet<String>();
        List<String> unknown = new ArrayList<String>();
        List<String> own = new ArrayList<String>();
        if (field != null) {
            for (String raw : field.split("[,;\\s]+")) {
                String t = raw.trim();
                if (t.isEmpty()) continue;
                String value = FREE_BY_CONSTANT.get(t.toUpperCase(Locale.ROOT).replaceFirst("^USERMANAGER\\.", ""));
                if (value == null) value = t.toLowerCase(Locale.ROOT);
                if (FREE.containsKey(value)) accepted.add(value);
                else if (OWN_OPTION.contains(value) && !own.contains(value)) own.add(value);
                else if (!OWN_OPTION.contains(value) && !unknown.contains(t) && unknown.size() < 50) unknown.add(t.length() > 60 ? t.substring(0, 60) : t);
            }
        }
        return new Restrictions(new ArrayList<String>(accepted), unknown, own);
    }

    /** The policy's rules with the device's override on top (the device wins); never null. */
    public static Map<String, String> effective(Map<String, String> policy, Map<String, String> override) {
        Map<String, String> out = new TreeMap<String, String>();
        if (policy != null) out.putAll(policy);
        if (override != null) out.putAll(override);
        return out;
    }

    // ---- status ----

    /** One row of a device's "Restricciones aplicadas". */
    public static final class Item {
        public String key;
        public String kind;
        public int minSdk;
        /** What the policy says: allow / block / null (not managed). */
        public String policy;
        /** The per-device override: allow / block / null (follows the policy). */
        public String override;
        /** What the device was asked: allow / block / null. */
        public String desired;
        /**
         * What the device reported: {@code applied}, {@code unsupported: <why>}, {@code failed: <why>},
         * {@code pending} (not applied yet) or null (nothing asked).
         */
        public String outcome;
    }

    public static final class Status {
        public List<Item> items = new ArrayList<Item>();
        /** Free restrictions sent to the device: key = {@code no_*}. */
        public List<Item> restrictions = new ArrayList<Item>();
        /** Entries of the policy's free field that are not sent (not on the list). */
        public List<String> unknownRestrictions = new ArrayList<String>();
        /** Entries of the free field that have their own option. */
        public List<String> ownOptionRestrictions = new ArrayList<String>();
        /** True when the device already reported on the current document. */
        public boolean reported;
        public Long reportedAt;
    }

    /** {@code unsupported: agent}: the agent on the phone is older than this rule. */
    public static final String OUTCOME_OLD_AGENT = "unsupported: agent";
    public static final String OUTCOME_PENDING = "pending";

    /**
     * @param policy        the policy's hardware rules (cleaned)
     * @param override      the device's override (cleaned)
     * @param doc           the document the device should have applied (null = no policy)
     * @param resultDetail  {@code detail} of the device's latest {@code config.apply} (JSON with revision + outcomes)
     * @param restrictions  the policy's free field
     */
    public static Status status(Map<String, String> policy, Map<String, String> override, DesiredConfig doc,
                                String resultDetail, String restrictions) {
        Status s = new Status();
        Map<String, String> outcomes = null;
        try {
            JsonNode n = resultDetail == null || resultDetail.trim().isEmpty() ? null : JSON.readTree(resultDetail);
            if (n != null && doc != null && doc.getRevision() != null && doc.getRevision().equals(n.path("revision").asText(null))
                    && n.path("outcomes").isObject()) {
                outcomes = new LinkedHashMap<String, String>();
                for (Iterator<Map.Entry<String, JsonNode>> it = n.path("outcomes").fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> e = it.next();
                    outcomes.put(e.getKey(), e.getValue().asText());
                }
            }
        } catch (Exception ignored) {
            outcomes = null;
        }
        s.reported = outcomes != null;
        Map<String, Boolean> toggles = doc == null || doc.getPolicies() == null ? Collections.<String, Boolean>emptyMap() : doc.getPolicies();
        Map<String, String> rules = doc == null || doc.getHardware() == null || doc.getHardware().getRules() == null
                ? Collections.<String, String>emptyMap() : doc.getHardware().getRules();
        for (Rule r : RULES) {
            Item i = new Item();
            i.key = r.key;
            i.kind = r.kind.name().toLowerCase(Locale.ROOT);
            i.minSdk = r.minSdk;
            i.override = override == null ? null : override.get(r.key);
            String reportedAs;
            switch (r.kind) {
                case TOGGLE: {
                    Boolean on = toggles.get(r.key);
                    i.desired = on == null ? null : on ? ALLOW : BLOCK;
                    // The policy's own value is whatever is asked when no override is in the way.
                    i.policy = i.override == null ? i.desired : null;
                    reportedAs = "policies." + r.key;
                    break;
                }
                case DEVICE: {
                    String t = doc == null || doc.getDevice() == null ? null : doc.getDevice().getTethering();
                    i.desired = BLOCK.equals(t) ? BLOCK : ALLOW.equals(t) ? ALLOW : null;
                    i.policy = i.desired;
                    reportedAs = "device";
                    break;
                }
                default:
                    i.policy = policy == null ? null : policy.get(r.key);
                    i.desired = rules.get(r.key);
                    reportedAs = "hardware." + r.key;
            }
            if (i.desired != null) {
                if (outcomes == null) i.outcome = OUTCOME_PENDING;
                else {
                    i.outcome = outcomes.get(reportedAs);
                    // Agents before the hardware section still apply the camera through its toggle.
                    if (i.outcome == null && CAMERA.equals(r.key)) i.outcome = outcomes.get("policies." + CAMERA);
                    if (i.outcome == null) i.outcome = OUTCOME_OLD_AGENT;
                }
            }
            s.items.add(i);
        }
        Restrictions parsed = parseRestrictions(restrictions);
        s.unknownRestrictions = parsed.unknown;
        s.ownOptionRestrictions = parsed.ownOption;
        List<String> sent = doc == null || doc.getHardware() == null || doc.getHardware().getRestrictions() == null
                ? Collections.<String>emptyList() : doc.getHardware().getRestrictions();
        for (String key : sent) {
            Item i = new Item();
            i.key = key;
            i.kind = "restriction";
            i.minSdk = FREE.containsKey(key) ? FREE.get(key) : 21;
            i.policy = BLOCK;
            i.desired = BLOCK;
            String o = outcomes == null ? null : outcomes.get("restrictions." + key);
            i.outcome = outcomes == null ? OUTCOME_PENDING : o == null ? OUTCOME_OLD_AGENT : o;
            s.restrictions.add(i);
        }
        return s;
    }
}
