package com.mingjin.school_wechat.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserDevice {
    private Long id;
    private Long userId;
    private String deviceType;
    private String platform;
    private String deviceName;
    private String browserName;
    private String osName;
    private String deviceIdentifier;
    private String lastLoginIp;
    private LocalDateTime lastLoginAt;
    private LocalDateTime lastActiveAt;
    private Long lastSyncSeq;
    private Integer isOnline;
    private Integer status;
}
