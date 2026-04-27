package com.mingjin.school_wechat.model.request;

import lombok.Data;

@Data
public class SendFriendRequestRequest {
    private Long toUserId;
    private String requestMessage;
    private String source;
}
