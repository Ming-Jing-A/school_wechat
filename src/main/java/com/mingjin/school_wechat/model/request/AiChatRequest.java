package com.mingjin.school_wechat.model.request;

import lombok.Data;

import java.util.List;

@Data
public class AiChatRequest {
    private String message;
    private String apiKey;
    private String baseUrl;
    private String model;
    private List<AiChatMessage> history;

    @Data
    public static class AiChatMessage {
        private String role;
        private String content;
    }
}
