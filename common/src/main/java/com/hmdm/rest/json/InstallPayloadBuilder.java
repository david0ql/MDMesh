package com.hmdm.rest.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builds an agent-v1 {@code app.install} payload. Split bundles carry {@code parts:[{url,sha256}]}
 * (the agent installs all parts in one PackageInstaller session, verifying each part's HEX sha256);
 * a single APK carries {@code url}. The caller must pass a non-blank {@code url} OR {@code partsJson}.
 */
public final class InstallPayloadBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InstallPayloadBuilder() {}

    public static String build(String packageName, int versionCode, String url, String partsJson) {
        return build(packageName, versionCode, null, url, partsJson);
    }

    /**
     * With the version's name: apps that ship new builds under the same version code (only the name changes) are
     * then installed again by the agent instead of being taken as "already at that version".
     */
    public static String build(String packageName, int versionCode, String versionName, String url, String partsJson) {
        try {
            ObjectNode p = MAPPER.createObjectNode();
            if (partsJson != null && !partsJson.trim().isEmpty()) {
                p.put("packageName", packageName);
                if (versionCode > 0) {
                    p.put("versionCode", versionCode);
                }
                putName(p, versionName);
                p.set("parts", MAPPER.readTree(partsJson));
            } else {
                p.put("url", url);
                p.put("packageName", packageName);
                if (versionCode > 0) {
                    p.put("versionCode", versionCode);
                }
                putName(p, versionName);
            }
            return p.toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("bad install payload input", e);
        }
    }

    private static void putName(ObjectNode p, String versionName) {
        if (versionName != null && !versionName.trim().isEmpty() && !"0".equals(versionName.trim())) {
            p.put("versionName", versionName.trim());
        }
    }
}
