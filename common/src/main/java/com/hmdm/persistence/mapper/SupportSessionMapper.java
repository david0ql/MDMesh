package com.hmdm.persistence.mapper;

import com.hmdm.persistence.domain.SupportSession;
import com.hmdm.persistence.domain.SupportSessionEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** Remote-support sessions and what happened in them (the remote module's audit trail). */
public interface SupportSessionMapper {

    String COLUMNS = "id, sessionKey, customerId, deviceNumber, userId, userLogin, viewOnly, engine, startedAt, "
            + "connectedAt, endedAt, endReason, chatCount, fileCount, voiceSeconds, inputCount";

    @Insert({"INSERT INTO support_session (sessionKey, customerId, deviceNumber, userId, userLogin, viewOnly, engine, startedAt) "
            + "VALUES (#{sessionKey}, #{customerId}, #{deviceNumber}, #{userId}, #{userLogin}, #{viewOnly}, #{engine}, #{startedAt})"})
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insert(SupportSession session);

    @Update("UPDATE support_session SET connectedAt = #{at} WHERE id = #{id} AND connectedAt IS NULL")
    void markConnected(@Param("id") long id, @Param("at") long at);

    @Update("UPDATE support_session SET engine = #{engine} WHERE id = #{id}")
    void setEngine(@Param("id") long id, @Param("engine") String engine);

    @Update({"UPDATE support_session SET endedAt = #{at}, endReason = #{reason}, chatCount = #{chat}, fileCount = #{files}, "
            + "voiceSeconds = #{voice}, inputCount = #{input} WHERE id = #{id} AND endedAt IS NULL"})
    void end(@Param("id") long id, @Param("at") long at, @Param("reason") String reason, @Param("chat") int chat,
             @Param("files") int files, @Param("voice") int voice, @Param("input") int input);

    /** Sessions a restart left open: they ended with the server. */
    @Update("UPDATE support_session SET endedAt = #{at}, endReason = 'server' WHERE endedAt IS NULL")
    int closeOrphans(@Param("at") long at);

    @Select("SELECT " + COLUMNS + " FROM support_session WHERE id = #{id}")
    SupportSession get(@Param("id") long id);

    @Select({"<script>",
            "SELECT " + COLUMNS + " FROM support_session WHERE customerId = #{customerId} ",
            "<if test='deviceNumber != null'>AND deviceNumber = #{deviceNumber} </if>",
            "<if test='numbers != null'>AND deviceNumber IN ",
            "<foreach item='n' collection='numbers' open='(' separator=',' close=')'>#{n}</foreach> </if>",
            "<if test='from != null'>AND startedAt &gt;= #{from} </if>",
            "<if test='to != null'>AND startedAt &lt; #{to} </if>",
            "ORDER BY startedAt DESC, id DESC LIMIT #{limit}",
            "</script>"})
    List<SupportSession> list(@Param("customerId") int customerId, @Param("deviceNumber") String deviceNumber,
                              @Param("numbers") List<String> numbers, @Param("from") Long from, @Param("to") Long to,
                              @Param("limit") int limit);

    @Insert("INSERT INTO support_session_event (sessionId, ts, kind, origin, detail) VALUES (#{sessionId}, #{ts}, #{kind}, #{origin}, #{detail})")
    void insertEvent(SupportSessionEvent event);

    @Select("SELECT id, sessionId, ts, kind, origin, detail FROM support_session_event WHERE sessionId = #{sessionId} ORDER BY ts, id LIMIT 2000")
    List<SupportSessionEvent> events(@Param("sessionId") long sessionId);
}
