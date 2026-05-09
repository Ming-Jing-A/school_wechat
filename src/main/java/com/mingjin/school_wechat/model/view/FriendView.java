package com.mingjin.school_wechat.model.view;

import lombok.Data;

@Data
public class FriendView {
    private Long friendUserId;
    private String username;
    private String remarkName;
    private Integer isStarred;
    private Integer isMuted;
    private String nickname;
    private String wechatNo;
    private String avatarUrl;
    private String signature;
    private String region;
}
