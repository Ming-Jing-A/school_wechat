package com.mingjin.school_wechat.model.view;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class MessageReadReceiptView {
    private Long messageId;
    private Integer readCount;
    private Integer unreadCount;
    private List<MessageReaderView> readers;

    @Data
    public static class MessageReaderView {
        private Long userId;
        private String nickname;
        private String avatarUrl;
        private LocalDateTime readAt;
    }
}
