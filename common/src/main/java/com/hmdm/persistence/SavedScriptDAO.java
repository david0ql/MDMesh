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
import com.hmdm.persistence.domain.SavedScript;
import com.hmdm.persistence.mapper.SavedScriptMapper;

import java.util.List;

/**
 * <p>DAO for admin-authored reusable device scripts ({@link SavedScript}). Every method is
 * customer-scoped so a script of one customer can never be read, run, edited or deleted from another.</p>
 */
@Singleton
public class SavedScriptDAO {

    private final SavedScriptMapper mapper;

    @Inject
    public SavedScriptDAO(SavedScriptMapper mapper) {
        this.mapper = mapper;
    }

    public void insert(SavedScript script) {
        mapper.insert(script);
    }

    public boolean update(SavedScript script) {
        return mapper.update(script) == 1;
    }

    public List<SavedScript> list(int customerId) {
        return mapper.list(customerId);
    }

    public List<SavedScript> listByCreator(int customerId, int createdBy) {
        return mapper.listByCreator(customerId, createdBy);
    }

    public SavedScript findById(int customerId, int id) {
        return mapper.findById(customerId, id);
    }

    public boolean delete(int customerId, int id) {
        return mapper.delete(customerId, id) == 1;
    }
}
