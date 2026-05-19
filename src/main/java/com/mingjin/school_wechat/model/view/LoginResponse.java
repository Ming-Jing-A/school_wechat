package com.mingjin.school_wechat.model.view;

import lombok.Data;

@Data
public class LoginResponse {
    private Long userId;
    private Long deviceId;
    private String token;
    private String username;
    private String nickname;
    private String avatarUrl;
    private String wechatNo;
}
