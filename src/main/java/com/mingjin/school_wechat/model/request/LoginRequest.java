package com.mingjin.school_wechat.model.request;

import lombok.Data;

@Data
public class LoginRequest {
    private String username;
    private String password;
    private String deviceName;
    private String browserName;
    private String osName;
}
