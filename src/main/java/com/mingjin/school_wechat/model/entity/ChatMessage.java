package com.mingjin.school_wechat.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ChatMessage {
    private Long id;
    private Long conversationId;
    private Long senderUserId;
    private String messageType;
    private String messageStatus;
    private String content;
    private String contentJson;
    private String clientMessageId;
    private Long quoteMessageId;
    private Long forwardFromMessageId;
    private Integer isRecalled;
    private LocalDateTime recallAt;
    private LocalDateTime sentAt;
}
