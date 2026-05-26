package com.mingjin.school_wechat.mapper;

import org.apache.ibatis.annotations.*;

@Mapper
public interface BrowserTimeMapper {

    @Select("SELECT remaining_seconds FROM browser_time_setting WHERE user_id = #{userId}")
    Integer getRemainingSeconds(@Param("userId") Long userId);

    @Insert("""
            INSERT INTO browser_time_setting (user_id, remaining_seconds)
            VALUES (#{userId}, #{seconds})
            ON DUPLICATE KEY UPDATE remaining_seconds = #{seconds}
            """)
    int upsertRemainingSeconds(@Param("userId") Long userId, @Param("seconds") int seconds);

    @Update("UPDATE browser_time_setting SET remaining_seconds = remaining_seconds - #{consume} WHERE user_id = #{userId} AND remaining_seconds > 0")
    int consumeSeconds(@Param("userId") Long userId, @Param("consume") int consume);

    @Delete("DELETE FROM browser_time_setting WHERE user_id = #{userId}")
    int deleteByUserId(@Param("userId") Long userId);

    @Select("""
            SELECT user_id, remaining_seconds
            FROM browser_time_setting
            """)
    java.util.List<java.util.Map<String, Object>> findAllSettings();
}
