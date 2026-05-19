package com.mingjin.school_wechat.model.view;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class GroupJoinRequestView {
    private Long id;
    private Long conversationId;
    private Long applicantUserId;
    private String applicantNickname;
    private String applicantAvatarUrl;
    private Long inviterUserId;
    private String inviterNickname;
    private String requestMessage;
    private String status;
    private Long handledBy;
    private String handledByNickname;
    private LocalDateTime handledAt;
    private LocalDateTime createdAt;
}
