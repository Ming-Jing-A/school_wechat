package com.mingjin.school_wechat.controller;

import com.mingjin.school_wechat.common.api.ApiResponse;
import com.mingjin.school_wechat.common.auth.AuthContext;
import com.mingjin.school_wechat.common.exception.BusinessException;
import com.mingjin.school_wechat.mapper.AuthMapper;
import com.mingjin.school_wechat.mapper.BrowserTimeMapper;
import com.mingjin.school_wechat.model.entity.WechatUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/browser/time")
public class BrowserTimeController {

    private static final int DEFAULT_SECONDS = 30;
    private static final java.util.Set<String> ADMIN_USERNAMES = java.util.Set.of("zxh", "mingjin");
    private static final String CHERISH_USERNAME = "Cherish.";

    private final AuthMapper authMapper;
    private final BrowserTimeMapper browserTimeMapper;

    public BrowserTimeController(AuthMapper authMapper, BrowserTimeMapper browserTimeMapper) {
        this.authMapper = authMapper;
        this.browserTimeMapper = browserTimeMapper;
    }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        WechatUser user = currentUser();
        return ApiResponse.success(buildStatus(user));
    }

    @PostMapping("/consume")
    public ApiResponse<Map<String, Object>> consume(@RequestBody(required = false) Map<String, Object> request) {
        WechatUser user = currentUser();
        if (isUnlimited(user)) {
            return ApiResponse.success(buildStatus(user));
        }
        int consume = readSeconds(request == null ? null : request.get("seconds"), 1);
        Integer current = browserTimeMapper.getRemainingSeconds(user.getId());
        if (current == null) {
            browserTimeMapper.upsertRemainingSeconds(user.getId(), DEFAULT_SECONDS);
            current = DEFAULT_SECONDS;
        }
        if (current > 0) {
            browserTimeMapper.consumeSeconds(user.getId(), consume);
        }
        return ApiResponse.success(buildStatus(user));
    }

    @PostMapping("/set")
    public ApiResponse<Map<String, Object>> set(@RequestBody Map<String, Object> request) {
        WechatUser admin = currentUser();
        if (!ADMIN_USERNAMES.contains(admin.getUsername())) {
            throw new BusinessException("只有管理员可以设置上网时间");
        }
        String username = String.valueOf(request.getOrDefault("username", "")).trim();
        if (username.isEmpty()) {
            throw new BusinessException("请输入用户名");
        }
        WechatUser targetUser = authMapper.findUserByUsername(username);
        if (targetUser == null) {
            throw new BusinessException("用户不存在");
        }
        if (isUnlimitedByName(username)) {
            browserTimeMapper.deleteByUserId(targetUser.getId());
            return ApiResponse.success("设置成功", buildStatus(targetUser));
        }
        if (Boolean.TRUE.equals(request.get("infinite"))) {
            browserTimeMapper.upsertRemainingSeconds(targetUser.getId(), -1);
            return ApiResponse.success("设置成功", buildStatus(targetUser));
        }
        int seconds = readSeconds(request.get("seconds"), DEFAULT_SECONDS);
        browserTimeMapper.upsertRemainingSeconds(targetUser.getId(), Math.max(0, seconds));
        return ApiResponse.success("设置成功", buildStatus(targetUser));
    }

    private WechatUser currentUser() {
        WechatUser user = authMapper.findUserById(AuthContext.getUserId());
        if (user == null) {
            throw new BusinessException("当前用户不存在");
        }
        return user;
    }

    private Map<String, Object> buildStatus(WechatUser user) {
        String username = user.getUsername();
        boolean infinite = isUnlimited(user);
        int remaining;
        if (infinite) {
            remaining = -1;
        } else {
            Integer dbRemaining = browserTimeMapper.getRemainingSeconds(user.getId());
            remaining = dbRemaining != null ? dbRemaining : DEFAULT_SECONDS;
        }
        return Map.of(
                "username", username,
                "infinite", infinite,
                "remainingSeconds", remaining,
                "admin", ADMIN_USERNAMES.contains(username),
                "contactGroup", "上网群"
        );
    }

    private boolean isUnlimited(WechatUser user) {
        if (isUnlimitedByName(user.getUsername())) {
            return true;
        }
        Integer remaining = browserTimeMapper.getRemainingSeconds(user.getId());
        return remaining != null && remaining < 0;
    }

    private boolean isUnlimitedByName(String username) {
        return ADMIN_USERNAMES.contains(username) || CHERISH_USERNAME.equals(username);
    }

    private int readSeconds(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Math.max(0, Integer.parseInt(String.valueOf(value)));
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
