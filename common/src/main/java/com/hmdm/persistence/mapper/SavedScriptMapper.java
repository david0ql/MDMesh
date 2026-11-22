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

package com.hmdm.persistence.mapper;

import com.hmdm.persistence.domain.SavedScript;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectKey;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * <p>MyBatis mapper for {@link SavedScript} (admin-authored reusable device scripts). Auto-registered
 * by package scan (see {@code AbstractPersistenceModule}).</p>
 */
public interface SavedScriptMapper {

    @Insert({"INSERT INTO savedScript (customerId, name, description, body, paramsJson, quickAction, createdBy, " +
            "createdAt, updatedAt) VALUES (#{customerId}, #{name}, #{description}, #{body}, #{paramsJson}, " +
            "#{quickAction}, #{createdBy}, #{createdAt}, #{updatedAt})"})
    @SelectKey(statement = "SELECT currval('savedscript_id_seq')", keyColumn = "id", keyProperty = "id",
            before = false, resultType = int.class)
    void insert(SavedScript script);

    @Update({"UPDATE savedScript SET name = #{name}, description = #{description}, body = #{body}, " +
            "paramsJson = #{paramsJson}, quickAction = #{quickAction}, updatedAt = #{updatedAt} " +
            "WHERE id = #{id} AND customerId = #{customerId}"})
    int update(SavedScript script);

    @Select({"SELECT * FROM savedScript WHERE customerId = #{customerId} ORDER BY quickAction DESC, name ASC"})
    List<SavedScript> list(@Param("customerId") int customerId);

    @Select({"SELECT * FROM savedScript WHERE id = #{id} AND customerId = #{customerId}"})
    SavedScript findById(@Param("customerId") int customerId, @Param("id") int id);

    @Delete({"DELETE FROM savedScript WHERE id = #{id} AND customerId = #{customerId}"})
    int delete(@Param("customerId") int customerId, @Param("id") int id);
}
