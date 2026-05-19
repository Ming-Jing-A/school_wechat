package com.mingjin.school_wechat.model.request;

import lombok.Data;

import java.time.LocalDate;

@Data
public class UpdateProfileRequest {
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
}
