package com.mingjin.school_wechat.model.view;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MessageReadEventView {
    private Long conversationId;
    private Long messageId;
    private Long readUserId;
    private String readUserNickname;
    private String readUserAvatarUrl;
    private LocalDateTime readAt;
    private Integer readCount;
    private Integer unreadCount;
}
