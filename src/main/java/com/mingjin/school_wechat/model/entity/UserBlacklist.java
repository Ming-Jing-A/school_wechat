package com.mingjin.school_wechat.model.entity;

import lombok.Data;

@Data
public class UserBlacklist {
    private Long id;
    private Long userId;
    private Long blockedUserId;
    private String reason;
}
