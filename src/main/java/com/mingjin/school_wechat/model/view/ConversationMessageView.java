package com.mingjin.school_wechat.model.view;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ConversationMessageView {
    private Long messageId;
    private Long conversationId;
    private Long senderUserId;
    private String senderNickname;
    private String senderUsername;
    private String senderDisplayName;
    private String senderRemarkName;
    private String senderAvatarUrl;
    private String messageType;
    private String messageStatus;
    private String content;
    private String contentJson;
    private Long quoteMessageId;
    private Long quoteSenderUserId;
    private String quoteSenderNickname;
    private String quoteSenderDisplayName;
    private String quoteMessageType;
    private String quoteMessageContent;
    private LocalDateTime sentAt;
    private Long fileId;
    private String fileName;
    private String fileUrl;
    private String mimeType;
    private String thumbnailUrl;
    private Integer width;
    private Integer height;
    private Integer durationSeconds;
    private FileAccessView fileAccess;
}
