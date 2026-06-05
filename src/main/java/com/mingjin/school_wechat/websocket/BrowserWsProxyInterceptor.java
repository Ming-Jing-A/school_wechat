package com.mingjin.school_wechat.websocket;

import com.mingjin.school_wechat.controller.BrowserProxyController;
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

    private final BrowserProxyController browserProxyController;

    public BrowserWsProxyInterceptor(BrowserProxyController browserProxyController) {
        this.browserProxyController = browserProxyController;
    }

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
            String subprotocol = servletRequest.getParameter("subprotocol");
            if (subprotocol != null && !subprotocol.isEmpty()) {
                attributes.put("subprotocol", subprotocol);
            }
            String cookie = servletRequest.getHeader("Cookie");
            if (cookie != null && !cookie.isEmpty()) {
                attributes.put("cookie", cookie);
            }
            if (isDoubaoRelated(targetUrl)) {
                String doubaoCookies = browserProxyController.getCookiesForUrl("https://www.doubao.com");
                if (doubaoCookies != null && !doubaoCookies.isEmpty()) {
                    attributes.put("doubaoCookies", doubaoCookies);
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }

    private boolean isDoubaoRelated(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains("doubao.com")
                || lower.contains("zijieapi.com")
                || lower.contains("bytedance.com")
                || lower.contains("bytegoofy.com")
                || lower.contains("volces.com")
                || lower.contains("volcengine.com")
                || lower.contains("bytescm.com")
                || lower.contains("bytetos.com")
                || lower.contains("byteimg.com")
                || lower.contains("byted-static.com")
                || lower.contains("bdurl.net");
    }
}
