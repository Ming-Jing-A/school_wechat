package com.mingjin.school_wechat.model.entity;

import lombok.Data;

@Data
public class Friendship {
    private Long id;
    private Long userId;
    private Long friendUserId;
    private Long friendGroupId;
    private Long sourceRequestId;
    private String remarkName;
    private Integer isStarred;
    private Integer isMuted;
    private Integer status;
}
