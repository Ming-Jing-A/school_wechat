package com.mingjin.school_wechat.websocket;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

@Component
public class BrowserWsProxyInterceptor implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {
        if (request instanceof ServletServerHttpRequest) {
            HttpServletRequest servletRequest = ((ServletServerHttpRequest) request).getServletRequest();
            String targetUrl = servletRequest.getParameter("url");
            if (targetUrl == null || targetUrl.isEmpty()) {
                return false;
            }
            if (!targetUrl.startsWith("ws://") && !targetUrl.startsWith("wss://")) {
                return false;
            }
            attributes.put("targetWsUrl", targetUrl);
            String cookie = servletRequest.getHeader("Cookie");
            if (cookie != null) {
                attributes.put("cookie", cookie);
            }
            return true;
        }
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
