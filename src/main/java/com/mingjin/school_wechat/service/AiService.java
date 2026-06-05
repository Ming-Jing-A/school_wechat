package com.mingjin.school_wechat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mingjin.school_wechat.common.auth.AuthContext;
import com.mingjin.school_wechat.common.exception.BusinessException;
import com.mingjin.school_wechat.mapper.AiUserConfigMapper;
import com.mingjin.school_wechat.model.entity.AiUserConfig;
import com.mingjin.school_wechat.model.request.AiChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private final ObjectMapper objectMapper;
    private final AiUserConfigMapper aiUserConfigMapper;

    public AiService(ObjectMapper objectMapper, AiUserConfigMapper aiUserConfigMapper) {
        this.objectMapper = objectMapper;
        this.aiUserConfigMapper = aiUserConfigMapper;
    }

    public AiUserConfig getUserConfig() {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            throw new BusinessException("用户未登录");
        }
        return aiUserConfigMapper.findByUserId(userId);
    }

    public void saveUserConfig(String apiKey, String baseUrl, String model) {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            throw new BusinessException("用户未登录");
        }
        aiUserConfigMapper.upsert(userId, apiKey, baseUrl, model);
    }

    public SseEmitter streamChat(AiChatRequest request) {
        final String apiKey = request.getApiKey() != null ? request.getApiKey() : "";
        final String baseUrl = (request.getBaseUrl() == null || request.getBaseUrl().isBlank())
                ? "http://host.docker.internal:11434/v1"
                : request.getBaseUrl().replaceAll("/+$", "");
        final String model = (request.getModel() == null || request.getModel().isBlank())
                ? "deepseek-r1:7b"
                : request.getModel();

        SseEmitter emitter = new SseEmitter(300_000L);

        new Thread(() -> {
            try {
                List<Map<String, String>> messages = new ArrayList<>();
                Map<String, String> systemMsg = new HashMap<>();
                systemMsg.put("role", "system");
                systemMsg.put("content", "你是一个智能助手。请严格遵循以下 Markdown 代码规范：\n1. 所有代码必须放在代码块中，使用 ```python、```java、```javascript 等带语言标识的代码块\n2. 代码块中的代码必须保持正确的缩进和换行，不要把所有代码写在一行\n3. 禁止输出不带代码块包裹的裸代码\n4. 禁止在代码块内把多行代码压缩成一行\n5. 非代码内容使用 Markdown 标题、加粗、列表、引用等格式化\n错误示例：for i in range(1,10):print()\n正确示例：\n```python\nfor i in range(1, 10):\n    print()\n```");
                messages.add(systemMsg);
                if (request.getHistory() != null) {
                    for (AiChatRequest.AiChatMessage msg : request.getHistory()) {
                        Map<String, String> m = new HashMap<>();
                        m.put("role", msg.getRole());
                        m.put("content", msg.getContent());
                        messages.add(m);
                    }
                }
                Map<String, String> userMsg = new HashMap<>();
                userMsg.put("role", "user");
                userMsg.put("content", request.getMessage());
                messages.add(userMsg);

                Map<String, Object> body = new HashMap<>();
                body.put("model", model);
                body.put("messages", messages);
                body.put("stream", true);
                body.put("temperature", 0.3);
                body.put("top_p", 0.7);
                body.put("presence_penalty", 0.1);
                body.put("frequency_penalty", 0.1);

                String jsonBody = objectMapper.writeValueAsString(body);

                String endpoint = baseUrl + "/chat/completions";
                log.info("AI 请求: endpoint={}, model={}, messagesCount={}", endpoint, model, messages.size());
                HttpURLConnection conn = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                if (!apiKey.isBlank()) {
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                }
                conn.setRequestProperty("Accept", "text/event-stream");
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(300000);

                conn.getOutputStream().write(jsonBody.getBytes(StandardCharsets.UTF_8));
                conn.getOutputStream().flush();

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    String errorMsg = "AI 服务返回错误 (HTTP " + responseCode + ")";
                    java.io.InputStream errorStream = conn.getErrorStream();
                    if (errorStream != null) {
                        BufferedReader errorReader = new BufferedReader(new InputStreamReader(errorStream, StandardCharsets.UTF_8));
                        StringBuilder errorBody = new StringBuilder();
                        String line;
                        while ((line = errorReader.readLine()) != null) {
                            errorBody.append(line);
                        }
                        errorReader.close();
                        errorMsg = errorBody.toString();
                        if (errorMsg.isBlank()) {
                            errorMsg = "AI 服务返回错误 (HTTP " + responseCode + ")";
                        } else {
                            try {
                                JsonNode errorNode = objectMapper.readTree(errorMsg);
                                if (errorNode.has("error") && errorNode.get("error").has("message")) {
                                    errorMsg = errorNode.get("error").get("message").asText();
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    emitter.send(SseEmitter.event().name("error").data(errorMsg));
                    emitter.complete();
                    return;
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                String sseLine;
                StringBuilder fullContent = new StringBuilder();
                while ((sseLine = reader.readLine()) != null) {
                    if (sseLine.isEmpty()) continue;

                    String data = null;
                    if (sseLine.startsWith("data: ")) {
                        data = sseLine.substring(6).trim();
                    } else if (sseLine.startsWith("data:")) {
                        data = sseLine.substring(5).trim();
                    } else {
                        log.debug("AI SSE 非 data 行: {}", sseLine);
                        continue;
                    }

                    if (data.isEmpty()) continue;
                    if ("[DONE]".equals(data)) {
                        emitter.send(SseEmitter.event().name("done").data(""));
                        break;
                    }

                    try {
                        JsonNode chunk = objectMapper.readTree(data);
                        JsonNode choices = chunk.get("choices");
                        if (choices != null && choices.isArray() && choices.size() > 0) {
                            JsonNode delta = choices.get(0).get("delta");
                            if (delta != null) {
                                String content = null;
                                if (delta.has("content") && !delta.get("content").isNull()) {
                                    content = delta.get("content").asText();
                                }
                                if (content != null && !content.isEmpty()) {
                                    fullContent.append(content);
                                    if (fullContent.length() <= 200) {
                                        log.info("AI SSE chunk content 原始数据: [{}]", content.replace("\n", "\\n"));
                                    }
                                    emitter.send(SseEmitter.event().name("chunk").data(content));
                                }
                            }
                        }
                    } catch (Exception parseEx) {
                        log.debug("AI SSE JSON 解析失败: {} data={}", parseEx.getMessage(), data.substring(0, Math.min(200, data.length())));
                    }
                }
                reader.close();
                conn.disconnect();
                log.info("AI 回复完成, 总长度: {}", fullContent.length());
                emitter.complete();
            } catch (BusinessException e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                    emitter.complete();
                } catch (Exception ignored) {
                    emitter.completeWithError(e);
                }
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data("AI 服务请求失败: " + e.getMessage()));
                    emitter.complete();
                } catch (Exception ignored) {
                    emitter.completeWithError(e);
                }
            }
        }).start();

        emitter.onTimeout(() -> emitter.complete());
        emitter.onError(e -> emitter.complete());

        return emitter;
    }
}
