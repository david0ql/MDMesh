package com.hmdm.persistence.mapper;

import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

/** Round trip to the database for the health probe ({@code /rest/public/health}). Reads no data. */
public interface HealthMapper {

    @Select("SELECT 1")
    @Options(timeout = 3, useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    int ping();
}
