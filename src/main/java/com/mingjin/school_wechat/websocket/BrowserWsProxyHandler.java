package com.mingjin.school_wechat.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class BrowserWsProxyHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(BrowserWsProxyHandler.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();

    private final ConcurrentHashMap<String, java.net.http.WebSocket> upstreamConnections = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String targetUrl = (String) session.getAttributes().get("targetWsUrl");
        if (targetUrl == null || targetUrl.isEmpty()) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        log.info("WS Proxy: connecting to {}", targetUrl);

        try {
            java.net.http.WebSocket.Builder wsBuilder = httpClient.newWebSocketBuilder();
            wsBuilder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            wsBuilder.header("Origin", extractOrigin(targetUrl));

            String cookieStr = (String) session.getAttributes().get("cookie");
            if (cookieStr != null && !cookieStr.isEmpty()) {
                wsBuilder.header("Cookie", cookieStr);
            }

            java.net.http.WebSocket upstreamWs = wsBuilder
                    .buildAsync(URI.create(targetUrl), new java.net.http.WebSocket.Listener() {
                        StringBuilder textBuffer = new StringBuilder();
                        ByteBuffer binaryBuffer = null;

                        @Override
                        public CompletionStage<?> onText(java.net.http.WebSocket webSocket, CharSequence data, boolean last) {
                            textBuffer.append(data);
                            if (last) {
                                String message = textBuffer.toString();
                                textBuffer = new StringBuilder();
                                try {
                                    if (session.isOpen()) {
                                        session.sendMessage(new TextMessage(message));
                                    }
                                } catch (Exception e) {
                                    log.warn("WS Proxy: error sending text to client: {}", e.getMessage());
                                }
                            }
                            webSocket.request(1);
                            return null;
                        }

                        @Override
                        public CompletionStage<?> onBinary(java.net.http.WebSocket webSocket, ByteBuffer data, boolean last) {
                            byte[] bytes = new byte[data.remaining()];
                            data.get(bytes);
                            try {
                                if (session.isOpen()) {
                                    session.sendMessage(new BinaryMessage(ByteBuffer.wrap(bytes)));
                                }
                            } catch (Exception e) {
                                log.warn("WS Proxy: error sending binary to client: {}", e.getMessage());
                            }
                            webSocket.request(1);
                            return null;
                        }

                        @Override
                        public CompletionStage<?> onPing(java.net.http.WebSocket webSocket, ByteBuffer message) {
                            try {
                                if (session.isOpen()) {
                                    session.sendMessage(new PingMessage(message));
                                }
                            } catch (Exception e) {
                                log.warn("WS Proxy: error sending ping to client: {}", e.getMessage());
                            }
                            webSocket.request(1);
                            return null;
                        }

                        @Override
                        public CompletionStage<?> onPong(java.net.http.WebSocket webSocket, ByteBuffer message) {
                            try {
                                if (session.isOpen()) {
                                    session.sendMessage(new PongMessage(message));
                                }
                            } catch (Exception e) {
                                log.warn("WS Proxy: error sending pong to client: {}", e.getMessage());
                            }
                            webSocket.request(1);
                            return null;
                        }

                        @Override
                        public CompletionStage<?> onClose(java.net.http.WebSocket webSocket, int statusCode, String reason) {
                            log.info("WS Proxy: upstream closed: {} {}", statusCode, reason);
                            upstreamConnections.remove(session.getId());
                            try {
                                if (session.isOpen()) {
                                    session.close(new CloseStatus(statusCode, reason));
                                }
                            } catch (Exception e) {
                                log.warn("WS Proxy: error closing client session: {}", e.getMessage());
                            }
                            return null;
                        }

                        @Override
                        public void onError(java.net.http.WebSocket webSocket, Throwable error) {
                            log.error("WS Proxy: upstream error: {}", error.getMessage());
                            upstreamConnections.remove(session.getId());
                            try {
                                if (session.isOpen()) {
                                    session.close(CloseStatus.SERVER_ERROR);
                                }
                            } catch (Exception e) {
                                log.warn("WS Proxy: error closing client session: {}", e.getMessage());
                            }
                        }
                    })
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);

            upstreamConnections.put(session.getId(), upstreamWs);
            log.info("WS Proxy: connected to upstream for session {}", session.getId());
        } catch (Exception e) {
            log.error("WS Proxy: failed to connect to upstream {}: {}", targetUrl, e.getMessage());
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        java.net.http.WebSocket upstream = upstreamConnections.get(session.getId());
        if (upstream != null) {
            upstream.sendText(message.getPayload(), true);
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        java.net.http.WebSocket upstream = upstreamConnections.get(session.getId());
        if (upstream != null) {
            ByteBuffer payload = message.getPayload();
            byte[] bytes = new byte[payload.remaining()];
            payload.get(bytes);
            upstream.sendBinary(ByteBuffer.wrap(bytes), true);
        }
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) throws Exception {
        java.net.http.WebSocket upstream = upstreamConnections.get(session.getId());
        if (upstream != null) {
            upstream.sendPong(message.getPayload());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.warn("WS Proxy: transport error for session {}: {}", session.getId(), exception.getMessage());
        cleanup(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("WS Proxy: client disconnected: {} {}", session.getId(), status);
        cleanup(session);
    }

    private void cleanup(WebSocketSession session) {
        java.net.http.WebSocket upstream = upstreamConnections.remove(session.getId());
        if (upstream != null) {
            try {
                upstream.sendClose(1000, "client disconnected");
            } catch (Exception e) {
                log.warn("WS Proxy: error closing upstream: {}", e.getMessage());
            }
        }
    }

    private String extractOrigin(String wsUrl) {
        try {
            URI uri = URI.create(wsUrl);
            String scheme = "wss".equalsIgnoreCase(uri.getScheme()) ? "https" : "http";
            int port = uri.getPort();
            if (port == -1) {
                return scheme + "://" + uri.getHost();
            }
            return scheme + "://" + uri.getHost() + ":" + port;
        } catch (Exception e) {
            return "";
        }
    }
}
