package com.mingjin.school_wechat.websocket;

import com.mingjin.school_wechat.model.entity.AuthSession;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WebSocketSessionManager {

    private final Map<Long, Map<String, WebSocketSession>> userSessions = new ConcurrentHashMap<>();
    private final Map<String, AuthSession> sessionAuthIndex = new ConcurrentHashMap<>();

    public void register(AuthSession authSession, WebSocketSession session) {
        userSessions.computeIfAbsent(authSession.getUserId(), key -> new ConcurrentHashMap<>())
                .put(session.getId(), session);
        sessionAuthIndex.put(session.getId(), authSession);
        
        System.out.println("[WebSocket] Session注册 - userId: " + authSession.getUserId() 
                + ", sessionId: " + session.getId()
                + ", 当前用户session总数: " + userSessions.get(authSession.getUserId()).size());
    }

    public void unregister(WebSocketSession session) {
        AuthSession authSession = sessionAuthIndex.remove(session.getId());
        if (authSession == null) {
            System.out.println("[WebSocket] Session注销 - 未找到authSession, sessionId: " + session.getId());
            return;
        }
        Map<String, WebSocketSession> sessions = userSessions.get(authSession.getUserId());
        if (sessions == null) {
            System.out.println("[WebSocket] Session注销 - 未找到用户session列表, userId: " + authSession.getUserId());
            return;
        }
        sessions.remove(session.getId());
        System.out.println("[WebSocket] Session注销 - userId: " + authSession.getUserId() 
                + ", sessionId: " + session.getId());
        if (sessions.isEmpty()) {
            userSessions.remove(authSession.getUserId());
            System.out.println("[WebSocket] 用户 " + authSession.getUserId() + " 已离线");
        }
    }

    public List<WebSocketSession> getUserSessions(Long userId) {
        Map<String, WebSocketSession> sessions = userSessions.get(userId);
        if (sessions == null) {
            return List.of();
        }
        return sessions.values().stream().toList();
    }

    public AuthSession getAuthSession(String sessionId) {
        return sessionAuthIndex.get(sessionId);
    }
}
