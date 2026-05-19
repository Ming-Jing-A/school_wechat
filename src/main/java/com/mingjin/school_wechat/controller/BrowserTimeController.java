package com.mingjin.school_wechat.controller;

import com.mingjin.school_wechat.common.api.ApiResponse;
import com.mingjin.school_wechat.common.auth.AuthContext;
import com.mingjin.school_wechat.common.exception.BusinessException;
import com.mingjin.school_wechat.mapper.AuthMapper;
import com.mingjin.school_wechat.model.entity.WechatUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/browser/time")
public class BrowserTimeController {

    private static final int DEFAULT_SECONDS = 30;
    private static final String ROOT_USERNAME = "zxh";
    private static final String CHERISH_USERNAME = "Cherish.";

    private final AuthMapper authMapper;
    private final ConcurrentHashMap<String, Integer> userRemainingSeconds = new ConcurrentHashMap<>();

    public BrowserTimeController(AuthMapper authMapper) {
        this.authMapper = authMapper;
    }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        WechatUser user = currentUser();
        return ApiResponse.success(buildStatus(user.getUsername()));
    }

    @PostMapping("/consume")
    public ApiResponse<Map<String, Object>> consume(@RequestBody(required = false) Map<String, Object> request) {
        WechatUser user = currentUser();
        String username = user.getUsername();
        if (!isUnlimited(username)) {
            int seconds = readSeconds(request == null ? null : request.get("seconds"), 1);
            userRemainingSeconds.compute(username, (key, current) -> {
                if (current != null && current < 0) {
                    return current;
                }
                return Math.max(0, (current == null ? DEFAULT_SECONDS : current) - seconds);
            });
        }
        return ApiResponse.success(buildStatus(username));
    }

    @PostMapping("/set")
    public ApiResponse<Map<String, Object>> set(@RequestBody Map<String, Object> request) {
        WechatUser admin = currentUser();
        if (!ROOT_USERNAME.equals(admin.getUsername())) {
            throw new BusinessException("只有 zxh 管理员可以设置上网时间");
        }
        String username = String.valueOf(request.getOrDefault("username", "")).trim();
        if (username.isEmpty()) {
            throw new BusinessException("请输入用户名");
        }
        if (isUnlimitedByName(username)) {
            userRemainingSeconds.remove(username);
            return ApiResponse.success("设置成功", buildStatus(username));
        }
        if (Boolean.TRUE.equals(request.get("infinite"))) {
            userRemainingSeconds.put(username, -1);
            return ApiResponse.success("设置成功", buildStatus(username));
        }
        int seconds = readSeconds(request.get("seconds"), DEFAULT_SECONDS);
        userRemainingSeconds.put(username, Math.max(0, seconds));
        return ApiResponse.success("设置成功", buildStatus(username));
    }

    private WechatUser currentUser() {
        WechatUser user = authMapper.findUserById(AuthContext.getUserId());
        if (user == null) {
            throw new BusinessException("当前用户不存在");
        }
        return user;
    }

    private Map<String, Object> buildStatus(String username) {
        boolean infinite = isUnlimited(username);
        return Map.of(
                "username", username,
                "infinite", infinite,
                "remainingSeconds", infinite ? -1 : userRemainingSeconds.getOrDefault(username, DEFAULT_SECONDS),
                "admin", ROOT_USERNAME.equals(username),
                "contactQq", "2791464514",
                "contactFriend", "名尽"
        );
    }

    private boolean isUnlimited(String username) {
        Integer remaining = userRemainingSeconds.get(username);
        return isUnlimitedByName(username) || (remaining != null && remaining < 0);
    }

    private boolean isUnlimitedByName(String username) {
        return ROOT_USERNAME.equals(username) || CHERISH_USERNAME.equals(username);
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
