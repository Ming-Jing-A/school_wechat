package com.mingjin.school_wechat.model.view;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserSyncEventView {
    private Long id;
    private Long syncSeq;
    private String eventType;
    private String actionType;
    private String relatedType;
    private Long relatedId;
    private String eventPayload;
    private LocalDateTime createdAt;
}
