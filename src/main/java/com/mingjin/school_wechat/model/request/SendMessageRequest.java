package com.mingjin.school_wechat.model.request;

import lombok.Data;

import java.util.List;

@Data
public class SendMessageRequest {
    private String messageType;
    private String content;
    private Long quoteMessageId;
    private Long fileId;
    private List<Long> mentionUserIds;
}
