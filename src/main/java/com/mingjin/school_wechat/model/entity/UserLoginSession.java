package com.mingjin.school_wechat.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserLoginSession {
    private Long id;
    private Long userId;
    private Long deviceId;
    private String sessionToken;
    private String refreshToken;
    private LocalDateTime loginAt;
    private LocalDateTime expireAt;
    private LocalDateTime lastActiveAt;
    private Integer status;
}
