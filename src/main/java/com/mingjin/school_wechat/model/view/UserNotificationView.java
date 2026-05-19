package com.mingjin.school_wechat.model.view;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserNotificationView {
    private Long id;
    private String notificationType;
    private String title;
    private String content;
    private String relatedType;
    private Long relatedId;
    private Integer isRead;
    private LocalDateTime readAt;
    private String extraJson;
    private LocalDateTime createdAt;
}
