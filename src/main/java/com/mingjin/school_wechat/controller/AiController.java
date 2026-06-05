package com.mingjin.school_wechat.controller;

import com.mingjin.school_wechat.common.api.ApiResponse;
import com.mingjin.school_wechat.model.entity.AiUserConfig;
import com.mingjin.school_wechat.model.request.AiChatRequest;
import com.mingjin.school_wechat.service.AiService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiService aiService;

    public AiController(AiService aiService) {
        this.aiService = aiService;
    }

    @GetMapping("/config")
    public ApiResponse<AiUserConfig> getConfig() {
        AiUserConfig config = aiService.getUserConfig();
        return ApiResponse.success(config);
    }

    @PostMapping("/config")
    public ApiResponse<Void> saveConfig(@RequestBody Map<String, String> request) {
        String apiKey = request.getOrDefault("apiKey", "");
        String baseUrl = request.getOrDefault("baseUrl", "http://host.docker.internal:11434/v1");
        String model = request.getOrDefault("model", "deepseek-r1:7b");
        aiService.saveUserConfig(apiKey, baseUrl, model);
        return ApiResponse.success("保存成功", null);
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody AiChatRequest request) {
        return aiService.streamChat(request);
    }
}
