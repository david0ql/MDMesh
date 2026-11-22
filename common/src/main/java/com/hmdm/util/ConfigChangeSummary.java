/*
 *
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hmdm.util;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Builds the {@code changesJson} snapshot stored in {@code config_audit}: a full snapshot of the
 * configuration (enough to see/reconstruct what it was) plus, for an update, a per-field diff of
 * what changed. Pure and defensive: never throws into the save path.
 *
 * <p>Shape: {@code {"diff": {field: {"from": <old>, "to": <new>}}, "snapshot": {<full after/last-known>}}}.
 * {@code diff} is omitted for create/delete.</p>
 */
public final class ConfigChangeSummary {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Volatile/derived fields that are noise in a human diff. */
    private static final TreeSet<String> IGNORED = new TreeSet<>(java.util.Arrays.asList(
            "baseUrl", "qrCodeKey"));

    private ConfigChangeSummary() {}

    /**
     * @param before the configuration before the change (null for a create)
     * @param after  the configuration after the change (the last-known row for a delete)
     * @return a JSON string, or {@code null} if nothing could be serialised
     */
    public static String build(Object before, Object after) {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            Map<String, Object> beforeMap = before == null ? null : toMap(before);
            Map<String, Object> afterMap = after == null ? null : toMap(after);
            if (beforeMap != null && afterMap != null) {
                out.put("diff", diff(beforeMap, afterMap));
            }
            // Snapshot: the resulting state (after), or the last-known state (before) on a delete.
            out.put("snapshot", afterMap != null ? afterMap : beforeMap);
            return MAPPER.writeValueAsString(out);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(Object o) {
        return MAPPER.convertValue(o, Map.class);
    }

    /** Per top-level field: the ones whose value differs, with their old/new values. */
    private static Map<String, Object> diff(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> changes = new LinkedHashMap<>();
        TreeSet<String> keys = new TreeSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        for (String k : keys) {
            if (IGNORED.contains(k)) continue;
            Object a = before.get(k);
            Object b = after.get(k);
            if (!Objects.equals(a, b)) {
                Map<String, Object> fromTo = new LinkedHashMap<>();
                fromTo.put("from", a);
                fromTo.put("to", b);
                changes.put(k, fromTo);
            }
        }
        return changes;
    }
}
