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
import com.mingjin.school_wechat.model.request.CreateFileRequest;
import com.mingjin.school_wechat.model.request.CreateGroupRequest;
import com.mingjin.school_wechat.model.request.HandleFriendRequestRequest;
import com.mingjin.school_wechat.model.request.LoginRequest;
import com.mingjin.school_wechat.model.request.SendFriendRequestRequest;
import com.mingjin.school_wechat.model.request.SendMessageRequest;
import com.mingjin.school_wechat.model.view.ConversationMessageView;
import com.mingjin.school_wechat.model.view.ConversationSummaryView;
import com.mingjin.school_wechat.model.view.FriendRequestView;
import com.mingjin.school_wechat.model.view.FriendView;
import com.mingjin.school_wechat.model.view.LoginResponse;
import com.mingjin.school_wechat.model.view.UserNotificationView;
import com.mingjin.school_wechat.model.view.UserSyncEventView;
import com.mingjin.school_wechat.websocket.WebSocketPushService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
public class SyncEventService {

    private final SyncMapper syncMapper;
    private final ObjectMapper objectMapper;
    private final WebSocketPushService webSocketPushService;

    public SyncEventService(SyncMapper syncMapper,
                            ObjectMapper objectMapper,
                            WebSocketPushService webSocketPushService) {
        this.syncMapper = syncMapper;
        this.objectMapper = objectMapper;
        this.webSocketPushService = webSocketPushService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public UserSyncEventView recordEvent(Long userId,
                                         Long sourceDeviceId,
                                         String eventType,
                                         String actionType,
                                         String relatedType,
                                         Long relatedId,
                                         Map<String, Object> payload) {
        String eventPayload = toJson(payload);
        Integer lockResult = syncMapper.acquireUserSyncLock(userId);
        if (lockResult == null || lockResult != 1) {
            throw new BusinessException("同步事件锁获取失败，请稍后重试");
        }
        try {
            for (int attempt = 0; attempt < 5; attempt++) {
                Long latestSeq = syncMapper.findLatestSyncSeq(userId);
                UserSyncEvent userSyncEvent = new UserSyncEvent();
                userSyncEvent.setUserId(userId);
                userSyncEvent.setSourceDeviceId(sourceDeviceId);
                userSyncEvent.setSyncSeq((latestSeq == null ? 0L : latestSeq) + 1);
                userSyncEvent.setEventType(eventType);
                userSyncEvent.setActionType(actionType);
                userSyncEvent.setRelatedType(relatedType);
                userSyncEvent.setRelatedId(relatedId);
                userSyncEvent.setEventPayload(eventPayload);
                try {
                    syncMapper.insertSyncEvent(userSyncEvent);
                    UserSyncEventView syncEventView = syncMapper.findById(userId, userSyncEvent.getId());
                    if (syncEventView != null) {
                        webSocketPushService.pushSyncEvent(userId, syncEventView);
                    }
                    return syncEventView;
                } catch (DuplicateKeyException ex) {
                    if (attempt == 4) {
                        throw new BusinessException("同步事件写入失败，请稍后重试");
                    }
                }
            }
            throw new BusinessException("同步事件写入失败，请稍后重试");
        } finally {
            syncMapper.releaseUserSyncLock(userId);
        }
    }

    public List<UserSyncEventView> listEvents(Long userId, Long deviceId, Long fromSeq, Integer limit) {
        List<UserSyncEventView> events = syncMapper.findEventsAfter(userId, fromSeq, limit);
        if (!events.isEmpty()) {
            syncMapper.updateDeviceLastSyncSeq(deviceId, events.get(events.size() - 1).getSyncSeq());
        }
        return events;
    }

    private String toJson(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("同步事件序列化失败");
        }
    }
}
