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
import com.mingjin.school_wechat.model.entity.UserDevice;
import com.mingjin.school_wechat.model.entity.UserLoginSession;
import com.mingjin.school_wechat.model.entity.UserNotification;
import com.mingjin.school_wechat.model.entity.UserSyncEvent;
import com.mingjin.school_wechat.model.entity.WechatUser;
import com.mingjin.school_wechat.model.request.*;
import com.mingjin.school_wechat.model.view.ConversationMessageView;
import com.mingjin.school_wechat.model.view.ConversationMessagePageView;
import com.mingjin.school_wechat.model.view.ConversationSummaryView;
import com.mingjin.school_wechat.model.view.FriendRequestView;
import com.mingjin.school_wechat.model.view.FriendView;
import com.mingjin.school_wechat.model.view.GroupDetailView;
import com.mingjin.school_wechat.model.view.GroupJoinRequestView;
import com.mingjin.school_wechat.model.view.GroupMemberView;
import com.mingjin.school_wechat.model.view.LoginResponse;
import com.mingjin.school_wechat.model.view.MessageReadEventView;
import com.mingjin.school_wechat.model.view.MessageReadReceiptView;
import com.mingjin.school_wechat.model.view.UserNotificationView;
import com.mingjin.school_wechat.model.view.UserSyncEventView;
import com.mingjin.school_wechat.websocket.WebSocketPushService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ConversationService {

    private final ConversationMapper conversationMapper;
    private final AuthMapper authMapper;
    private final javax.sql.DataSource dataSource;
    private final FileMapper fileMapper;
    private final FileService fileService;
    private final NotificationService notificationService;
    private final SyncEventService syncEventService;
    private final WebSocketPushService webSocketPushService;
    private final ObjectMapper objectMapper;

    public ConversationService(ConversationMapper conversationMapper,
                        AuthMapper authMapper,
                        javax.sql.DataSource dataSource,
                        FileMapper fileMapper,
                        FileService fileService,
                        NotificationService notificationService,
                        SyncEventService syncEventService,
                        WebSocketPushService webSocketPushService,
                        ObjectMapper objectMapper) {
        this.conversationMapper = conversationMapper;
        this.authMapper = authMapper;
        this.dataSource = dataSource;
        this.fileMapper = fileMapper;
        this.fileService = fileService;
        this.notificationService = notificationService;
        this.syncEventService = syncEventService;
        this.webSocketPushService = webSocketPushService;
        this.objectMapper = objectMapper;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        try (var conn = dataSource.getConnection();
             var rs = conn.getMetaData().getColumns(null, null, "conversation", "is_official")) {
            if (!rs.next()) {
                try (var stmt = conn.createStatement()) {
                    stmt.execute("ALTER TABLE conversation ADD COLUMN is_official INT DEFAULT 0");
                }
            }
        } catch (Exception e) {
        }
    }

    public List<ConversationSummaryView> listConversations(Long userId) {
        return conversationMapper.findConversationList(userId).stream()
                .map(summaryView -> {
                    summaryView = formatConversationSummary(summaryView);
                    // 设置是否为群主
                    if ("group".equals(summaryView.getConversationType())) {
                        ConversationMember member = conversationMapper.findConversationMember(summaryView.getConversationId(), userId);
                        summaryView.setIsGroupOwner(member != null && "owner".equals(member.getMemberRole()));
                    } else {
                        summaryView.setIsGroupOwner(false);
                    }
                    return summaryView;
                })
                .collect(Collectors.toList());
    }

    @Transactional
    public ConversationSummaryView hideConversation(Long userId, Long conversationId, boolean isHidden) {
        assertConversationMember(conversationId, userId);
        ensureConversationSetting(conversationId, userId);
        conversationMapper.updateConversationHidden(conversationId, userId, isHidden ? 1 : 0);
        syncEventService.recordEvent(
                userId,
                AuthContext.getDeviceId(),
                "setting",
                "update",
                "conversation_user_setting",
                conversationId,
                Map.of("conversationId", conversationId, "isHidden", isHidden)
        );
        return pushAndGetConversationState(conversationId, userId);
    }

    @Transactional
    public ConversationSummaryView topConversation(Long userId, Long conversationId, boolean isTop) {
        assertConversationMember(conversationId, userId);
        ensureConversationSetting(conversationId, userId);
        conversationMapper.updateConversationTop(conversationId, userId, isTop ? 1 : 0);
        syncEventService.recordEvent(
                userId,
                AuthContext.getDeviceId(),
                "setting",
                "update",
                "conversation_user_setting",
                conversationId,
                Map.of("conversationId", conversationId, "isTop", isTop)
        );
        return pushAndGetConversationState(conversationId, userId);
    }

    @Transactional
    public ConversationSummaryView muteConversation(Long userId, Long conversationId, boolean isMuted) {
        assertConversationMember(conversationId, userId);
        ensureConversationSetting(conversationId, userId);
        conversationMapper.updateConversationMuted(conversationId, userId, isMuted ? 1 : 0);
        syncEventService.recordEvent(
                userId,
                AuthContext.getDeviceId(),
                "setting",
                "update",
                "conversation_user_setting",
                conversationId,
                Map.of("conversationId", conversationId, "isMuted", isMuted)
        );
        return pushAndGetConversationState(conversationId, userId);
    }

    @Transactional
    public ConversationSummaryView saveDraft(Long userId, Long conversationId, ConversationDraftRequest request) {
        assertConversationMember(conversationId, userId);
        ensureConversationSetting(conversationId, userId);
        String draftContent = request == null ? null : request.getDraftContent();
        conversationMapper.updateConversationDraft(conversationId, userId, draftContent);
        syncEventService.recordEvent(
                userId,
                AuthContext.getDeviceId(),
                "setting",
                "update",
                "conversation_user_setting",
                conversationId,
                Map.of("conversationId", conversationId, "draftContent", draftContent == null ? "" : draftContent)
        );
        return pushAndGetConversationState(conversationId, userId);
    }

    @Transactional
    public ConversationSummaryView clearUnread(Long userId, Long conversationId) {
        assertConversationMember(conversationId, userId);
        List<Long> unreadMessageIds = conversationMapper.findUnreadMessageIdsForUser(conversationId, userId);
        Long lastMessageId = conversationMapper.findLatestMessageId(conversationId);
        
        // 只更新会话的已读状态，避免批量更新消息状态导致死锁
        conversationMapper.markConversationRead(conversationId, userId, lastMessageId);
        
        pushReadReceiptUpdates(conversationId, userId, unreadMessageIds, LocalDateTime.now());
        syncEventService.recordEvent(
                userId,
                AuthContext.getDeviceId(),
                "conversation",
                "read",
                "conversation",
                conversationId,
                Map.of("conversationId", conversationId, "action", "clear_unread", "lastReadMessageId", lastMessageId == null ? 0L : lastMessageId)
        );
        return pushAndGetConversationState(conversationId, userId);
    }

    @Transactional
    public ConversationSummaryView clearMessagesForMe(Long userId, Long conversationId) {
        assertConversationMember(conversationId, userId);
        ensureConversationSetting(conversationId, userId);
        List<Long> unreadMessageIds = conversationMapper.findUnreadMessageIdsForUser(conversationId, userId);
        Long lastMessageId = conversationMapper.findLatestMessageId(conversationId);
        LocalDateTime clearedAt = LocalDateTime.now();
        conversationMapper.clearConversationMessagesForMe(conversationId, userId, clearedAt, lastMessageId);
        
        // 只更新会话的已读状态，避免批量更新消息状态导致死锁
        conversationMapper.markConversationRead(conversationId, userId, lastMessageId);
        
        pushReadReceiptUpdates(conversationId, userId, unreadMessageIds, clearedAt);
        syncEventService.recordEvent(
                userId,
                AuthContext.getDeviceId(),
                "conversation",
                "update",
                "conversation_user_setting",
                conversationId,
                Map.of(
                        "conversationId", conversationId,
                        "action", "clear_messages_for_me",
                        "clearMessageBefore", clearedAt.toString(),
                        "lastReadMessageId", lastMessageId == null ? 0L : lastMessageId
                )
        );
        return pushAndGetConversationState(conversationId, userId);
    }

    @Transactional
    public ConversationSummaryView deleteConversationForMe(Long userId, Long conversationId) {
        assertConversationMember(conversationId, userId);
        ensureConversationSetting(conversationId, userId);
        Long lastMessageId = conversationMapper.findLatestMessageId(conversationId);
        LocalDateTime deletedAt = LocalDateTime.now();
        conversationMapper.deleteConversationForMe(conversationId, userId, deletedAt, lastMessageId);
        syncEventService.recordEvent(
                userId,
                AuthContext.getDeviceId(),
                "conversation",
                "delete",
                "conversation_user_setting",
                conversationId,
                Map.of(
                        "conversationId", conversationId,
                        "action", "delete_for_me",
                        "clearMessageBefore", deletedAt.toString(),
                        "lastReadMessageId", lastMessageId == null ? 0L : lastMessageId
                )
        );
        return pushAndGetConversationState(conversationId, userId);
    }

    public GroupDetailView getGroupDetail(Long userId, Long conversationId) {
        getRequiredGroupConversation(conversationId);
        getRequiredActiveMember(conversationId, userId);
        GroupDetailView detailView = conversationMapper.findGroupDetail(conversationId, userId);
        if (detailView == null) {
            throw new BusinessException("群聊不存在");
        }
        return detailView;
    }

    public ConversationSummaryView searchGroupByConversationId(Long userId, Long conversationId) {
        Conversation conversation = conversationMapper.findConversationById(conversationId);
        if (conversation == null || !"group".equals(conversation.getConversationType())) {
            throw new BusinessException("群聊不存在");
        }
        if (conversation.getStatus() != 1) {
            throw new BusinessException("群聊已解散");
        }
        // 构建群摘要信息，不依赖用户成员关系
        ConversationSummaryView summaryView = new ConversationSummaryView();
        summaryView.setConversationId(conversation.getId());
        summaryView.setConversationType("group");
        summaryView.setConversationName(conversation.getName());
        summaryView.setAvatarUrl(conversation.getAvatarUrl());
        summaryView.setAnnouncement(conversation.getAnnouncement());
        summaryView.setLastMessageType(conversation.getLastMessageType());
        summaryView.setLastMessageContent(conversation.getLastMessageContent());
        summaryView.setLastSenderId(conversation.getLastSenderId());
        summaryView.setLastMessageAt(conversation.getLastMessageAt());
        // 非成员用户，未读数为0
        summaryView.setUnreadCount(0);
        summaryView.setIsTop(0);
        summaryView.setIsMuted(0);
        summaryView.setIsHidden(0);
        summaryView.setDraftContent(null);
        return summaryView;
    }

    public List<ConversationSummaryView> searchGroupsByName(Long userId, String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new BusinessException("群名不能为空");
        }
        List<Conversation> groups = conversationMapper.searchGroupsByKeywordOrdered(name.trim());
        return groups.stream().map(conversation -> {
            ConversationSummaryView view = new ConversationSummaryView();
            view.setConversationId(conversation.getId());
            view.setConversationType("group");
            view.setConversationName(conversation.getName());
            view.setAvatarUrl(conversation.getAvatarUrl());
            view.setAnnouncement(conversation.getAnnouncement());
            view.setUnreadCount(0);
            view.setIsTop(0);
            view.setIsMuted(0);
            view.setIsHidden(0);
            view.setIsOfficial(conversation.getIsOfficial() != null ? conversation.getIsOfficial() : 0);
            return view;
        }).collect(Collectors.toList());
    }

    public List<GroupMemberView> listGroupMembers(Long userId, Long conversationId) {
        getRequiredGroupConversation(conversationId);
        getRequiredActiveMember(conversationId, userId);
        return conversationMapper.findGroupMembers(conversationId, userId);
    }

    public List<GroupJoinRequestView> listGroupJoinRequests(Long userId, Long conversationId) {
        getRequiredGroupConversation(conversationId);
        ConversationMember currentMember = getRequiredActiveMember(conversationId, userId);
        // 移除权限检查，允许所有成员调用（但只有管理员能看到数据）
        // assertManagerRole(currentMember);
        return conversationMapper.findConversationJoinRequests(conversationId);
    }

    @Transactional
    public String applyJoinGroup(Long userId, Long conversationId, JoinGroupRequest request) {
        Conversation conversation = getRequiredGroupConversation(conversationId);
        ConversationMember existingMember = conversationMapper.findConversationMember(conversationId, userId);
        if (existingMember != null && Integer.valueOf(1).equals(existingMember.getStatus())) {
            throw new BusinessException("你已在群聊中");
        }
        if (conversationMapper.countActiveMembers(conversationId) >= conversation.getMaxMemberCount()) {
            throw new BusinessException("群成员数量已达上限");
        }
        if ("invite_only".equals(conversation.getJoinRule())) {
            throw new BusinessException("该群仅支持邀请加入");
        }
        if ("direct".equals(conversation.getJoinRule())) {
            addOrReactivateGroupMember(conversationId, userId, null, "direct");
            List<Long> memberIds = conversationMapper.findMemberUserIds(conversationId);
            recordGroupEventForMembers(memberIds, conversationId, "join_member", Map.of("targetUserId", userId));
            pushConversationStateToMembers(conversationId, memberIds);
            return "加入群聊成功";
        }
        if (conversationMapper.countPendingJoinRequest(conversationId, userId) > 0) {
            throw new BusinessException("你已提交过入群申请");
        }
        conversationMapper.insertConversationJoinRequest(
                conversationId,
                userId,
                null,
                request == null ? null : request.getRequestMessage(),
                "pending",
                null,
                null
        );
        notifyGroupManagers(conversation, userId, "group_join_request", "收到新的入群申请", "有新的入群申请待处理");
        return "入群申请已提交";
    }

    @Transactional
    public List<GroupJoinRequestView> handleGroupJoinRequest(Long userId,
                                                             Long conversationId,
                                                             Long requestId,
                                                             HandleGroupJoinRequest request) {
        Conversation conversation = getRequiredGroupConversation(conversationId);
        ConversationMember currentMember = getRequiredActiveMember(conversationId, userId);
        assertManagerRole(currentMember);
        GroupJoinRequestView joinRequestView = conversationMapper.findConversationJoinRequestById(conversationId, requestId);
        if (joinRequestView == null) {
            throw new BusinessException("入群申请不存在");
        }
        if (!"pending".equals(joinRequestView.getStatus())) {
            throw new BusinessException("该申请已处理");
        }
        String action = request == null ? null : request.getAction();
        if (!"accept".equals(action) && !"reject".equals(action)) {
            throw new BusinessException("处理动作不合法");
        }
        if ("accept".equals(action)) {
            if (conversationMapper.countActiveMembers(conversationId) >= conversation.getMaxMemberCount()) {
                throw new BusinessException("群成员数量已达上限");
            }
            addOrReactivateGroupMember(conversationId, joinRequestView.getApplicantUserId(), userId, "approval");
            notificationService.createNotification(
                    joinRequestView.getApplicantUserId(),
                    "group_join_result",
                    "入群申请已通过",
                    "你加入群聊 " + conversation.getName() + " 的申请已通过",
                    "conversation",
                    conversationId,
                    null
            );
        } else {
            notificationService.createNotification(
                    joinRequestView.getApplicantUserId(),
                    "group_join_result",
                    "入群申请未通过",
                    "你加入群聊 " + conversation.getName() + " 的申请未通过",
                    "conversation",
                    conversationId,
                    null
            );
        }
        conversationMapper.handleConversationJoinRequest(
                conversationId,
                requestId,
                "accept".equals(action) ? "accepted" : "rejected",
                userId,
                LocalDateTime.now()
        );
        List<Long> memberIds = conversationMapper.findMemberUserIds(conversationId);
        recordGroupEventForMembers(memberIds, conversationId, "join_request_" + action, Map.of("requestId", requestId, "applicantUserId", joinRequestView.getApplicantUserId()));
        pushConversationStateToMembers(conversationId, memberIds);
        return conversationMapper.findConversationJoinRequests(conversationId);
    }

    @Transactional
    public Long ensureSingleConversation(Long userId, Long friendUserId) {
        Long conversationId = conversationMapper.findSingleConversationId(userId, friendUserId);
        if (conversationId != null) {
            ensureConversationSetting(conversationId, userId);
            ensureConversationSetting(conversationId, friendUserId);
            pushConversationStateToUser(conversationId, userId);
            pushConversationStateToUser(conversationId, friendUserId);
            return conversationId;
        }
        Conversation conversation = new Conversation();
        conversation.setConversationType("single");
        conversation.setDescriptionText("单聊会话");
        conversation.setJoinRule("direct");
        conversation.setMaxMemberCount(2);
        conversation.setMuteAll(0);
        conversation.setStatus(1);
        conversationMapper.insertConversation(conversation);

        insertConversationMember(conversation.getId(), userId, "member", null, "direct", null);
        insertConversationMember(conversation.getId(), friendUserId, "member", null, "direct", userId);
        insertConversationSetting(conversation.getId(), userId);
        insertConversationSetting(conversation.getId(), friendUserId);
        pushConversationStateToUser(conversation.getId(), userId);
        pushConversationStateToUser(conversation.getId(), friendUserId);

        syncEventService.recordEvent(userId, AuthContext.getDeviceId(), "conversation", "create", "conversation", conversation.getId(), Map.of("conversationType", "single"));
        syncEventService.recordEvent(friendUserId, AuthContext.getDeviceId(), "conversation", "create", "conversation", conversation.getId(), Map.of("conversationType", "single"));
        return conversation.getId();
    }

    private static final List<String> OFFICIAL_GROUP_NAMES = List.of("上网群");

    @Transactional
    public Conversation createGroup(Long creatorUserId, CreateGroupRequest request) {
        if (request == null || !StringUtils.hasText(request.getName())) {
            throw new BusinessException("群名称不能为空");
        }
        String groupName = request.getName().trim();
        if (groupName.length() > 50) {
            throw new BusinessException("群名称不能超过50个字符");
        }
        boolean isOfficialName = OFFICIAL_GROUP_NAMES.stream().anyMatch(groupName::contains);
        if (isOfficialName) {
            WechatUser creator = authMapper.findUserById(creatorUserId);
            if (creator == null || !"zxh".equals(creator.getUsername())) {
                throw new BusinessException("包含\"" + String.join("\"、\"", OFFICIAL_GROUP_NAMES) + "\"的群名仅限管理员创建");
            }
        }
        Set<Long> memberIds = new LinkedHashSet<>();
        memberIds.add(creatorUserId);
        if (!CollectionUtils.isEmpty(request.getMemberUserIds())) {
            memberIds.addAll(request.getMemberUserIds());
        }
        if (memberIds.size() > 500) {
            throw new BusinessException("群成员数量不能超过500人");
        }

        Conversation conversation = new Conversation();
        conversation.setConversationType("group");
        conversation.setName(groupName);
        conversation.setOwnerUserId(creatorUserId);
        conversation.setDescriptionText("群聊会话");
        conversation.setAnnouncement(request.getAnnouncement());
        conversation.setJoinRule("approval");
        conversation.setMaxMemberCount(500);
        conversation.setMuteAll(0);
        conversation.setIsOfficial(isOfficialName ? 1 : 0);
        conversation.setStatus(1);
        conversationMapper.insertConversation(conversation);

        for (Long memberId : memberIds) {
            WechatUser user = authMapper.findUserById(memberId);
            if (user == null) {
                throw new BusinessException("群成员不存在: " + memberId);
            }
            insertConversationMember(
                    conversation.getId(),
                    memberId,
                    creatorUserId.equals(memberId) ? "owner" : "member",
                    user.getNickname(),
                    creatorUserId.equals(memberId) ? "direct" : "invite",
                    creatorUserId.equals(memberId) ? null : creatorUserId
            );
            insertConversationSetting(conversation.getId(), memberId);
            if (!creatorUserId.equals(memberId)) {
                notificationService.createNotification(
                        memberId,
                        "group_invite",
                        "你已加入群聊",
                        "你已被邀请加入群聊：" + request.getName(),
                        "conversation",
                        conversation.getId(),
                        null
                );
            }
            syncEventService.recordEvent(
                    memberId,
                    AuthContext.getDeviceId(),
                    "conversation",
                    "create",
                    "conversation",
                    conversation.getId(),
                    Map.of("conversationType", "group", "name", request.getName())
            );
        }
        return conversation;
    }

    @Transactional
    public List<GroupMemberView> inviteGroupMembers(Long operatorUserId,
                                                    Long conversationId,
                                                    InviteGroupMembersRequest request) {
        Conversation conversation = getRequiredGroupConversation(conversationId);
        ConversationMember operatorMember = getRequiredActiveMember(conversationId, operatorUserId);
        // 所有群成员都有权限邀请好友进群，不需要管理员权限
        if (request == null || CollectionUtils.isEmpty(request.getMemberUserIds())) {
            throw new BusinessException("邀请成员不能为空");
        }

        Set<Long> targetUserIds = request.getMemberUserIds().stream()
                .filter(id -> id != null && !id.equals(operatorUserId))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        int activeCount = conversationMapper.countActiveMembers(conversationId);
        int validInviteCount = 0;
        List<Long> validUserIds = new ArrayList<>();
        
        // 先检查哪些用户是有效的邀请目标
        for (Long targetUserId : targetUserIds) {
            ConversationMember existingMember = conversationMapper.findConversationMember(conversationId, targetUserId);
            if (existingMember != null && Integer.valueOf(1).equals(existingMember.getStatus())) {
                // 用户已在群聊中，跳过
                continue;
            }
            validUserIds.add(targetUserId);
            validInviteCount++;
        }
        
        if (validUserIds.isEmpty()) {
            throw new BusinessException("没有可邀请的成员");
        }
        
        if (activeCount + validInviteCount > conversation.getMaxMemberCount()) {
            throw new BusinessException("群成员数量已达上限");
        }

        for (Long targetUserId : validUserIds) {
            WechatUser user = authMapper.findUserById(targetUserId);
            if (user == null) {
                throw new BusinessException("群成员不存在: " + targetUserId);
            }
            ConversationMember existingMember = conversationMapper.findConversationMember(conversationId, targetUserId);
            if (existingMember == null) {
                insertConversationMember(conversationId, targetUserId, "member", user.getNickname(), "invite", operatorUserId);
            } else {
                existingMember.setMemberRole("member");
                existingMember.setDisplayName(user.getNickname());
                existingMember.setJoinSource("invite");
                existingMember.setInviterUserId(operatorUserId);
                existingMember.setStatus(1);
                existingMember.setJoinedAt(LocalDateTime.now());
                conversationMapper.reactivateConversationMember(existingMember);
            }

            ensureConversationSetting(conversationId, targetUserId);
            notificationService.createNotification(
                    targetUserId,
                    "group_invite",
                    "你已加入群聊",
                    "你已被邀请加入群聊：" + conversation.getName(),
                    "conversation",
                    conversationId,
                    null
            );
            syncEventService.recordEvent(
                    targetUserId,
                    AuthContext.getDeviceId(),
                    "conversation",
                    "create",
                    "conversation",
                    conversationId,
                    Map.of("conversationType", "group", "action", "invite")
            );
        }

        List<Long> activeMembers = conversationMapper.findMemberUserIds(conversationId);
        for (Long memberId : activeMembers) {
            syncEventService.recordEvent(
                    memberId,
                    AuthContext.getDeviceId(),
                    "conversation",
                    "update",
                    "conversation",
                    conversationId,
                    Map.of("action", "invite_member", "operatorUserId", operatorUserId)
            );
        }
        pushConversationStateToMembers(conversationId, activeMembers);
        return conversationMapper.findGroupMembers(conversationId, operatorUserId);
    }

    @Transactional
    public GroupDetailView updateGroupMuteAll(Long userId,
                                               Long conversationId,
                                               UpdateGroupMuteAllRequest request) {
        getRequiredGroupConversation(conversationId);
        ConversationMember currentMember = getRequiredActiveMember(conversationId, userId);
        assertManagerRole(currentMember);
        int muteAll = request != null && Integer.valueOf(1).equals(request.getMuteAll()) ? 1 : 0;
        conversationMapper.updateConversationMuteAll(conversationId, muteAll);
        List<Long> memberIds = conversationMapper.findMemberUserIds(conversationId);
        recordGroupEventForMembers(memberIds, conversationId, "mute_all", Map.of("muteAll", muteAll));
        pushConversationStateToMembers(conversationId, memberIds);
        return conversationMapper.findGroupDetail(conversationId, userId);
    }

    @Transactional
    public GroupDetailView updateGroupJoinRule(Long userId,
                                               Long conversationId,
                                               UpdateGroupJoinRuleRequest request) {
        Conversation conversation = getRequiredGroupConversation(conversationId);
        ConversationMember currentMember = getRequiredActiveMember(conversationId, userId);
        assertManagerRole(currentMember);
        if (request == null || request.getJoinRule() == null || request.getJoinRule().trim().isEmpty()) {
            throw new BusinessException("入群方式不能为空");
        }
        String joinRule = request.getJoinRule().trim();
        if (!"direct".equals(joinRule) && !"approval".equals(joinRule) && !"invite_only".equals(joinRule)) {
            throw new BusinessException("入群方式仅支持 direct、approval 或 invite_only");
        }
        conversationMapper.updateConversationJoinRule(conversationId, joinRule);
        List<Long> memberIds = conversationMapper.findMemberUserIds(conversationId);
        recordGroupEventForMembers(memberIds, conversationId, "join_rule", Map.of("joinRule", joinRule));
        pushConversationStateToMembers(conversationId, memberIds);
        return conversationMapper.findGroupDetail(conversationId, userId);
    }

    @Transactional
    public List<GroupMemberView> updateGroupMemberRole(Long userId,
                                                       Long conversationId,
                                                       Long memberUserId,
                                                       UpdateGroupMemberRoleRequest request) {
        Conversation conversation = getRequiredGroupConversation(conversationId);
        ConversationMember currentMember = getRequiredActiveMember(conversationId, userId);
        ConversationMember targetMember = getRequiredActiveMember(conversationId, memberUserId);
        if (!"owner".equals(currentMember.getMemberRole())) {
            throw new BusinessException("只有群主可以设置管理员");
        }
        if (conversation.getOwnerUserId().equals(memberUserId)) {
            throw new BusinessException("不能修改群主角色");
        }
        String targetRole = request == null ? null : request.getMemberRole();
        if (!"admin".equals(targetRole) && !"member".equals(targetRole)) {
            throw new BusinessException("角色仅支持 admin 或 member");
        }
        conversationMapper.updateConversationMemberRole(conversationId, memberUserId, targetRole);
        notificationService.createNotification(
                memberUserId,
                "group_role",
                "群角色已变更",
                "你在群聊 " + conversation.getName() + " 中的角色已更新为 " + ("admin".equals(targetRole) ? "管理员" : "普通成员"),
                "conversation",
                conversationId,
                null
        );
        List<Long> memberIds = conversationMapper.findMemberUserIds(conversationId);
        recordGroupEventForMembers(memberIds, conversationId, "member_role", Map.of("targetUserId", memberUserId, "memberRole", targetRole));
        pushConversationStateToMembers(conversationId, memberIds);
        return conversationMapper.findGroupMembers(conversationId, userId);
    }

    @Transactional
    public List<GroupMemberView> muteGroupMember(Long userId,
                                                 Long conversationId,
                                                 Long memberUserId,
                                                 MuteGroupMemberRequest request) {
        Conversation conversation = getRequiredGroupConversation(conversationId);
        ConversationMember currentMember = getRequiredActiveMember(conversationId, userId);
        ConversationMember targetMember = getRequiredActiveMember(conversationId, memberUserId);
        assertCanMuteMember(currentMember, targetMember, conversation.getOwnerUserId());
        int muted = request != null && Integer.valueOf(1).equals(request.getMuted()) ? 1 : 0;
        LocalDateTime muteUntil = null;
        if (muted == 1) {
            Integer muteMinutes = request == null ? null : request.getMuteMinutes();
            if (muteMinutes == null || muteMinutes <= 0) {
                throw new BusinessException("禁言时长必须大于 0");
            }
            muteUntil = LocalDateTime.now().plusMinutes(muteMinutes);
        }
        conversationMapper.updateConversationMemberMute(conversationId, memberUserId, muted, muteUntil);
        notificationService.createNotification(
                memberUserId,
                "group_mute",
                muted == 1 ? "你已被禁言" : "你已被解除禁言",
                muted == 1 ? "你在群聊 " + conversation.getName() + " 中被禁言" : "你在群聊 " + conversation.getName() + " 中已解除禁言",
                "conversation",
                conversationId,
                null
        );
        List<Long> memberIds = conversationMapper.findMemberUserIds(conversationId);
        recordGroupEventForMembers(memberIds, conversationId, "member_mute", Map.of("targetUserId", memberUserId, "muted", muted));
        pushConversationStateToMembers(conversationId, memberIds);
        return conversationMapper.findGroupMembers(conversationId, userId);
    }

    @Transactional
    public GroupDetailView transferGroupOwner(Long userId,
                                              Long conversationId,
                                              TransferGroupOwnerRequest request) {
        Conversation conversation = getRequiredGroupConversation(conversationId);
        ConversationMember currentMember = getRequiredActiveMember(conversationId, userId);
        if (!"owner".equals(currentMember.getMemberRole())) {
            throw new BusinessException("只有群主可以转让群主");
        }
        if (request == null || request.getTargetUserId() == null) {
            throw new BusinessException("目标成员不能为空");
        }
        ConversationMember targetMember = getRequiredActiveMember(conversationId, request.getTargetUserId());
        if (userId.equals(request.getTargetUserId())) {
            throw new BusinessException("不能转让给自己");
        }
        conversationMapper.updateConversationOwner(conversationId, request.getTargetUserId());
        conversationMapper.updateConversationMemberRole(conversationId, userId, "admin");
        conversationMapper.updateConversationMemberRole(conversationId, request.getTargetUserId(), "owner");
        notificationService.createNotification(
                request.getTargetUserId(),
                "group_owner_transfer",
                "你已成为新群主",
                "你已成为群聊 " + conversation.getName() + " 的新群主",
                "conversation",
                conversationId,
                null
        );
        List<Long> memberIds = conversationMapper.findMemberUserIds(conversationId);
        recordGroupEventForMembers(memberIds, conversationId, "owner_transfer", Map.of("targetUserId", request.getTargetUserId()));
        pushConversationStateToMembers(conversationId, memberIds);
        return conversationMapper.findGroupDetail(conversationId, userId);
    }

    // 移除 @Transactional，改为手动管理事务，提高并发性能
    public ChatMessage sendMessage(Long userId, Long conversationId, SendMessageRequest request) {
        assertConversationMember(conversationId, userId);
        assertCanSendMessage(conversationId, userId);
        if (request == null || !StringUtils.hasText(request.getMessageType())) {
            throw new BusinessException("消息类型不能为空");
        }
        String messageType = request.getMessageType().trim();
        FileResource fileResource = validateAndGetMessageFile(messageType, request.getFileId());
        String content = resolveMessageContent(messageType, request.getContent(), fileResource);
        if (!StringUtils.hasText(content)) {
            throw new BusinessException("消息内容不能为空");
        }
        if (request.getQuoteMessageId() != null) {
            ChatMessage quoteMessage = conversationMapper.findChatMessageById(request.getQuoteMessageId());
            if (quoteMessage == null || !conversationId.equals(quoteMessage.getConversationId())) {
                throw new BusinessException("引用消息不存在");
            }
        }

        ChatMessage chatMessage = new ChatMessage();
        LocalDateTime now = LocalDateTime.now();
        chatMessage.setConversationId(conversationId);
        chatMessage.setSenderUserId(userId);
        chatMessage.setMessageType(messageType);
        chatMessage.setMessageStatus("sent");
        chatMessage.setContent(content);
        chatMessage.setContentJson(buildContentJson(request));
        chatMessage.setClientMessageId("msg_" + UUID.randomUUID());
        chatMessage.setQuoteMessageId(request.getQuoteMessageId());
        chatMessage.setIsRecalled(0);
        chatMessage.setSentAt(now);
        conversationMapper.insertChatMessage(chatMessage);

        if (fileResource != null) {
            conversationMapper.insertMessageAttachment(chatMessage.getId(), fileResource.getId());
        }

        // 发送消息时自动清空草稿
        conversationMapper.updateConversationDraft(conversationId, userId, "");

        ConversationMessageView messageView = formatConversationMessage(conversationMapper.findMessageById(chatMessage.getId(), userId));

        // 异步插入消息状态和推送，提高响应速度
        List<Long> memberIds = conversationMapper.findMemberUserIds(conversationId);
        for (Long memberId : memberIds) {
            boolean isSender = userId.equals(memberId);
            boolean isMentioned = request.getMentionUserIds() != null && request.getMentionUserIds().contains(memberId);
            conversationMapper.insertMessageUserStatus(chatMessage.getId(), memberId, now, isSender ? now : null, 0, isMentioned ? 1 : 0);
        }
        
        // 异步更新未读计数
        for (Long memberId : memberIds) {
            if (!userId.equals(memberId)) {
                conversationMapper.increaseUnreadCount(conversationId, memberId);
            }
        }

        conversationMapper.updateConversationLastMessage(conversationId, chatMessage.getId(), messageType, content, userId, now);
        
        // 立即推送消息，不等待事务提交
        if (messageView != null) {
            for (Long memberId : memberIds) {
                webSocketPushService.pushChatMessage(memberId, messageView);
            }
            pushConversationStateToMembers(conversationId, memberIds);
        }
        
        return chatMessage;
    }

    @Transactional
    public List<ConversationMessageView> listMessages(Long userId, Long conversationId) {
        return listMessagesPage(userId, conversationId, null, 50).getList();
    }

    public ConversationMessagePageView listMessagesPage(Long userId,
                                                        Long conversationId,
                                                        Long beforeMessageId,
                                                        Integer limit) {
        assertConversationMember(conversationId, userId);
        List<Long> unreadMessageIds = conversationMapper.findUnreadMessageIdsForUser(conversationId, userId);
        Long lastMessageId = conversationMapper.findLatestMessageId(conversationId);
        if (lastMessageId != null) {
            LocalDateTime readAt = LocalDateTime.now();
            // 只更新会话的已读状态，避免批量更新消息状态导致死锁
            conversationMapper.markConversationRead(conversationId, userId, lastMessageId);
            
            pushReadReceiptUpdates(conversationId, userId, unreadMessageIds, readAt);
            pushConversationStateToUser(conversationId, userId);
            // 异步推送已读状态，不阻塞主流程
            recordReadEventAsync(userId, conversationId, lastMessageId);
        }
        int pageSize = normalizePageSize(limit);
        List<ConversationMessageView> messages = conversationMapper.findMessagesPage(
                conversationId,
                userId,
                beforeMessageId,
                pageSize + 1
        );
        boolean hasMore = messages.size() > pageSize;
        if (hasMore) {
            messages = messages.subList(messages.size() - pageSize, messages.size());
        }
        ConversationMessagePageView pageView = new ConversationMessagePageView();
        pageView.setList(messages.stream().map(this::formatConversationMessage).collect(Collectors.toList()));
        pageView.setLimit(pageSize);
        pageView.setHasMore(hasMore);
        pageView.setNextBeforeMessageId(messages.isEmpty() ? null : messages.get(0).getMessageId());
        return pageView;
    }

    private void recordReadEventAsync(Long userId, Long conversationId, Long lastReadMessageId) {
        try {
            syncEventService.recordEvent(
                    userId,
                    AuthContext.getDeviceId(),
                    "conversation",
                    "read",
                    "conversation",
                    conversationId,
                    Map.of("lastReadMessageId", lastReadMessageId)
            );
        } catch (Exception e) {
            // 同步事件记录失败不影响消息列表读取，静默处理
        }
    }

    public MessageReadReceiptView getMessageReadReceipt(Long userId, Long conversationId, Long messageId) {
        assertConversationMember(conversationId, userId);
        ChatMessage chatMessage = conversationMapper.findChatMessageById(messageId);
        if (chatMessage == null || !conversationId.equals(chatMessage.getConversationId())) {
            throw new BusinessException("消息不存在");
        }
        MessageReadReceiptView receiptView = new MessageReadReceiptView();
        receiptView.setMessageId(messageId);
        receiptView.setReaders(conversationMapper.findMessageReaders(conversationId, messageId));
        receiptView.setReadCount(conversationMapper.countMessageReaders(conversationId, messageId));
        receiptView.setUnreadCount(conversationMapper.countMessageUnreadUsers(conversationId, messageId));
        return receiptView;
    }

    public ConversationMessagePageView searchMessages(Long userId,
                                                      Long conversationId,
                                                      String keyword,
                                                      String messageType,
                                                      Long beforeMessageId,
                                                      Integer limit) {
        assertConversationMember(conversationId, userId);
        int pageSize = normalizePageSize(limit);
        String normalizedKeyword = StringUtils.hasText(keyword) ? "%" + keyword.trim() + "%" : null;
        String normalizedMessageType = StringUtils.hasText(messageType) ? messageType.trim() : null;
        List<ConversationMessageView> messages = conversationMapper.searchMessagesPage(
                conversationId,
                userId,
                beforeMessageId,
                normalizedMessageType,
                normalizedKeyword,
                pageSize + 1
        );
        boolean hasMore = messages.size() > pageSize;
        if (hasMore) {
            messages = messages.subList(messages.size() - pageSize, messages.size());
        }
        ConversationMessagePageView pageView = new ConversationMessagePageView();
        pageView.setList(messages.stream().map(this::formatConversationMessage).collect(Collectors.toList()));
        pageView.setLimit(pageSize);
        pageView.setHasMore(hasMore);
        pageView.setNextBeforeMessageId(messages.isEmpty() ? null : messages.get(0).getMessageId());
        return pageView;
    }

    @Transactional
    public void deleteMessageForMe(Long userId, Long conversationId, Long messageId) {
        assertConversationMember(conversationId, userId);
        ChatMessage chatMessage = conversationMapper.findChatMessageById(messageId);
        if (chatMessage == null || !conversationId.equals(chatMessage.getConversationId())) {
            throw new BusinessException("消息不存在");
        }
        int updated = conversationMapper.deleteMessageForMe(conversationId, messageId, userId);
        if (updated <= 0) {
            LocalDateTime now = LocalDateTime.now();
            conversationMapper.upsertDeletedMessageForMe(
                    messageId,
                    userId,
                    now,
                    userId.equals(chatMessage.getSenderUserId()) ? now : null,
                    0
            );
        }
        syncEventService.recordEvent(
                userId,
                AuthContext.getDeviceId(),
                "message",
                "delete",
                "message_user_status",
                messageId,
                Map.of("conversationId", conversationId, "messageId", messageId, "action", "delete_for_me")
        );
    }

    @Transactional
    public ConversationMessageView recallMessage(Long userId, Long conversationId, Long messageId) {
        assertConversationMember(conversationId, userId);
        ChatMessage chatMessage = conversationMapper.findChatMessageById(messageId);
        if (chatMessage == null || !conversationId.equals(chatMessage.getConversationId())) {
            throw new BusinessException("消息不存在");
        }
        if (!userId.equals(chatMessage.getSenderUserId())) {
            throw new BusinessException("只能撤回自己发送的消息");
        }
        if (Integer.valueOf(1).equals(chatMessage.getIsRecalled())) {
            throw new BusinessException("该消息已撤回");
        }

        int updatedRows = conversationMapper.recallMessage(messageId, userId, "[消息已撤回]");
        if (updatedRows <= 0) {
            throw new BusinessException("消息撤回失败");
        }

        Long latestMessageId = conversationMapper.findLatestMessageId(conversationId);
        if (messageId.equals(latestMessageId)) {
            conversationMapper.updateConversationLastMessage(
                    conversationId,
                    messageId,
                    "revoke",
                    "[消息已撤回]",
                    userId,
                    LocalDateTime.now()
            );
        }

        ConversationMessageView recalledView = formatConversationMessage(conversationMapper.findMessageById(messageId, userId));
        List<Long> memberIds = conversationMapper.findMemberUserIds(conversationId);
        for (Long memberId : memberIds) {
            if (recalledView != null) {
                webSocketPushService.pushMessageRecalled(memberId, recalledView);
            }
            syncEventService.recordEvent(
                    memberId,
                    AuthContext.getDeviceId(),
                    "message",
                    "update",
                    "chat_message",
                    messageId,
                    Map.of("conversationId", conversationId, "messageStatus", "recalled")
            );
        }
        pushConversationStateToMembers(conversationId, memberIds);
        return recalledView;
    }

    @Transactional
    public List<GroupMemberView> removeGroupMember(Long operatorUserId, Long conversationId, Long memberUserId) {
        Conversation conversation = getRequiredGroupConversation(conversationId);
        ConversationMember operatorMember = getRequiredActiveMember(conversationId, operatorUserId);
        ConversationMember targetMember = getRequiredActiveMember(conversationId, memberUserId);
        if (operatorUserId.equals(memberUserId)) {
            throw new BusinessException("请使用退群接口退出群聊");
        }
        assertCanRemoveMember(operatorMember, targetMember, conversation.getOwnerUserId());

        int updated = conversationMapper.updateConversationMemberStatus(conversationId, memberUserId, 3);
        if (updated <= 0) {
            throw new BusinessException("移除群成员失败");
        }

        notificationService.createNotification(
                memberUserId,
                "group_notice",
                "你已被移出群聊",
                "你已被移出群聊：" + conversation.getName(),
                "conversation",
                conversationId,
                null
        );
        syncEventService.recordEvent(
                memberUserId,
                AuthContext.getDeviceId(),
                "conversation",
                "delete",
                "conversation",
                conversationId,
                Map.of("action", "removed", "operatorUserId", operatorUserId)
        );

        List<Long> remainingMembers = conversationMapper.findMemberUserIds(conversationId);
        for (Long memberId : remainingMembers) {
            syncEventService.recordEvent(
                    memberId,
                    AuthContext.getDeviceId(),
                    "conversation",
                    "update",
                    "conversation",
                    conversationId,
                    Map.of("action", "remove_member", "targetUserId", memberUserId)
            );
        }
        pushConversationStateToMembers(conversationId, remainingMembers);
        return conversationMapper.findGroupMembers(conversationId, operatorUserId);
    }

    @Transactional
    public void leaveGroup(Long userId, Long conversationId) {
        Conversation conversation = getRequiredGroupConversation(conversationId);
        ConversationMember currentMember = getRequiredActiveMember(conversationId, userId);
        if ("owner".equals(currentMember.getMemberRole())) {
            throw new BusinessException("群主暂不支持直接退群，请先转让群主");
        }

        int updated = conversationMapper.updateConversationMemberStatus(conversationId, userId, 2);
        if (updated <= 0) {
            throw new BusinessException("退群失败");
        }

        syncEventService.recordEvent(
                userId,
                AuthContext.getDeviceId(),
                "conversation",
                "delete",
                "conversation",
                conversationId,
                Map.of("action", "leave")
        );

        List<Long> remainingMembers = conversationMapper.findMemberUserIds(conversationId);
        for (Long memberId : remainingMembers) {
            syncEventService.recordEvent(
                    memberId,
                    AuthContext.getDeviceId(),
                    "conversation",
                    "update",
                    "conversation",
                    conversationId,
                    Map.of("action", "leave_member", "targetUserId", userId)
            );
        }
        pushConversationStateToMembers(conversationId, remainingMembers);
    }

    @Transactional
    public void dissolveGroup(Long userId, Long conversationId) {
        Conversation conversation = getRequiredGroupConversation(conversationId);
        ConversationMember currentMember = getRequiredActiveMember(conversationId, userId);
        
        // 只有群主才能解散群聊
        if (!"owner".equals(currentMember.getMemberRole())) {
            throw new BusinessException("只有群主才能解散群聊");
        }

        // 删除所有群成员
        conversationMapper.deleteConversationMembers(conversationId);
        
        // 标记群聊为已删除
        conversationMapper.deleteConversation(conversationId);
        
        // 通知所有群成员
        List<Long> allMemberIds = conversationMapper.findMemberUserIdsIncludingDeleted(conversationId);
        for (Long memberId : allMemberIds) {
            syncEventService.recordEvent(
                    memberId,
                    AuthContext.getDeviceId(),
                    "conversation",
                    "delete",
                    "conversation",
                    conversationId,
                    Map.of("action", "dissolved")
            );
        }
    }

    @Transactional
    public GroupDetailView updateGroupAnnouncement(Long userId,
                                                   Long conversationId,
                                                   UpdateGroupAnnouncementRequest request) {
        getRequiredGroupConversation(conversationId);
        ConversationMember currentMember = getRequiredActiveMember(conversationId, userId);
        assertManagerRole(currentMember);
        if (request == null) {
            throw new BusinessException("群公告请求不能为空");
        }

        conversationMapper.updateConversationAnnouncement(conversationId, request.getAnnouncement());
        List<Long> memberIds = conversationMapper.findMemberUserIds(conversationId);
        for (Long memberId : memberIds) {
            syncEventService.recordEvent(
                    memberId,
                    AuthContext.getDeviceId(),
                    "conversation",
                    "update",
                    "conversation",
                    conversationId,
                    Map.of("action", "update_announcement")
            );
        }
        pushConversationStateToMembers(conversationId, memberIds);
        return conversationMapper.findGroupDetail(conversationId, userId);
    }

    @Transactional
    public GroupDetailView updateGroupName(Long userId,
                                           Long conversationId,
                                           UpdateGroupNameRequest request) {
        getRequiredGroupConversation(conversationId);
        ConversationMember currentMember = getRequiredActiveMember(conversationId, userId);
        assertManagerRole(currentMember);
        if (request == null || request.getName() == null || request.getName().trim().isEmpty()) {
            throw new BusinessException("群名称不能为空");
        }

        conversationMapper.updateConversationName(conversationId, request.getName().trim());
        List<Long> memberIds = conversationMapper.findMemberUserIds(conversationId);
        for (Long memberId : memberIds) {
            syncEventService.recordEvent(
                    memberId,
                    AuthContext.getDeviceId(),
                    "conversation",
                    "update",
                    "conversation",
                    conversationId,
                    Map.of("action", "update_name")
            );
        }
        pushConversationStateToMembers(conversationId, memberIds);
        return conversationMapper.findGroupDetail(conversationId, userId);
    }

    @Transactional
    public GroupDetailView updateGroupRemark(Long userId,
                                             Long conversationId,
                                             UpdateGroupRemarkRequest request) {
        getRequiredGroupConversation(conversationId);
        getRequiredActiveMember(conversationId, userId);
        if (request == null) {
            throw new BusinessException("备注请求不能为空");
        }

        String remark = request.getRemark();
        if (remark != null && remark.trim().isEmpty()) {
            remark = null;
        }

        int count = conversationMapper.countConversationUserSetting(conversationId, userId);
        if (count == 0) {
            ConversationUserSetting setting = new ConversationUserSetting();
            setting.setConversationId(conversationId);
            setting.setUserId(userId);
            setting.setIsTop(0);
            setting.setIsMuted(0);
            setting.setIsHidden(0);
            setting.setUnreadCount(0);
            setting.setDraftContent(null);
            setting.setLastReadMessageId(null);
            setting.setLastReadAt(null);
            setting.setClearMessageBefore(null);
            setting.setRemark(remark);
            conversationMapper.insertConversationUserSetting(setting);
        } else {
            conversationMapper.updateConversationUserRemark(conversationId, userId, remark);
        }

        syncEventService.recordEvent(
                userId,
                AuthContext.getDeviceId(),
                "conversation",
                "update",
                "conversation",
                conversationId,
                Map.of("action", "update_remark")
        );
        pushAndGetConversationState(conversationId, userId);
        return conversationMapper.findGroupDetail(conversationId, userId);
    }

    @Transactional
    public GroupDetailView updateGroupMyNickname(Long userId,
                                                 Long conversationId,
                                                 UpdateGroupMyNicknameRequest request) {
        getRequiredGroupConversation(conversationId);
        getRequiredActiveMember(conversationId, userId);
        if (request == null) {
            throw new BusinessException("群昵称请求不能为空");
        }

        String nickname = request.getNickname();
        if (nickname != null && nickname.trim().isEmpty()) {
            nickname = null;
        }

        conversationMapper.updateConversationMemberDisplayName(conversationId, userId, nickname);

        List<Long> memberIds = conversationMapper.findMemberUserIds(conversationId);
        for (Long memberId : memberIds) {
            syncEventService.recordEvent(
                    memberId,
                    AuthContext.getDeviceId(),
                    "conversation",
                    "update",
                    "conversation",
                    conversationId,
                    Map.of("action", "update_my_nickname")
            );
        }
        pushConversationStateToMembers(conversationId, memberIds);
        return conversationMapper.findGroupDetail(conversationId, userId);
    }

    private void assertConversationMember(Long conversationId, Long userId) {
        if (conversationMapper.countConversationMember(conversationId, userId) == 0) {
            throw new BusinessException("你不在该会话中");
        }
    }

    private Conversation getRequiredGroupConversation(Long conversationId) {
        Conversation conversation = conversationMapper.findConversationById(conversationId);
        if (conversation == null || !Integer.valueOf(1).equals(conversation.getStatus())) {
            throw new BusinessException("群聊不存在");
        }
        if (!"group".equals(conversation.getConversationType())) {
            throw new BusinessException("当前会话不是群聊");
        }
        return conversation;
    }

    private ConversationMember getRequiredActiveMember(Long conversationId, Long userId) {
        ConversationMember member = conversationMapper.findConversationMember(conversationId, userId);
        if (member == null || !Integer.valueOf(1).equals(member.getStatus())) {
            throw new BusinessException("你不在该群聊中");
        }
        return member;
    }

    private void assertManagerRole(ConversationMember member) {
        if (!"owner".equals(member.getMemberRole()) && !"admin".equals(member.getMemberRole())) {
            throw new BusinessException("你没有群管理权限");
        }
    }

    private void assertCanRemoveMember(ConversationMember operatorMember,
                                       ConversationMember targetMember,
                                       Long ownerUserId) {
        if ("owner".equals(targetMember.getMemberRole()) || ownerUserId.equals(targetMember.getUserId())) {
            throw new BusinessException("不能移除群主");
        }
        if ("owner".equals(operatorMember.getMemberRole())) {
            return;
        }
        if ("admin".equals(operatorMember.getMemberRole()) && "member".equals(targetMember.getMemberRole())) {
            return;
        }
        throw new BusinessException("你没有权限移除该成员");
    }

    private void assertCanMuteMember(ConversationMember operatorMember,
                                     ConversationMember targetMember,
                                     Long ownerUserId) {
        if ("owner".equals(targetMember.getMemberRole()) || ownerUserId.equals(targetMember.getUserId())) {
            throw new BusinessException("不能禁言群主");
        }
        if ("owner".equals(operatorMember.getMemberRole())) {
            return;
        }
        if ("admin".equals(operatorMember.getMemberRole()) && "member".equals(targetMember.getMemberRole())) {
            return;
        }
        throw new BusinessException("你没有权限禁言该成员");
    }

    private void assertCanSendMessage(Long conversationId, Long userId) {
        Conversation conversation = conversationMapper.findConversationById(conversationId);
        if (conversation == null || !"group".equals(conversation.getConversationType())) {
            return;
        }
        ConversationMember member = getRequiredActiveMember(conversationId, userId);
        if (Integer.valueOf(1).equals(conversation.getMuteAll())
                && !"owner".equals(member.getMemberRole())
                && !"admin".equals(member.getMemberRole())) {
            throw new BusinessException("当前群已开启全员禁言");
        }
        if (Integer.valueOf(1).equals(member.getIsMuted())) {
            if (member.getMuteUntil() == null || member.getMuteUntil().isAfter(LocalDateTime.now())) {
                throw new BusinessException("你当前已被群管理禁言");
            }
            conversationMapper.updateConversationMemberMute(conversationId, userId, 0, null);
        }
    }

    private void insertConversationMember(Long conversationId,
                                          Long userId,
                                          String role,
                                          String displayName,
                                          String joinSource,
                                          Long inviterUserId) {
        ConversationMember member = new ConversationMember();
        member.setConversationId(conversationId);
        member.setUserId(userId);
        member.setMemberRole(role);
        member.setDisplayName(displayName);
        member.setJoinSource(joinSource);
        member.setInviterUserId(inviterUserId);
        member.setIsMuted(0);
        member.setStatus(1);
        member.setJoinedAt(LocalDateTime.now());
        conversationMapper.insertConversationMember(member);
    }

    private void addOrReactivateGroupMember(Long conversationId,
                                            Long userId,
                                            Long operatorUserId,
                                            String joinSource) {
        WechatUser user = authMapper.findUserById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        ConversationMember existingMember = conversationMapper.findConversationMember(conversationId, userId);
        if (existingMember == null) {
            insertConversationMember(conversationId, userId, "member", user.getNickname(), joinSource, operatorUserId);
        } else {
            existingMember.setMemberRole("member");
            existingMember.setDisplayName(user.getNickname());
            existingMember.setJoinSource(joinSource);
            existingMember.setInviterUserId(operatorUserId);
            existingMember.setStatus(1);
            existingMember.setIsMuted(0);
            existingMember.setMuteUntil(null);
            existingMember.setJoinedAt(LocalDateTime.now());
            conversationMapper.reactivateConversationMember(existingMember);
            conversationMapper.updateConversationMemberMute(conversationId, userId, 0, null);
        }
        ensureConversationSetting(conversationId, userId);
        syncEventService.recordEvent(
                userId,
                AuthContext.getDeviceId(),
                "conversation",
                "create",
                "conversation",
                conversationId,
                Map.of("conversationType", "group", "action", "join")
        );
    }

    private void recordGroupEventForMembers(List<Long> memberIds,
                                            Long conversationId,
                                            String action,
                                            Map<String, Object> extraData) {
        for (Long memberId : memberIds) {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("action", action);
            payload.putAll(extraData);
            syncEventService.recordEvent(
                    memberId,
                    AuthContext.getDeviceId(),
                    "conversation",
                    "update",
                    "conversation",
                    conversationId,
                    payload
            );
        }
    }

    private void notifyGroupManagers(Conversation conversation,
                                     Long userId,
                                     String type,
                                     String title,
                                     String content) {
        for (GroupMemberView memberView : conversationMapper.findGroupMembers(conversation.getId(), userId)) {
            if ("owner".equals(memberView.getMemberRole()) || "admin".equals(memberView.getMemberRole())) {
                notificationService.createNotification(
                        memberView.getUserId(),
                        type,
                        title,
                        content,
                        "conversation",
                        conversation.getId(),
                        null
                );
            }
        }
    }

    private void insertConversationSetting(Long conversationId, Long userId) {
        ConversationUserSetting setting = new ConversationUserSetting();
        setting.setConversationId(conversationId);
        setting.setUserId(userId);
        setting.setIsTop(0);
        setting.setIsMuted(0);
        setting.setIsHidden(0);
        setting.setUnreadCount(0);
        conversationMapper.insertConversationUserSetting(setting);
    }

    private void ensureConversationSetting(Long conversationId, Long userId) {
        if (conversationMapper.countConversationUserSetting(conversationId, userId) > 0) {
            conversationMapper.reactivateConversationUserSetting(conversationId, userId);
            return;
        }
        insertConversationSetting(conversationId, userId);
    }

    private ConversationSummaryView pushAndGetConversationState(Long conversationId, Long userId) {
        ConversationSummaryView summaryView = conversationMapper.findConversationSummary(userId, conversationId);
        if (summaryView != null) {
            summaryView = formatConversationSummary(summaryView);
            webSocketPushService.pushConversationState(userId, summaryView);
        }
        return summaryView;
    }

    private void pushConversationStateToMembers(Long conversationId, List<Long> memberIds) {
        for (Long memberId : memberIds) {
            pushConversationStateToUser(conversationId, memberId);
        }
    }

    private void pushConversationStateToUser(Long conversationId, Long userId) {
        pushAndGetConversationState(conversationId, userId);
    }

    public void refreshConversationStateForFriends(Long userId) {
        List<Map<String, Object>> rows = conversationMapper.findSingleConversationIdsForUser(userId);
        if (rows == null || rows.isEmpty()) {
            return;
        }
        for (Map<String, Object> row : rows) {
            Long conversationId = ((Number) row.get("conversation_id")).longValue();
            Long friendUserId = ((Number) row.get("friend_user_id")).longValue();
            pushConversationStateToUser(conversationId, friendUserId);
        }
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

    private String buildContentJson(SendMessageRequest request) {
        List<Map<String, Object>> parts = new ArrayList<>();
        if (request.getFileId() != null) {
            parts.add(Map.of("fileId", request.getFileId()));
        }
        if (!CollectionUtils.isEmpty(request.getMentionUserIds())) {
            parts.add(Map.of("mentions", request.getMentionUserIds()));
        }
        if (parts.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(parts);
        } catch (JsonProcessingException e) {
            throw new BusinessException("消息扩展内容序列化失败");
        }
    }

    private int normalizePageSize(Integer limit) {
        if (limit == null) {
            return 20;
        }
        if (limit < 1) {
            return 20;
        }
        return Math.min(limit, 50);
    }

    private FileResource validateAndGetMessageFile(String messageType, Long fileId) {
        boolean fileRequired = switch (messageType) {
            case "image", "voice", "video", "file" -> true;
            default -> false;
        };
        if (fileRequired && fileId == null) {
            throw new BusinessException("当前消息类型必须上传文件");
        }
        if (!fileRequired && fileId != null) {
            throw new BusinessException("当前消息类型不支持附件");
        }
        if (fileId == null) {
            return null;
        }
        FileResource fileResource = fileMapper.findById(fileId);
        if (fileResource == null) {
            throw new BusinessException("附件不存在");
        }
        String mimeType = fileResource.getMimeType();
        switch (messageType) {
            case "image" -> {
                if (mimeType == null || !mimeType.startsWith("image/")) {
                    throw new BusinessException("图片消息必须使用图片文件");
                }
            }
            case "voice" -> {
                if (mimeType == null || !mimeType.startsWith("audio/")) {
                    throw new BusinessException("语音消息必须使用音频文件");
                }
            }
            case "video" -> {
                if (mimeType == null || !mimeType.startsWith("video/")) {
                    throw new BusinessException("视频消息必须使用视频文件");
                }
            }
            default -> {
            }
        }
        return fileResource;
    }

    private String resolveMessageContent(String messageType, String content, FileResource fileResource) {
        if (StringUtils.hasText(content)) {
            return content.trim();
        }
        if (fileResource == null) {
            return content;
        }
        return switch (messageType) {
            case "image" -> "[图片]";
            case "voice" -> "[语音]";
            case "video" -> "[视频]";
            case "file" -> "[文件] " + fileResource.getFileName();
            default -> content;
        };
    }

    private void pushReadReceiptUpdates(Long conversationId,
                                        Long readerUserId,
                                        List<Long> messageIds,
                                        LocalDateTime readAt) {
        if (CollectionUtils.isEmpty(messageIds)) {
            return;
        }
        WechatUser reader = authMapper.findUserById(readerUserId);
        if (reader == null) {
            return;
        }
        List<Long> memberIds = conversationMapper.findMemberUserIds(conversationId);
        for (Long messageId : messageIds) {
            MessageReadEventView eventView = buildMessageReadEvent(conversationId, messageId, reader, readAt);
            if (eventView == null) {
                continue;
            }
            for (Long memberId : memberIds) {
                webSocketPushService.pushMessageReadReceipt(memberId, eventView);
            }
        }
    }

    private MessageReadEventView buildMessageReadEvent(Long conversationId,
                                                       Long messageId,
                                                       WechatUser reader,
                                                       LocalDateTime readAt) {
        if (conversationMapper.findMessageSenderUserId(messageId) == null) {
            return null;
        }
        MessageReadEventView eventView = new MessageReadEventView();
        eventView.setConversationId(conversationId);
        eventView.setMessageId(messageId);
        eventView.setReadUserId(reader.getId());
        eventView.setReadUserNickname(reader.getNickname());
        eventView.setReadUserAvatarUrl(reader.getAvatarUrl());
        eventView.setReadAt(readAt);
        eventView.setReadCount(conversationMapper.countMessageReaders(conversationId, messageId));
        eventView.setUnreadCount(conversationMapper.countMessageUnreadUsers(conversationId, messageId));
        return eventView;
    }

    private ConversationMessageView formatConversationMessage(ConversationMessageView messageView) {
        if (messageView == null) {
            return null;
        }
        if (!StringUtils.hasText(messageView.getContent())) {
            messageView.setContent(formatMessagePreview(messageView.getMessageType(), messageView.getFileName()));
        }
        if (messageView.getFileId() != null) {
            FileResource fileResource = fileMapper.findById(messageView.getFileId());
            if (fileResource != null) {
                messageView.setFileAccess(fileService.buildFileAccessView(fileResource, null));
            }
        }
        if (messageView.getQuoteMessageId() != null) {
            messageView.setQuoteMessageContent(formatMessagePreview(messageView.getQuoteMessageType(), messageView.getQuoteMessageContent()));
        }
        return messageView;
    }

    private ConversationSummaryView formatConversationSummary(ConversationSummaryView summaryView) {
        if (summaryView == null) {
            return null;
        }
        String draftContent = summaryView.getDraftContent();
        if (StringUtils.hasText(draftContent)) {
            summaryView.setLastMessageContent("[草稿] " + draftContent.trim());
            return summaryView;
        }
        String content = summaryView.getLastMessageContent();
        if (!StringUtils.hasText(content)) {
            return summaryView;
        }
        String messageType = summaryView.getLastMessageType();
        if (!StringUtils.hasText(messageType)) {
            return summaryView;
        }
        switch (messageType) {
            case "image" -> summaryView.setLastMessageContent("[图片]");
            case "file" -> summaryView.setLastMessageContent(content.startsWith("[文件]") ? content : "[文件] " + content);
            case "voice" -> summaryView.setLastMessageContent("[语音]");
            case "video" -> summaryView.setLastMessageContent("[视频]");
            case "system" -> summaryView.setLastMessageContent("[系统消息] " + content);
            case "revoke" -> summaryView.setLastMessageContent("[消息已撤回]");
            default -> summaryView.setLastMessageContent(content);
        }
        return summaryView;
    }

    private String formatMessagePreview(String messageType, String content) {
        if (!StringUtils.hasText(messageType)) {
            return content;
        }
        return switch (messageType) {
            case "image" -> "[图片]";
            case "file" -> StringUtils.hasText(content) && content.startsWith("[文件]") ? content : "[文件]" + (StringUtils.hasText(content) ? " " + content : "");
            case "voice" -> "[语音]";
            case "video" -> "[视频]";
            case "system" -> StringUtils.hasText(content) ? "[系统消息] " + content : "[系统消息]";
            case "revoke" -> "[消息已撤回]";
            default -> content;
        };
    }

}
