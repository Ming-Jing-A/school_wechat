package com.mingjin.school_wechat.model.request;

import lombok.Data;

import java.util.List;

@Data
public class InviteGroupMembersRequest {
    private List<Long> memberUserIds;
}
