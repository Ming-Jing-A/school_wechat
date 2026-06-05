package com.mingjin.school_wechat.controller;

import com.mingjin.school_wechat.common.api.ApiResponse;
import com.mingjin.school_wechat.common.auth.AuthContext;
import com.mingjin.school_wechat.common.exception.BusinessException;
import com.mingjin.school_wechat.mapper.AuthMapper;
import com.mingjin.school_wechat.mapper.BrowserTimeMapper;
import com.mingjin.school_wechat.model.entity.WechatUser;
import com.mingjin.school_wechat.websocket.WebSocketPushService;
import com.mingjin.school_wechat.websocket.WebSocketSessionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.socket.WebSocketSession;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Set<String> ADMIN_USERNAMES = Set.of("zxh", "mingjin");
    private static final Set<String> UNLIMITED_USERNAMES = Set.of("zxh", "mingjin", "Cherish.");

    private final AuthMapper authMapper;
    private final BrowserTimeMapper browserTimeMapper;
    private final WebSocketPushService pushService;
    private final WebSocketSessionManager sessionManager;

    public AdminController(AuthMapper authMapper,
                           BrowserTimeMapper browserTimeMapper,
                           WebSocketPushService pushService,
                           WebSocketSessionManager sessionManager) {
        this.authMapper = authMapper;
        this.browserTimeMapper = browserTimeMapper;
        this.pushService = pushService;
        this.sessionManager = sessionManager;
    }

    @GetMapping("/users")
    public ApiResponse<List<Map<String, Object>>> listUsers() {
        ensureAdmin();
        List<Map<String, Object>> users = authMapper.findAllUsersBasic();

        Map<Long, Integer> timeMap = new LinkedHashMap<>();
        for (Map<String, Object> row : browserTimeMapper.findAllSettings()) {
            Long userId = ((Number) row.get("user_id")).longValue();
            int seconds = ((Number) row.get("remaining_seconds")).intValue();
            timeMap.put(userId, seconds);
        }

        for (Map<String, Object> user : users) {
            Long userId = ((Number) user.get("id")).longValue();
            String username = (String) user.get("username");

            boolean hasWsConnection = !sessionManager.getUserSessions(userId).isEmpty();
            user.put("online", hasWsConnection);

            if (UNLIMITED_USERNAMES.contains(username)) {
                user.put("browserTimeInfinite", true);
                user.put("browserTimeSeconds", -1);
            } else {
                Integer seconds = timeMap.get(userId);
                if (seconds != null && seconds < 0) {
                    user.put("browserTimeInfinite", true);
                    user.put("browserTimeSeconds", seconds);
                } else {
                    user.put("browserTimeInfinite", false);
                    user.put("browserTimeSeconds", seconds != null ? seconds : 0);
                }
            }
        }

        return ApiResponse.success(users);
    }

    @PostMapping("/users/{userId}/browser-time")
    public ApiResponse<Map<String, Object>> setBrowserTime(
            @PathVariable Long userId,
            @RequestBody Map<String, Object> request) {
        ensureAdmin();

        WechatUser target = authMapper.findUserById(userId);
        if (target == null) {
            throw new BusinessException("用户不存在");
        }

        boolean infinite = Boolean.TRUE.equals(request.get("infinite"));
        int seconds = 0;
        if (request.get("seconds") != null) {
            try {
                seconds = Math.max(0, Integer.parseInt(String.valueOf(request.get("seconds"))));
            } catch (Exception e) {
                seconds = 0;
            }
        }

        if (infinite) {
            browserTimeMapper.upsertRemainingSeconds(userId, -1);
        } else {
            browserTimeMapper.upsertRemainingSeconds(userId, seconds);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("username", target.getUsername());
        result.put("infinite", infinite);
        result.put("remainingSeconds", infinite ? -1 : seconds);
        return ApiResponse.success("设置成功", result);
    }

    @PostMapping("/users/{userId}/force-logout")
    @Transactional
    public ApiResponse<String> forceLogout(@PathVariable Long userId) {
        ensureAdmin();

        WechatUser target = authMapper.findUserById(userId);
        if (target == null) {
            throw new BusinessException("用户不存在");
        }

        if (ADMIN_USERNAMES.contains(target.getUsername())) {
            throw new BusinessException("不能强制管理员下线");
        }

        authMapper.invalidateAllSessionsByUserId(userId);
        authMapper.setAllDevicesOfflineByUserId(userId);

        Long adminUserId = AuthContext.getUserId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                pushService.pushKickedOut(userId, "您已被管理员强制下线");
                List<WebSocketSession> sessions = new ArrayList<>(sessionManager.getUserSessions(userId));
                for (WebSocketSession session : sessions) {
                    try {
                        if (session.isOpen()) {
                            session.close();
                        }
                    } catch (Exception ignored) {
                    }
                    sessionManager.unregister(session);
                }
            }
        });

        return ApiResponse.success("已将用户 " + target.getNickname() + " 强制下线");
    }

    @DeleteMapping("/users/{userId}")
    @Transactional
    public ApiResponse<String> deleteUser(@PathVariable Long userId) {
        ensureAdmin();

        WechatUser target = authMapper.findUserById(userId);
        if (target == null) {
            throw new BusinessException("用户不存在");
        }

        if (ADMIN_USERNAMES.contains(target.getUsername())) {
            throw new BusinessException("不能注销管理员账号");
        }

        authMapper.deleteBrowserTimeSettingByUserId(userId);
        authMapper.deleteSyncEventsByUserId(userId);
        authMapper.deleteNotificationsByUserId(userId);
        authMapper.deleteMessageUserStatusByUserId(userId);
        authMapper.deleteBlacklistByUserId(userId);
        authMapper.deleteLoginSessionsByUserId(userId);
        authMapper.deleteDevicesByUserId(userId);
        authMapper.deleteFriendshipsByUserId(userId);
        authMapper.deleteFriendRequestsByUserId(userId);
        authMapper.deleteFriendGroupsByUserId(userId);
        authMapper.deleteConversationUserSettingsByUserId(userId);
        authMapper.deleteConversationMembersByUserId(userId);
        authMapper.deleteConversationJoinRequestsByUserId(userId);
        authMapper.softDeleteUser(userId);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                pushService.pushKickedOut(userId, "您的账号已被管理员注销");
                List<WebSocketSession> sessions = new ArrayList<>(sessionManager.getUserSessions(userId));
                for (WebSocketSession session : sessions) {
                    try {
                        if (session.isOpen()) {
                            session.close();
                        }
                    } catch (Exception ignored) {
                    }
                    sessionManager.unregister(session);
                }
            }
        });

        return ApiResponse.success("已注销用户 " + target.getNickname());
    }

    // ========== 登录限制管理 ==========

    @GetMapping("/login-restriction")
    public ApiResponse<Map<String, Object>> getLoginRestriction() {
        ensureAdmin();
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String mode = authMapper.getLoginRestrictionMode();
            List<Map<String, Object>> allowedUsers = authMapper.findAllLoginAllowedUsers();
            result.put("mode", mode != null ? mode : "open");
            result.put("allowedUsers", allowedUsers);
        } catch (Exception e) {
            result.put("mode", "open");
            result.put("allowedUsers", List.of());
        }
        return ApiResponse.success(result);
    }

    @PostMapping("/login-restriction/mode")
    public ApiResponse<Map<String, Object>> setLoginRestrictionMode(@RequestBody Map<String, String> request) {
        ensureAdmin();
        String mode = request.getOrDefault("mode", "open");
        if (!Set.of("open", "closed", "restricted").contains(mode)) {
            throw new BusinessException("无效的模式，仅支持 open/closed/restricted");
        }
        authMapper.updateLoginRestrictionMode(mode);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", mode);
        return ApiResponse.success("登录限制模式已更新", result);
    }

    @PostMapping("/login-restriction/allowed-users")
    public ApiResponse<Void> addLoginAllowedUser(@RequestBody Map<String, Long> request) {
        ensureAdmin();
        Long userId = request.get("userId");
        if (userId == null) {
            throw new BusinessException("用户ID不能为空");
        }
        WechatUser target = authMapper.findUserById(userId);
        if (target == null) {
            throw new BusinessException("用户不存在");
        }
        authMapper.addLoginAllowedUser(userId);
        return ApiResponse.success("已添加到登录白名单", null);
    }

    @DeleteMapping("/login-restriction/allowed-users/{userId}")
    public ApiResponse<Void> removeLoginAllowedUser(@PathVariable Long userId) {
        ensureAdmin();
        authMapper.removeLoginAllowedUser(userId);
        return ApiResponse.success("已从登录白名单移除", null);
    }

    private void ensureAdmin() {
        Long currentUserId = AuthContext.getUserId();
        if (currentUserId == null) {
            throw new BusinessException("未登录");
        }
        WechatUser currentUser = authMapper.findUserById(currentUserId);
        if (currentUser == null || !ADMIN_USERNAMES.contains(currentUser.getUsername())) {
            throw new BusinessException("无管理员权限");
        }
    }
}
