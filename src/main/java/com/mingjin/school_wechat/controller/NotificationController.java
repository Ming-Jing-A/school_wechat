package com.mingjin.school_wechat.controller;

import com.mingjin.school_wechat.common.api.ApiResponse;
import com.mingjin.school_wechat.common.auth.AuthContext;
import com.mingjin.school_wechat.model.entity.ChatMessage;
import com.mingjin.school_wechat.model.entity.Conversation;
import com.mingjin.school_wechat.model.entity.FileResource;
import com.mingjin.school_wechat.model.entity.FriendRequest;
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
import com.mingjin.school_wechat.service.AuthService;
import com.mingjin.school_wechat.service.ConversationService;
import com.mingjin.school_wechat.service.FileService;
import com.mingjin.school_wechat.service.FriendService;
import com.mingjin.school_wechat.service.NotificationService;
import com.mingjin.school_wechat.service.SyncEventService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/notifications")
    public ApiResponse<List<UserNotificationView>> listNotifications() {
        return ApiResponse.success(notificationService.listByUserId(AuthContext.getUserId()));
    }


    @PostMapping("/notifications/{notificationId}/read")
    public ApiResponse<Void> markAsRead(@PathVariable Long notificationId) {
        notificationService.markAsRead(notificationId, AuthContext.getUserId());
        return ApiResponse.success("已标记为已读", null);
    }

    @PostMapping("/notifications/clear-unread")
    public ApiResponse<Void> clearUnread() {
        notificationService.clearUnread(AuthContext.getUserId());
        return ApiResponse.success("已清空未读通知", null);
    }

    @PostMapping("/notifications/clear-all")
    public ApiResponse<Void> clearAll() {
        notificationService.clearAll(AuthContext.getUserId());
        return ApiResponse.success("已清空所有通知", null);
    }

    @DeleteMapping("/notifications/{notificationId}")
    public ApiResponse<Void> deleteNotification(@PathVariable Long notificationId) {
        notificationService.deleteById(notificationId, AuthContext.getUserId());
        return ApiResponse.success("已删除通知", null);
    }
}
