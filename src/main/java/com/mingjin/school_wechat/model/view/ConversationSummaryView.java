package com.mingjin.school_wechat.model.view;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ConversationSummaryView {
    private Long conversationId;
    private String conversationType;
    private String conversationName;
    private String avatarUrl;
    private String announcement;
    private String lastMessageType;
    private String lastMessageContent;
    private Long lastSenderId;
    private LocalDateTime lastMessageAt;
    private Integer unreadCount;
    private Integer isTop;
    private Integer isMuted;
    private Integer isHidden;
    private String draftContent;
    private Boolean isGroupOwner;
    private String remark;
}
