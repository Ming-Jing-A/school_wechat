package com.mingjin.school_wechat.model.entity;

import lombok.Data;

@Data
public class AuthSession {
    private Long sessionId;
    private Long userId;
    private Long deviceId;
    private String sessionToken;
    private String nickname;
    private String avatarUrl;
}
