package com.mingjin.school_wechat.model.entity;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class WechatUser {
    private Long id;
    private String username;
    private String passwordHash;
    private String nickname;
    private String wechatNo;
    private String phone;
    private String email;
    private String avatarUrl;
    private Integer gender;
    private LocalDate birthday;
    private String region;
    private String signature;
    private String friendAddPolicy;
    private Integer status;
    private LocalDateTime lastOnlineAt;
}
