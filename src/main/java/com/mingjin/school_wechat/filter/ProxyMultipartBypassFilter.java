package com.mingjin.school_wechat.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProxyMultipartBypassFilter extends OncePerRequestFilter {

    private static final String PROXY_PATH = "/api/browser/proxy";
    public static final String ORIGINAL_CONTENT_TYPE_ATTR = "proxy.originalContentType";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        String contentType = request.getContentType();

        if (path != null && path.startsWith(PROXY_PATH)
                && contentType != null && contentType.toLowerCase().startsWith("multipart/")) {

            byte[] cachedBody = request.getInputStream().readAllBytes();
            request.setAttribute(ORIGINAL_CONTENT_TYPE_ATTR, contentType);

            HttpServletRequestWrapper wrapper = new HttpServletRequestWrapper(request) {
                @Override
                public String getContentType() {
                    return "application/octet-stream";
                }

                @Override
                public ServletInputStream getInputStream() {
                    ByteArrayInputStream bais = new ByteArrayInputStream(cachedBody);
                    return new ServletInputStream() {
                        @Override
                        public int read() throws IOException {
                            return bais.read();
                        }

                        @Override
                        public boolean isFinished() {
                            return bais.available() == 0;
                        }

                        @Override
                        public boolean isReady() {
                            return true;
                        }

                        @Override
                        public void setReadListener(ReadListener readListener) {
                        }
                    };
                }

                @Override
                public int getContentLength() {
                    return cachedBody.length;
                }

                @Override
                public long getContentLengthLong() {
                    return cachedBody.length;
                }
            };
            filterChain.doFilter(wrapper, response);
        } else {
            filterChain.doFilter(request, response);
        }
    }
}