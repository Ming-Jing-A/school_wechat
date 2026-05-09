package com.mingjin.school_wechat.websocket;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mingjin.school_wechat.common.exception.BusinessException;
import com.mingjin.school_wechat.mapper.AuthMapper;
import com.mingjin.school_wechat.model.entity.AuthSession;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final WebSocketSessionManager sessionManager;
    private final WebSocketPushService pushService;
    private final AuthMapper authMapper;
    private final ObjectMapper objectMapper;

    public ChatWebSocketHandler(WebSocketSessionManager sessionManager,
                                WebSocketPushService pushService,
                                AuthMapper authMapper,
                                ObjectMapper objectMapper) {
        this.sessionManager = sessionManager;
        this.pushService = pushService;
        this.authMapper = authMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        AuthSession authSession = getRequiredAuthSession(session);
        sessionManager.register(authSession, session);
        authMapper.updateDeviceActiveThrottled(authSession.getDeviceId());
        pushService.pushConnected(authSession.getUserId(), Map.of(
                "userId", authSession.getUserId(),
                "deviceId", authSession.getDeviceId(),
                "sessionId", session.getId()
        ));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> request = objectMapper.readValue(message.getPayload(), new TypeReference<>() {
        });
        String type = request.getOrDefault("type", "").toString();
        if ("ping".equalsIgnoreCase(type)) {
            pushService.pushPong(session);
            return;
        }
        pushService.pushServerNotice(session, "已建立实时连接，当前仅处理服务器推送与 ping/pong。");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionManager.unregister(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        sessionManager.unregister(session);
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    private AuthSession getRequiredAuthSession(WebSocketSession session) {
        Object authSession = session.getAttributes().get(WebSocketHandshakeInterceptor.ATTR_AUTH_SESSION);
        if (authSession instanceof AuthSession sessionInfo) {
            return sessionInfo;
        }
        throw new BusinessException("WebSocket 会话鉴权失败");
    }
}
