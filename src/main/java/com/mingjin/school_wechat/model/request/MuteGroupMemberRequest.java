package com.mingjin.school_wechat.model.request;

import lombok.Data;

@Data
public class MuteGroupMemberRequest {
    private Integer muted;
    private Integer muteMinutes;
}
