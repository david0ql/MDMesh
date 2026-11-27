/*
 * DallyControl — scheduled fleet reports (new module, not part of upstream Headwind).
 */
package com.hmdm.persistence.mapper;

import com.hmdm.persistence.domain.ReportSchedule;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** report_schedule: reports sent by e-mail on a schedule. Every console query is scoped by customerId. */
public interface ReportScheduleMapper {

    String COLS = "id, customerId, name, report, format, csvSeparator, frequency, runHour, runMinute, dayOfWeek, "
            + "dayOfMonth, timezone, recipients, groupId, enabled, createdBy, createdAt, updatedAt, lastRunAt, "
            + "lastStatus, lastError, nextRunAt";

    @Select({"SELECT " + COLS + " FROM report_schedule WHERE customerId = #{customerId} ORDER BY lower(name), id"})
    List<ReportSchedule> list(@Param("customerId") int customerId);

    @Select({"SELECT " + COLS + " FROM report_schedule WHERE customerId = #{customerId} AND id = #{id}"})
    ReportSchedule find(@Param("customerId") int customerId, @Param("id") int id);

    /** Enabled schedules whose time has come, for every customer (the server's scheduler). */
    @Select({"SELECT " + COLS + " FROM report_schedule WHERE enabled = TRUE AND nextRunAt IS NOT NULL "
            + "AND nextRunAt <= #{now} ORDER BY nextRunAt LIMIT 200"})
    List<ReportSchedule> due(@Param("now") long now);

    @Insert({"INSERT INTO report_schedule (customerId, name, report, format, csvSeparator, frequency, runHour, runMinute, "
            + "dayOfWeek, dayOfMonth, timezone, recipients, groupId, enabled, createdBy, createdAt, updatedAt, nextRunAt) "
            + "VALUES (#{customerId}, #{name}, #{report}, #{format}, #{csvSeparator}, #{frequency}, #{runHour}, #{runMinute}, "
            + "#{dayOfWeek}, #{dayOfMonth}, #{timezone}, #{recipients}, #{groupId}, #{enabled}, #{createdBy}, #{createdAt}, "
            + "#{updatedAt}, #{nextRunAt})"})
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insert(ReportSchedule s);

    @Update({"UPDATE report_schedule SET name = #{name}, report = #{report}, format = #{format}, "
            + "csvSeparator = #{csvSeparator}, frequency = #{frequency}, runHour = #{runHour}, runMinute = #{runMinute}, "
            + "dayOfWeek = #{dayOfWeek}, dayOfMonth = #{dayOfMonth}, timezone = #{timezone}, recipients = #{recipients}, "
            + "groupId = #{groupId}, enabled = #{enabled}, updatedAt = #{updatedAt}, nextRunAt = #{nextRunAt} "
            + "WHERE customerId = #{customerId} AND id = #{id}"})
    int update(ReportSchedule s);

    @Delete({"DELETE FROM report_schedule WHERE customerId = #{customerId} AND id = #{id}"})
    int delete(@Param("customerId") int customerId, @Param("id") int id);

    /**
     * Take a due schedule: moves its next run forward only if nobody else did (two servers, or two ticks, never send
     * the same run twice). Returns 1 when this caller got it.
     */
    @Update({"UPDATE report_schedule SET nextRunAt = #{next} WHERE id = #{id} AND enabled = TRUE AND nextRunAt = #{prev}"})
    int claim(@Param("id") int id, @Param("prev") long prev, @Param("next") long next);

    @Update({"UPDATE report_schedule SET lastRunAt = #{at}, lastStatus = #{status}, lastError = #{error} WHERE id = #{id}"})
    int result(@Param("id") int id, @Param("at") long at, @Param("status") String status, @Param("error") String error);
}
