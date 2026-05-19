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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/api")
public class SyncController {

    private final SyncEventService syncEventService;

    public SyncController(SyncEventService syncEventService) {
        this.syncEventService = syncEventService;
    }

    @GetMapping("/sync/events")
    public ApiResponse<List<UserSyncEventView>> listEvents(@RequestParam(defaultValue = "0") Long fromSeq,
                                                           @RequestParam(defaultValue = "50") Integer limit) {
        return ApiResponse.success(syncEventService.listEvents(AuthContext.getUserId(), AuthContext.getDeviceId(), fromSeq, limit));
    }
}
