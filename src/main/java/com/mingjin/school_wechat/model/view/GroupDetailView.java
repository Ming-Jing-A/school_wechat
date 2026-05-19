package com.mingjin.school_wechat.model.view;

import lombok.Data;

@Data
public class GroupDetailView {
    private Long conversationId;
    private String conversationType;
    private String name;
    private String avatarUrl;
    private Long ownerUserId;
    private String descriptionText;
    private String announcement;
    private String joinRule;
    private Integer maxMemberCount;
    private Integer muteAll;
    private Integer status;
    private Integer memberCount;
    private String currentUserRole;
    private String remark;
    private String myNickname;
}
