package com.mingjin.school_wechat.common.auth;

import com.mingjin.school_wechat.model.entity.AuthSession;

public final class AuthContext {
    private static final ThreadLocal<AuthSession> HOLDER = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(AuthSession authSession) {
        HOLDER.set(authSession);
    }

    public static AuthSession get() {
        return HOLDER.get();
    }

    public static Long getUserId() {
        AuthSession authSession = HOLDER.get();
        return authSession == null ? null : authSession.getUserId();
    }

    public static Long getDeviceId() {
        AuthSession authSession = HOLDER.get();
        return authSession == null ? null : authSession.getDeviceId();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
