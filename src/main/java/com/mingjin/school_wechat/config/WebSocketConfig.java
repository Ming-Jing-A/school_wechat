package com.mingjin.school_wechat.config;

import com.mingjin.school_wechat.websocket.ChatWebSocketHandler;
import com.mingjin.school_wechat.websocket.WebSocketHandshakeInterceptor;
import com.mingjin.school_wechat.websocket.BrowserWsProxyHandler;
import com.mingjin.school_wechat.websocket.BrowserWsProxyInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;
    private final WebSocketHandshakeInterceptor webSocketHandshakeInterceptor;
    private final BrowserWsProxyHandler browserWsProxyHandler;
    private final BrowserWsProxyInterceptor browserWsProxyInterceptor;

    public WebSocketConfig(ChatWebSocketHandler chatWebSocketHandler,
                           WebSocketHandshakeInterceptor webSocketHandshakeInterceptor,
                           BrowserWsProxyHandler browserWsProxyHandler,
                           BrowserWsProxyInterceptor browserWsProxyInterceptor) {
        this.chatWebSocketHandler = chatWebSocketHandler;
        this.webSocketHandshakeInterceptor = webSocketHandshakeInterceptor;
        this.browserWsProxyHandler = browserWsProxyHandler;
        this.browserWsProxyInterceptor = browserWsProxyInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/ws/chat")
                .addInterceptors(webSocketHandshakeInterceptor)
                .setAllowedOriginPatterns("*");
        registry.addHandler(browserWsProxyHandler, "/api/browser/ws-proxy")
                .addInterceptors(browserWsProxyInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
