package com.mingjin.school_wechat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mingjin.school_wechat.common.auth.AuthContext;
import com.mingjin.school_wechat.common.exception.BusinessException;
import com.mingjin.school_wechat.mapper.AuthMapper;
import com.mingjin.school_wechat.mapper.ConversationMapper;
import com.mingjin.school_wechat.mapper.FileMapper;
import com.mingjin.school_wechat.mapper.FriendMapper;
import com.mingjin.school_wechat.mapper.NotificationMapper;
import com.mingjin.school_wechat.mapper.SyncMapper;
import com.mingjin.school_wechat.model.entity.ChatMessage;
import com.mingjin.school_wechat.model.entity.Conversation;
import com.mingjin.school_wechat.model.entity.ConversationMember;
import com.mingjin.school_wechat.model.entity.ConversationUserSetting;
import com.mingjin.school_wechat.model.entity.FileResource;
import com.mingjin.school_wechat.model.entity.FriendRequest;
import com.mingjin.school_wechat.model.entity.Friendship;
import com.mingjin.school_wechat.model.entity.UserBlacklist;
import com.mingjin.school_wechat.model.entity.UserDevice;
import com.mingjin.school_wechat.model.entity.UserLoginSession;
import com.mingjin.school_wechat.model.entity.UserNotification;
import com.mingjin.school_wechat.model.entity.UserSyncEvent;
import com.mingjin.school_wechat.model.entity.WechatUser;
import com.mingjin.school_wechat.model.request.BlacklistRequest;
import com.mingjin.school_wechat.model.request.CreateFileRequest;
import com.mingjin.school_wechat.model.request.CreateGroupRequest;
import com.mingjin.school_wechat.model.request.HandleFriendRequestRequest;
import com.mingjin.school_wechat.model.request.LoginRequest;
import com.mingjin.school_wechat.model.request.SendFriendRequestRequest;
import com.mingjin.school_wechat.model.request.SendMessageRequest;
import com.mingjin.school_wechat.model.request.UpdateFriendRemarkRequest;
import com.mingjin.school_wechat.model.view.ConversationMessageView;
import com.mingjin.school_wechat.model.view.ConversationSummaryView;
import com.mingjin.school_wechat.model.view.FriendRequestView;
import com.mingjin.school_wechat.model.view.FriendView;
import com.mingjin.school_wechat.model.view.LoginResponse;
import com.mingjin.school_wechat.model.view.UserSearchView;
import com.mingjin.school_wechat.model.view.UserNotificationView;
import com.mingjin.school_wechat.model.view.UserSyncEventView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class FriendService {

    private final FriendMapper friendMapper;
    private final ConversationMapper conversationMapper;
    private final ConversationService conversationService;
    private final NotificationService notificationService;
    private final SyncEventService syncEventService;
    private final ObjectMapper objectMapper;

    public FriendService(FriendMapper friendMapper,
                  ConversationMapper conversationMapper,
                  ConversationService conversationService,
                  NotificationService notificationService,
                  SyncEventService syncEventService,
                  ObjectMapper objectMapper) {
        this.friendMapper = friendMapper;
        this.conversationMapper = conversationMapper;
        this.conversationService = conversationService;
        this.notificationService = notificationService;
        this.syncEventService = syncEventService;
        this.objectMapper = objectMapper;
    }

    public List<FriendView> listFriends(Long userId) {
        return friendMapper.findFriendsByUserId(userId);
    }

    public List<UserSearchView> searchUsers(Long userId, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            throw new BusinessException("搜索内容不能为空");
        }
        String trimmed = keyword.trim();
        Long targetUserId = null;
        if (trimmed.matches("\\d+")) {
            targetUserId = Long.parseLong(trimmed);
        }
        return friendMapper.searchUsers(userId, targetUserId, "%" + trimmed + "%");
    }

    public List<FriendRequestView> listRequests(Long userId) {
        return friendMapper.findFriendRequestsByUserId(userId);
    }

    @Transactional
    public FriendRequest sendRequest(Long userId, SendFriendRequestRequest request) {
        if (request == null || request.getToUserId() == null) {
            throw new BusinessException("目标用户不能为空");
        }
        if (userId.equals(request.getToUserId())) {
            throw new BusinessException("不能添加自己为好友");
        }
        WechatUser targetUser = friendMapper.findTargetUserById(request.getToUserId());
        if (targetUser == null) {
            throw new BusinessException("目标用户不存在");
        }
        if (friendMapper.countBlacklist(userId, request.getToUserId()) > 0) {
            throw new BusinessException("你已将对方拉黑，无法发送好友申请");
        }
        if (friendMapper.countBlacklist(request.getToUserId(), userId) > 0) {
            throw new BusinessException("对方暂时无法接收你的好友申请");
        }
        if (friendMapper.countFriendship(userId, request.getToUserId()) > 0) {
            throw new BusinessException("你们已经是好友了");
        }
        if (friendMapper.findPendingFriendRequest(userId, request.getToUserId()) != null) {
            throw new BusinessException("好友申请已发送，请等待对方处理");
        }
        if (friendMapper.findPendingFriendRequest(request.getToUserId(), userId) != null) {
            throw new BusinessException("对方已经向你发送过好友申请，请直接处理");
        }

        FriendRequest friendRequest = new FriendRequest();
        friendRequest.setFromUserId(userId);
        friendRequest.setToUserId(request.getToUserId());
        friendRequest.setRequestMessage(StringUtils.hasText(request.getRequestMessage()) ? request.getRequestMessage() : "你好，想加你为好友");
        friendRequest.setSource(StringUtils.hasText(request.getSource()) ? request.getSource() : "search");
        friendRequest.setStatus("pending");
        friendMapper.insertFriendRequest(friendRequest);

        notificationService.createNotification(
                request.getToUserId(),
                "friend_request",
                "新的好友申请",
                "你收到了一条新的好友申请",
                "friend_request",
                friendRequest.getId(),
                toJson(Map.of("fromUserId", userId))
        );
        syncEventService.recordEvent(
                request.getToUserId(),
                AuthContext.getDeviceId(),
                "friendship",
                "create",
                "friend_request",
                friendRequest.getId(),
                Map.of("fromUserId", userId)
        );

        if ("direct".equalsIgnoreCase(targetUser.getFriendAddPolicy())) {
            acceptRequestInternal(friendRequest, targetUser.getId());
        }
        return friendRequest;
    }

    @Transactional
    public void deleteFriend(Long userId, Long friendUserId) {
        assertTargetUserExists(friendUserId);
        Friendship current = getRequiredActiveFriendship(userId, friendUserId);
        Friendship reverse = getRequiredActiveFriendship(friendUserId, userId);
        friendMapper.updateFriendshipStatus(current.getUserId(), current.getFriendUserId(), 2);
        friendMapper.updateFriendshipStatus(reverse.getUserId(), reverse.getFriendUserId(), 2);

        // 删除双方的单聊会话
        Long conversationId = conversationMapper.findSingleConversationId(userId, friendUserId);
        if (conversationId != null) {
            LocalDateTime deletedAt = LocalDateTime.now();
            Long lastMessageId = conversationMapper.findLatestMessageId(conversationId);
            conversationMapper.deleteConversationForMe(conversationId, userId, deletedAt, lastMessageId);
            conversationMapper.deleteConversationForMe(conversationId, friendUserId, deletedAt, lastMessageId);
        }

        syncEventService.recordEvent(
                userId,
                AuthContext.getDeviceId(),
                "friendship",
                "delete",
                "friendship",
                current.getId(),
                Map.of("friendUserId", friendUserId)
        );
        syncEventService.recordEvent(
                friendUserId,
                AuthContext.getDeviceId(),
                "friendship",
                "delete",
                "friendship",
                reverse.getId(),
                Map.of("friendUserId", userId)
        );
    }

    @Transactional
    public FriendView updateRemark(Long userId, Long friendUserId, UpdateFriendRemarkRequest request) {
        assertTargetUserExists(friendUserId);
        getRequiredActiveFriendship(userId, friendUserId);
        if (request == null) {
            throw new BusinessException("备注请求不能为空");
        }
        friendMapper.updateFriendRemark(userId, friendUserId, request.getRemarkName());
        syncEventService.recordEvent(
                userId,
                AuthContext.getDeviceId(),
                "friendship",
                "update",
                "friendship",
                friendUserId,
                Map.of("friendUserId", friendUserId, "remarkName", request.getRemarkName())
        );
        return getRequiredFriendView(userId, friendUserId);
    }

    @Transactional
    public void blockUser(Long userId, Long blockedUserId, BlacklistRequest request) {
        assertTargetUserExists(blockedUserId);
        if (userId.equals(blockedUserId)) {
            throw new BusinessException("不能拉黑自己");
        }
        if (friendMapper.countBlacklist(userId, blockedUserId) > 0) {
            throw new BusinessException("该用户已在黑名单中");
        }
        UserBlacklist userBlacklist = new UserBlacklist();
        userBlacklist.setUserId(userId);
        userBlacklist.setBlockedUserId(blockedUserId);
        userBlacklist.setReason(request == null ? null : request.getReason());
        friendMapper.insertBlacklist(userBlacklist);
        syncEventService.recordEvent(
                userId,
                AuthContext.getDeviceId(),
                "friendship",
                "update",
                "user_blacklist",
                userBlacklist.getId(),
                Map.of("blockedUserId", blockedUserId, "action", "block")
        );
    }

    @Transactional
    public void unblockUser(Long userId, Long blockedUserId) {
        assertTargetUserExists(blockedUserId);
        if (friendMapper.countBlacklist(userId, blockedUserId) == 0) {
            throw new BusinessException("该用户不在黑名单中");
        }
        friendMapper.deleteBlacklist(userId, blockedUserId);
        syncEventService.recordEvent(
                userId,
                AuthContext.getDeviceId(),
                "friendship",
                "update",
                "user_blacklist",
                blockedUserId,
                Map.of("blockedUserId", blockedUserId, "action", "unblock")
        );
    }

    @Transactional
    public void handleRequest(Long userId, Long requestId, HandleFriendRequestRequest request) {
        FriendRequest friendRequest = friendMapper.findFriendRequestById(requestId);
        if (friendRequest == null) {
            throw new BusinessException("好友申请不存在");
        }
        if (!userId.equals(friendRequest.getToUserId())) {
            throw new BusinessException("只能处理发给自己的申请");
        }
        if (!"pending".equals(friendRequest.getStatus())) {
            throw new BusinessException("该申请已处理");
        }
        if (request == null || !StringUtils.hasText(request.getAction())) {
            throw new BusinessException("处理动作不能为空");
        }
        if ("accept".equalsIgnoreCase(request.getAction())) {
            acceptRequestInternal(friendRequest, userId);
            return;
        }
        if ("reject".equalsIgnoreCase(request.getAction())) {
            friendMapper.updateFriendRequestStatus(friendRequest.getId(), "rejected", userId);
            notificationService.deleteByRelated(friendRequest.getToUserId(), "friend_request", friendRequest.getId());
            syncEventService.recordEvent(friendRequest.getFromUserId(), AuthContext.getDeviceId(), "friendship", "update", "friend_request", friendRequest.getId(), Map.of("status", "rejected"));
            return;
        }
        throw new BusinessException("不支持的处理动作");
    }

    private void acceptRequestInternal(FriendRequest friendRequest, Long handledBy) {
        List<FriendRequest> pendingRequests = friendMapper.findPendingFriendRequestsBetweenUsers(
                friendRequest.getFromUserId(),
                friendRequest.getToUserId()
        );
        if (CollectionUtils.isEmpty(pendingRequests)) {
            pendingRequests = List.of(friendRequest);
        }
        for (FriendRequest pendingRequest : pendingRequests) {
            friendMapper.updateFriendRequestStatus(pendingRequest.getId(), "accepted", handledBy);
            notificationService.deleteByRelated(pendingRequest.getToUserId(), "friend_request", pendingRequest.getId());
        }
        createFriendshipIfAbsent(friendRequest.getFromUserId(), friendRequest.getToUserId(), friendRequest.getId());
        createFriendshipIfAbsent(friendRequest.getToUserId(), friendRequest.getFromUserId(), friendRequest.getId());
        Long conversationId = conversationService.ensureSingleConversation(friendRequest.getFromUserId(), friendRequest.getToUserId());
        
        // 创建通知并推送给申请人
        notificationService.createNotification(friendRequest.getFromUserId(), "friend_request", "好友申请已通过", "你的好友申请已通过，现在可以开始聊天了", "friend_request", friendRequest.getId(), null);
        
        // 推送同步事件给双方
        System.out.println("[DEBUG] 准备推送同步事件给申请人 fromUserId=" + friendRequest.getFromUserId() + ", conversationId=" + conversationId);
        syncEventService.recordEvent(friendRequest.getFromUserId(), AuthContext.getDeviceId(), "friendship", "update", "friend_request", friendRequest.getId(), Map.of("status", "accepted", "friendUserId", friendRequest.getToUserId(), "conversationId", conversationId));
        System.out.println("[DEBUG] 准备推送同步事件给同意人 toUserId=" + friendRequest.getToUserId() + ", conversationId=" + conversationId);
        syncEventService.recordEvent(friendRequest.getToUserId(), AuthContext.getDeviceId(), "friendship", "create", "friendship", friendRequest.getId(), Map.of("friendUserId", friendRequest.getFromUserId(), "conversationId", conversationId));
    }

    private void createFriendshipIfAbsent(Long userId, Long friendUserId, Long requestId) {
        if (friendMapper.countFriendship(userId, friendUserId) > 0) {
            return;
        }
        // 如果存在已删除的好友关系，恢复它
        if (friendMapper.restoreDeletedFriendship(userId, friendUserId, requestId) > 0) {
            return;
        }
        // 否则插入新记录
        WechatUser target = friendMapper.findTargetUserById(friendUserId);
        Friendship friendship = new Friendship();
        friendship.setUserId(userId);
        friendship.setFriendUserId(friendUserId);
        friendship.setSourceRequestId(requestId);
        friendship.setRemarkName(target == null ? null : target.getNickname());
        friendship.setIsStarred(0);
        friendship.setIsMuted(0);
        friendship.setStatus(1);
        friendMapper.insertFriendship(friendship);
    }

    private void assertTargetUserExists(Long userId) {
        if (friendMapper.findTargetUserById(userId) == null) {
            throw new BusinessException("目标用户不存在");
        }
    }

    private Friendship getRequiredActiveFriendship(Long userId, Long friendUserId) {
        Friendship friendship = friendMapper.findFriendship(userId, friendUserId);
        if (friendship == null || !Integer.valueOf(1).equals(friendship.getStatus())) {
            throw new BusinessException("好友关系不存在");
        }
        return friendship;
    }

    private FriendView getRequiredFriendView(Long userId, Long friendUserId) {
        return friendMapper.findFriendsByUserId(userId).stream()
                .filter(item -> friendUserId.equals(item.getFriendUserId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException("好友不存在"));
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new BusinessException("通知扩展信息序列化失败");
        }
    }
}
