package com.mingjin.school_wechat.model.request;

import lombok.Data;

import java.util.List;

@Data
public class CreateGroupRequest {
    private String name;
    private String announcement;
    private List<Long> memberUserIds;
}
