package com.mingjin.school_wechat.mapper;

import com.mingjin.school_wechat.model.entity.AiUserConfig;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AiUserConfigMapper {

    @Select("SELECT * FROM ai_user_config WHERE user_id = #{userId}")
    AiUserConfig findByUserId(@Param("userId") Long userId);

    @Insert("""
            INSERT INTO ai_user_config (user_id, api_key, base_url, model, updated_at)
            VALUES (#{userId}, #{apiKey}, #{baseUrl}, #{model}, NOW())
            ON DUPLICATE KEY UPDATE
                api_key = VALUES(api_key),
                base_url = VALUES(base_url),
                model = VALUES(model),
                updated_at = NOW()
            """)
    void upsert(@Param("userId") Long userId, 
               @Param("apiKey") String apiKey, 
               @Param("baseUrl") String baseUrl, 
               @Param("model") String model);

    @Delete("DELETE FROM ai_user_config WHERE user_id = #{userId}")
    void deleteByUserId(@Param("userId") Long userId);
}
