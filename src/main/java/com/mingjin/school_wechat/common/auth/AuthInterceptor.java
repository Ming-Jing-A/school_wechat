package com.mingjin.school_wechat.common.auth;

import com.mingjin.school_wechat.common.exception.BusinessException;
import com.mingjin.school_wechat.mapper.AuthMapper;
import com.mingjin.school_wechat.model.entity.AuthSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.cors.CorsUtils;

@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final AuthMapper authMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (CorsUtils.isPreFlightRequest(request)) {
            return true;
        }
        String token = resolveToken(request);
        if (!StringUtils.hasText(token)) {
            throw new BusinessException("未登录或登录已过期");
        }
        AuthSession authSession = authMapper.findAuthSessionByToken(token);
        if (authSession == null) {
            throw new BusinessException("登录会话不存在");
        }
        authMapper.updateSessionActiveThrottled(authSession.getSessionId());
        if (authSession.getDeviceId() != null) {
            authMapper.updateDeviceActiveThrottled(authSession.getDeviceId());
        }
        AuthContext.set(authSession);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AuthContext.clear();
    }

    private String resolveToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (StringUtils.hasText(authorization) && authorization.startsWith("Bearer ")) {
            return authorization.substring(7);
        }
        return request.getHeader("X-Token");
    }
}
