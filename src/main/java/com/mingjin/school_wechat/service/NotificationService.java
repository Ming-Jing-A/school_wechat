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
public class NotificationService {

    private final NotificationMapper notificationMapper;
    private final WebSocketPushService webSocketPushService;

    public NotificationService(NotificationMapper notificationMapper,
                               WebSocketPushService webSocketPushService) {
        this.notificationMapper = notificationMapper;
        this.webSocketPushService = webSocketPushService;
    }

    public UserNotificationView createNotification(Long userId,
                                                   String type,
                                                   String title,
                                                   String content,
                                                   String relatedType,
                                                   Long relatedId,
                                                   String extraJson) {
        UserNotification notification = new UserNotification();
        notification.setUserId(userId);
        notification.setNotificationType(type);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setRelatedType(relatedType);
        notification.setRelatedId(relatedId);
        notification.setIsRead(0);
        notification.setReadAt(null);
        notification.setExtraJson(extraJson);
        notificationMapper.insertNotification(notification);
        UserNotificationView notificationView = notificationMapper.findById(notification.getId(), userId);
        if (notificationView != null) {
            webSocketPushService.pushNotification(userId, notificationView);
        }
        return notificationView;
    }

    public List<UserNotificationView> listByUserId(Long userId) {
        return notificationMapper.findByUserId(userId);
    }

    public void markAsRead(Long notificationId, Long userId) {
        notificationMapper.markAsRead(notificationId, userId);
    }

    public void deleteByRelated(Long userId, String relatedType, Long relatedId) {
        notificationMapper.deleteByRelated(userId, relatedType, relatedId);
    }

    public int clearUnread(Long userId) {
        return notificationMapper.clearUnread(userId);
    }

    public int clearAll(Long userId) {
        return notificationMapper.clearAll(userId);
    }

    public void deleteById(Long notificationId, Long userId) {
        notificationMapper.deleteById(notificationId, userId);
    }

    @Transactional
    public void cleanOldNotifications(int days) {
        LocalDateTime beforeDate = LocalDateTime.now().minusDays(days);
        notificationMapper.deleteOlderThan(beforeDate);
    }
}
