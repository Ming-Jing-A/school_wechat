package com.mingjin.school_wechat.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserNotification {
    private Long id;
    private Long userId;
    private String notificationType;
    private String title;
    private String content;
    private String relatedType;
    private Long relatedId;
    private Integer isRead;
    private LocalDateTime readAt;
    private String extraJson;
}
