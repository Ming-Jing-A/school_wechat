package com.mingjin.school_wechat.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiUserConfig {
    private Long id;
    private Long userId;
    private String apiKey;
    private String baseUrl;
    private String model;
    private LocalDateTime updatedAt;
}
