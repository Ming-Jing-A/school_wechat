package com.mingjin.school_wechat.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ConversationUserSetting {
    private Long id;
    private Long conversationId;
    private Long userId;
    private Integer isTop;
    private Integer isMuted;
    private Integer isHidden;
    private Integer unreadCount;
    private String draftContent;
    private Long lastReadMessageId;
    private LocalDateTime lastReadAt;
    private LocalDateTime clearMessageBefore;
    private String remark;
}
