package com.mingjin.school_wechat.model.view;

import lombok.Data;

@Data
public class UserSearchView {
    private Long id;
    private String username;
    private String nickname;
    private String wechatNo;
    private String avatarUrl;
    private String region;
    private String signature;
    private Integer isFriend;
}
