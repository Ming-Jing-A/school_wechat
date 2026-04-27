package com.mingjin.school_wechat.model.entity;

import lombok.Data;

@Data
public class UserSyncEvent {
    private Long id;
    private Long userId;
    private Long sourceDeviceId;
    private Long syncSeq;
    private String eventType;
    private String actionType;
    private String relatedType;
    private Long relatedId;
    private String eventPayload;
}
