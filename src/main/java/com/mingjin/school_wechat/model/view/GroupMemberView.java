package com.mingjin.school_wechat.model.view;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class GroupMemberView {
    private Long userId;
    private String nickname;
    private String avatarUrl;
    private String memberRole;
    private String displayName;
    private String joinSource;
    private Long inviterUserId;
    private Integer isMuted;
    private LocalDateTime muteUntil;
    private LocalDateTime joinedAt;
    private Integer status;
}
