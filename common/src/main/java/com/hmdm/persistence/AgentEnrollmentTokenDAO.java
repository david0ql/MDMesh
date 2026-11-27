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

package com.hmdm.persistence;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hmdm.persistence.domain.AgentEnrollmentToken;
import com.hmdm.persistence.mapper.AgentEnrollmentTokenMapper;

/**
 * <p>DAO for the agent v1 single-use enrollment tokens.</p>
 */
@Singleton
public class AgentEnrollmentTokenDAO {

    private final AgentEnrollmentTokenMapper mapper;

    @Inject
    public AgentEnrollmentTokenDAO(AgentEnrollmentTokenMapper mapper) {
        this.mapper = mapper;
    }

    public void insert(AgentEnrollmentToken token) {
        mapper.insert(token);
    }

    public AgentEnrollmentToken findByToken(String token) {
        return mapper.findByToken(token);
    }

    /** Atomic single-use claim; true = this caller owns the token. */
    public boolean claim(Integer id) {
        return mapper.claim(id, System.currentTimeMillis()) == 1;
    }

    /** Un-burn a claimed token after a server-side enrollment failure. */
    public void release(Integer id) {
        mapper.release(id);
    }

    /** One more enrollment through a reusable code; false = revoked or expired meanwhile. */
    public boolean claimReusable(Integer id) {
        return mapper.claimReusable(id, System.currentTimeMillis()) == 1;
    }

    public void releaseReusable(Integer id) {
        mapper.releaseReusable(id);
    }

    public java.util.List<com.hmdm.persistence.domain.EnrollmentCodeView> listCodes(int customerId) {
        return mapper.listCodes(customerId);
    }

    public boolean revoke(int customerId, int id) {
        return mapper.revoke(customerId, id) == 1;
    }

    public boolean deleteRevoked(int customerId, int id) {
        return mapper.deleteRevoked(customerId, id) == 1;
    }

    // --- Enrollment reliability: retries by the same device, and the attempts log ---

    public void updateWifiHidden(Integer id, boolean hidden) {
        mapper.updateWifiHidden(id, hidden);
    }

    public void recordClaim(Integer id, String hardwareId, String serial, String imei) {
        mapper.recordClaim(id, System.currentTimeMillis(), hardwareId, serial, imei);
    }

    public void recordDevice(Integer id, String deviceNumber) {
        mapper.recordDevice(id, deviceNumber);
    }

    /** The one token starting with [prefix]; null when none or ambiguous. */
    public AgentEnrollmentToken findByPrefix(String prefix) {
        if (prefix == null || !prefix.matches("^[A-Za-z0-9-]{8,36}$")) {
            return null;
        }
        java.util.List<AgentEnrollmentToken> found = mapper.findByPrefix(prefix);
        return found.size() == 1 ? found.get(0) : null;
    }

    /** Log an enrollment attempt; a repeat of the last one within ten minutes only raises its counter. */
    public void logAttempt(com.hmdm.persistence.domain.AgentEnrollAttempt attempt) {
        if (mapper.bumpAttempt(attempt) == 0) {
            mapper.insertAttempt(attempt);
            // Housekeeping on the insert path: nothing older than 60 days is of use to anyone.
            if (java.util.concurrent.ThreadLocalRandom.current().nextInt(50) == 0) {
                mapper.purgeAttempts(System.currentTimeMillis() - 60L * 24 * 3600 * 1000);
            }
        }
    }

    public java.util.List<com.hmdm.persistence.domain.AgentEnrollAttempt> listAttempts(int customerId,
                                                                                       boolean includeUnknown, int limit) {
        return mapper.listAttempts(customerId, includeUnknown, Math.max(1, Math.min(limit, 500)));
    }

    public java.util.List<com.hmdm.persistence.domain.AgentEnrollAttempt> listAttemptsOfToken(int tokenId) {
        return mapper.listAttemptsOfToken(tokenId);
    }
}
