package com.mingjin.school_wechat.model.view;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FriendRequestView {
    private Long id;
    private Long fromUserId;
    private String fromUsername;
    private String fromNickname;
    private String fromAvatarUrl;
    private Long toUserId;
    private String toUsername;
    private String toNickname;
    private String toAvatarUrl;
    private String requestMessage;
    private String source;
    private String status;
    private LocalDateTime createdAt;
}
