package com.mingjin.school_wechat.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Conversation {
    private Long id;
    private String conversationType;
    private String name;
    private String avatarUrl;
    private Long ownerUserId;
    private String descriptionText;
    private String announcement;
    private String joinRule;
    private Integer maxMemberCount;
    private Integer muteAll;
    private Long lastMessageId;
    private String lastMessageType;
    private String lastMessageContent;
    private Long lastSenderId;
    private LocalDateTime lastMessageAt;
    private Integer status;
}
