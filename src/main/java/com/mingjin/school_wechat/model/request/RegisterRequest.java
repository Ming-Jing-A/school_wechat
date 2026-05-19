package com.mingjin.school_wechat.model.request;

import lombok.Data;

@Data
public class RegisterRequest {
    private String username;
    private String password;
    private String nickname;
    private String wechatNo;
    private String phone;
    private String email;
    private String avatarUrl;
}
