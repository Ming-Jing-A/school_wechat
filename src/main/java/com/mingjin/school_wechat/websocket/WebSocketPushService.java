package com.mingjin.school_wechat.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mingjin.school_wechat.model.entity.AuthSession;
import com.mingjin.school_wechat.model.view.ConversationMessageView;
import com.mingjin.school_wechat.model.view.MessageReadEventView;
import com.mingjin.school_wechat.model.view.ConversationSummaryView;
import com.mingjin.school_wechat.model.view.UserNotificationView;
import com.mingjin.school_wechat.model.view.UserSyncEventView;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class WebSocketPushService {

    private final WebSocketSessionManager sessionManager;
    private final ObjectMapper objectMapper;

    public WebSocketPushService(WebSocketSessionManager sessionManager, ObjectMapper objectMapper) {
        this.sessionManager = sessionManager;
        this.objectMapper = objectMapper;
    }

    public void pushConnected(Long userId, Map<String, Object> data) {
        sendToUser(userId, "connected", data);
    }

    public void pushChatMessage(Long userId, ConversationMessageView messageView) {
        sendToUser(userId, "chat_message", messageView);
    }

    public void pushMessageRecalled(Long userId, ConversationMessageView messageView) {
        sendToUser(userId, "message_recalled", messageView);
    }

    public void pushMessageReadReceipt(Long userId, MessageReadEventView messageReadEventView) {
        sendToUser(userId, "message_read_receipt", messageReadEventView);
    }

    public void pushConversationState(Long userId, ConversationSummaryView conversationSummaryView) {
        sendToUser(userId, "conversation_state", conversationSummaryView);
    }

    public void pushNotification(Long userId, UserNotificationView notificationView) {
        sendToUser(userId, "notification", notificationView);
    }

    public void pushSyncEvent(Long userId, UserSyncEventView syncEventView) {
        sendToUser(userId, "sync_event", syncEventView);
    }

    public void pushPong(WebSocketSession session) {
        send(session, buildPayload("pong", Map.of("serverTime", LocalDateTime.now().toString())));
    }

    public void pushServerNotice(WebSocketSession session, String message) {
        send(session, buildPayload("server_notice", Map.of("message", message)));
    }

    public void pushKickedOut(Long userId, String reason) {
        sendToUser(userId, "kicked_out", Map.of("reason", reason != null ? reason : "您的账号已在其他设备登录"));
    }

    public void pushUserStatusChangeToAdmins(Long changedUserId, String username, String nickname, String avatarUrl, boolean online) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", changedUserId);
        data.put("username", username);
        data.put("nickname", nickname);
        data.put("avatarUrl", avatarUrl);
        data.put("online", online);
        Set<String> adminUsernames = Set.of("zxh", "mingjin");
        for (Map.Entry<Long, Map<String, WebSocketSession>> entry : sessionManager.getAllUserSessions().entrySet()) {
            AuthSession authSession = sessionManager.getAuthSession(entry.getValue().values().iterator().next().getId());
            if (authSession != null) {
                String uname = authSession.getUsername();
                if (uname != null && adminUsernames.contains(uname)) {
                    sendToUser(entry.getKey(), "user_status_change", data);
                }
            }
        }
    }

    private void sendToUser(Long userId, String type, Object data) {
        List<WebSocketSession> sessions = sessionManager.getUserSessions(userId);
        if (sessions.isEmpty()) {
            return;
        }
        String payload = buildPayload(type, data);
        for (WebSocketSession session : sessions) {
            send(session, payload);
        }
    }

    private String buildPayload(String type, Object data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        payload.put("timestamp", LocalDateTime.now().toString());
        payload.put("data", data);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("WebSocket 消息序列化失败", ex);
        }
    }

    private void send(WebSocketSession session, String payload) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(payload));
            }
        } catch (IOException ex) {
            sessionManager.unregister(session);
        }
    }
}
