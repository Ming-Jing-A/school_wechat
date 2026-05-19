package com.mingjin.school_wechat.service;

import com.mingjin.school_wechat.common.auth.AuthContext;
import com.mingjin.school_wechat.common.exception.BusinessException;
import com.mingjin.school_wechat.common.security.PasswordUtils;
import com.mingjin.school_wechat.mapper.AuthMapper;
import com.mingjin.school_wechat.mapper.FriendMapper;
import com.mingjin.school_wechat.model.entity.UserDevice;
import com.mingjin.school_wechat.model.entity.UserLoginSession;
import com.mingjin.school_wechat.model.entity.WechatUser;
import com.mingjin.school_wechat.model.request.ChangePasswordRequest;
import com.mingjin.school_wechat.model.request.LoginRequest;
import com.mingjin.school_wechat.model.request.RegisterRequest;
import com.mingjin.school_wechat.model.request.UpdateProfileRequest;
import com.mingjin.school_wechat.model.view.LoginResponse;
import com.mingjin.school_wechat.model.view.UserProfileView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {

    private final AuthMapper authMapper;
    private final ConversationService conversationService;
    private final FriendMapper friendMapper;
    private final SyncEventService syncEventService;

    public AuthService(AuthMapper authMapper, ConversationService conversationService, FriendMapper friendMapper, SyncEventService syncEventService) {
        this.authMapper = authMapper;
        this.conversationService = conversationService;
        this.friendMapper = friendMapper;
        this.syncEventService = syncEventService;
    }

    @Transactional
    public LoginResponse login(LoginRequest request, String ipAddress) {
        validateLoginRequest(request);
        WechatUser user = authMapper.findUserByUsername(request.getUsername().trim());
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException("用户不存在或已被禁用");
        }
        if (!PasswordUtils.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException("用户名或密码错误");
        }
        LoginResponse response = createLoginSession(user, request.getDeviceName(), request.getBrowserName(), request.getOsName(), ipAddress);
        authMapper.updateUserOnline(user.getId());
        return response;
    }

    @Transactional
    public LoginResponse register(RegisterRequest request, String ipAddress) {
        validateRegisterRequest(request);
        assertRegistrationUnique(request);
        WechatUser user = new WechatUser();
        user.setUsername(request.getUsername().trim());
        user.setPasswordHash(PasswordUtils.hash(request.getPassword()));
        user.setNickname(StringUtils.hasText(request.getNickname()) ? request.getNickname().trim() : request.getUsername().trim());
        user.setWechatNo(resolveWechatNo(request));
        user.setPhone(trimToNull(request.getPhone()));
        user.setEmail(trimToNull(request.getEmail()));
        user.setAvatarUrl(trimToNull(request.getAvatarUrl()));
        user.setGender(0);
        user.setRegion(null);
        user.setSignature(null);
        user.setFriendAddPolicy("need_confirm");
        user.setStatus(1);
        user.setLastOnlineAt(LocalDateTime.now());
        authMapper.insertWechatUser(user);
        LoginResponse response = createLoginSession(user, "默认网页端", "Browser", "Unknown OS", ipAddress);
        authMapper.updateUserOnline(user.getId());
        return response;
    }

    @Transactional
    public void logout() {
        if (AuthContext.get() == null || !StringUtils.hasText(AuthContext.get().getSessionToken())) {
            throw new BusinessException("当前登录会话不存在");
        }
        authMapper.invalidateSessionByToken(AuthContext.get().getSessionToken());
        if (AuthContext.getDeviceId() != null && authMapper.countActiveSessionsByDevice(AuthContext.getDeviceId()) == 0) {
            authMapper.updateDeviceOnlineStatus(AuthContext.getDeviceId(), 0);
        }
    }

    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        if (request == null || !StringUtils.hasText(request.getOldPassword()) || !StringUtils.hasText(request.getNewPassword())) {
            throw new BusinessException("旧密码和新密码不能为空");
        }
        if (request.getNewPassword().trim().length() < 6) {
            throw new BusinessException("新密码长度不能少于 6 位");
        }
        WechatUser user = getRequiredCurrentUserEntity();
        if (!PasswordUtils.matches(request.getOldPassword(), user.getPasswordHash())) {
            throw new BusinessException("旧密码错误");
        }
        if (PasswordUtils.matches(request.getNewPassword(), user.getPasswordHash())) {
            throw new BusinessException("新密码不能与旧密码相同");
        }
        authMapper.updatePasswordHash(user.getId(), PasswordUtils.hash(request.getNewPassword()));
    }

    public UserProfileView getCurrentUser() {
        WechatUser user = getRequiredCurrentUserEntity();
        touchCurrentSession();
        return toUserProfileView(user);
    }

    @Transactional
    public UserProfileView updateProfile(UpdateProfileRequest request) {
        if (request == null) {
            throw new BusinessException("更新内容不能为空");
        }
        WechatUser user = getRequiredCurrentUserEntity();
        String oldNickname = user.getNickname();
        user.setNickname(resolveProfileValue(request.getNickname(), user.getNickname()));
        user.setWechatNo(resolveProfileValue(request.getWechatNo(), user.getWechatNo()));
        user.setPhone(resolveProfileValue(request.getPhone(), user.getPhone()));
        user.setEmail(resolveProfileValue(request.getEmail(), user.getEmail()));
        user.setAvatarUrl(resolveProfileValue(request.getAvatarUrl(), user.getAvatarUrl()));
        user.setGender(request.getGender() == null ? user.getGender() : request.getGender());
        user.setBirthday(request.getBirthday() == null ? user.getBirthday() : request.getBirthday());
        user.setRegion(resolveProfileValue(request.getRegion(), user.getRegion()));
        user.setSignature(resolveProfileValue(request.getSignature(), user.getSignature()));
        user.setFriendAddPolicy(resolveProfileValue(request.getFriendAddPolicy(), user.getFriendAddPolicy()));
        validateProfile(user);
        authMapper.updateUserProfile(user);
        touchCurrentSession();
        Long currentUserId = user.getId();
        String newNickname = user.getNickname();
        runAfterCommit(() -> {
            if (oldNickname != null && !oldNickname.equals(newNickname)) {
                friendMapper.clearRemarkNameIfMatches(currentUserId, oldNickname);
                List<Long> friendIds = friendMapper.findFriendUserIds(currentUserId);
                for (Long friendId : friendIds) {
                    syncEventService.recordEvent(friendId, null, "friendship", "update", "profile", currentUserId, Map.of("userId", currentUserId));
                }
            }
            conversationService.refreshConversationStateForFriends(currentUserId);
        });
        return toUserProfileView(authMapper.findUserById(user.getId()));
    }

    private LoginResponse createLoginSession(WechatUser user,
                                             String deviceName,
                                             String browserName,
                                             String osName,
                                             String ipAddress) {
        LocalDateTime now = LocalDateTime.now();
        UserDevice device = new UserDevice();
        device.setUserId(user.getId());
        device.setDeviceType("web");
        device.setPlatform("browser");
        device.setDeviceName(StringUtils.hasText(deviceName) ? deviceName.trim() : "默认网页端");
        device.setBrowserName(StringUtils.hasText(browserName) ? browserName.trim() : "Browser");
        device.setOsName(StringUtils.hasText(osName) ? osName.trim() : "Unknown OS");
        device.setDeviceIdentifier("device_" + UUID.randomUUID());
        device.setLastLoginIp(ipAddress);
        device.setLastLoginAt(now);
        device.setLastActiveAt(now);
        device.setLastSyncSeq(0L);
        device.setIsOnline(1);
        device.setStatus(1);
        authMapper.insertUserDevice(device);

        UserLoginSession session = new UserLoginSession();
        session.setUserId(user.getId());
        session.setDeviceId(device.getId());
        session.setSessionToken("token_" + UUID.randomUUID());
        session.setRefreshToken("refresh_" + UUID.randomUUID());
        session.setLoginAt(now);
        session.setExpireAt(now.plusDays(7));
        session.setLastActiveAt(now);
        session.setStatus(1);
        authMapper.insertUserLoginSession(session);

        LoginResponse response = new LoginResponse();
        response.setUserId(user.getId());
        response.setDeviceId(device.getId());
        response.setToken(session.getSessionToken());
        response.setUsername(user.getUsername());
        response.setNickname(user.getNickname());
        response.setAvatarUrl(user.getAvatarUrl());
        response.setWechatNo(user.getWechatNo());
        return response;
    }

    private void validateLoginRequest(LoginRequest request) {
        if (request == null || !StringUtils.hasText(request.getUsername()) || !StringUtils.hasText(request.getPassword())) {
            throw new BusinessException("用户名和密码不能为空");
        }
    }

    private void validateRegisterRequest(RegisterRequest request) {
        if (request == null || !StringUtils.hasText(request.getUsername()) || !StringUtils.hasText(request.getPassword())) {
            throw new BusinessException("用户名和密码不能为空");
        }
        if (request.getUsername().trim().length() < 3) {
            throw new BusinessException("用户名长度不能少于 3 位");
        }
        if (request.getPassword().trim().length() < 6) {
            throw new BusinessException("密码长度不能少于 6 位");
        }
    }

    private void assertRegistrationUnique(RegisterRequest request) {
        if (authMapper.countByUsername(request.getUsername().trim()) > 0) {
            throw new BusinessException("用户名已存在");
        }
        String wechatNo = resolveWechatNo(request);
        if (authMapper.countByWechatNo(wechatNo) > 0) {
            throw new BusinessException("微信号已存在");
        }
        if (StringUtils.hasText(request.getPhone()) && authMapper.countByPhone(request.getPhone().trim()) > 0) {
            throw new BusinessException("手机号已存在");
        }
        if (StringUtils.hasText(request.getEmail()) && authMapper.countByEmail(request.getEmail().trim()) > 0) {
            throw new BusinessException("邮箱已存在");
        }
    }

    private String resolveWechatNo(RegisterRequest request) {
        if (StringUtils.hasText(request.getWechatNo())) {
            return request.getWechatNo().trim();
        }
        return "wx_" + request.getUsername().trim();
    }

    private void validateProfile(WechatUser user) {
        if (!StringUtils.hasText(user.getNickname())) {
            throw new BusinessException("昵称不能为空");
        }
        if (!StringUtils.hasText(user.getWechatNo())) {
            throw new BusinessException("微信号不能为空");
        }
        if (authMapper.countByWechatNoExcludeUser(user.getWechatNo(), user.getId()) > 0) {
            throw new BusinessException("微信号已存在");
        }
        if (StringUtils.hasText(user.getPhone()) && authMapper.countByPhoneExcludeUser(user.getPhone(), user.getId()) > 0) {
            throw new BusinessException("手机号已存在");
        }
        if (StringUtils.hasText(user.getEmail()) && authMapper.countByEmailExcludeUser(user.getEmail(), user.getId()) > 0) {
            throw new BusinessException("邮箱已存在");
        }
        if (StringUtils.hasText(user.getFriendAddPolicy())) {
            String policy = user.getFriendAddPolicy().trim();
            if (!"need_confirm".equals(policy) && !"direct".equals(policy) && !"deny".equals(policy)) {
                throw new BusinessException("好友添加方式不合法");
            }
            user.setFriendAddPolicy(policy);
        }
    }

    private WechatUser getRequiredCurrentUserEntity() {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            throw new BusinessException("未获取到当前用户");
        }
        WechatUser user = authMapper.findUserById(userId);
        if (user == null) {
            throw new BusinessException("当前用户不存在");
        }
        return user;
    }

    private void touchCurrentSession() {
        if (AuthContext.get() != null) {
            authMapper.updateSessionActiveThrottled(AuthContext.get().getSessionId());
        }
        if (AuthContext.getDeviceId() != null) {
            authMapper.updateDeviceActiveThrottled(AuthContext.getDeviceId());
        }
    }

    private UserProfileView toUserProfileView(WechatUser user) {
        UserProfileView profileView = new UserProfileView();
        profileView.setId(user.getId());
        profileView.setUsername(user.getUsername());
        profileView.setNickname(user.getNickname());
        profileView.setWechatNo(user.getWechatNo());
        profileView.setPhone(user.getPhone());
        profileView.setEmail(user.getEmail());
        profileView.setAvatarUrl(user.getAvatarUrl());
        profileView.setGender(user.getGender());
        profileView.setBirthday(user.getBirthday());
        profileView.setRegion(user.getRegion());
        profileView.setSignature(user.getSignature());
        profileView.setFriendAddPolicy(user.getFriendAddPolicy());
        profileView.setStatus(user.getStatus());
        profileView.setLastOnlineAt(user.getLastOnlineAt());
        return profileView;
    }

    private String resolveProfileValue(String newValue, String currentValue) {
        if (newValue == null) {
            return currentValue;
        }
        String trimmed = newValue.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private void runAfterCommit(Runnable task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            task.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                task.run();
            }
        });
    }
}
