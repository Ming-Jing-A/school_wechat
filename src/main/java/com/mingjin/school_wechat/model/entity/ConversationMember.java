package com.mingjin.school_wechat.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ConversationMember {
    private Long id;
    private Long conversationId;
    private Long userId;
    private String memberRole;
    private String displayName;
    private String joinSource;
    private Long inviterUserId;
    private Integer isMuted;
    private LocalDateTime muteUntil;
    private Integer status;
    private LocalDateTime joinedAt;
}
