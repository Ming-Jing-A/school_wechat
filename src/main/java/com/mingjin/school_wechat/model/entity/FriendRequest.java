package com.mingjin.school_wechat.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FriendRequest {
    private Long id;
    private Long fromUserId;
    private Long toUserId;
    private String requestMessage;
    private String source;
    private String status;
    private Long handledBy;
    private LocalDateTime handledAt;
}
