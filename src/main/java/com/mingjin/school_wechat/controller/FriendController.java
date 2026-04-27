package com.mingjin.school_wechat.controller;

import com.mingjin.school_wechat.common.api.ApiResponse;
import com.mingjin.school_wechat.common.auth.AuthContext;
import com.mingjin.school_wechat.model.entity.ChatMessage;
import com.mingjin.school_wechat.model.entity.Conversation;
import com.mingjin.school_wechat.model.entity.FileResource;
import com.mingjin.school_wechat.model.entity.FriendRequest;
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
public class FriendController {

    private final FriendService friendService;

    public FriendController(FriendService friendService) {
        this.friendService = friendService;
    }

    @GetMapping("/friends")
    public ApiResponse<List<FriendView>> listFriends() {
        return ApiResponse.success(friendService.listFriends(AuthContext.getUserId()));
    }

    @GetMapping("/users/search")
    public ApiResponse<List<UserSearchView>> searchUsers(@RequestParam String keyword) {
        return ApiResponse.success(friendService.searchUsers(AuthContext.getUserId(), keyword));
    }

    @GetMapping("/friend-requests")
    public ApiResponse<List<FriendRequestView>> listRequests() {
        return ApiResponse.success(friendService.listRequests(AuthContext.getUserId()));
    }

    @PostMapping("/friend-requests")
    public ApiResponse<FriendRequest> sendRequest(@RequestBody SendFriendRequestRequest request) {
        return ApiResponse.success("好友申请已发送", friendService.sendRequest(AuthContext.getUserId(), request));
    }

    @PostMapping("/friend-requests/{requestId}/handle")
    public ApiResponse<Void> handleRequest(@PathVariable Long requestId, @RequestBody HandleFriendRequestRequest request) {
        friendService.handleRequest(AuthContext.getUserId(), requestId, request);
        return ApiResponse.success("处理成功", null);
    }

    @PostMapping("/friends/{friendUserId}/delete")
    public ApiResponse<Void> deleteFriend(@PathVariable Long friendUserId) {
        friendService.deleteFriend(AuthContext.getUserId(), friendUserId);
        return ApiResponse.success("删除好友成功", null);
    }

    @PostMapping("/friends/{friendUserId}/remark")
    public ApiResponse<FriendView> updateRemark(@PathVariable Long friendUserId,
                                                @RequestBody UpdateFriendRemarkRequest request) {
        return ApiResponse.success("好友备注更新成功", friendService.updateRemark(AuthContext.getUserId(), friendUserId, request));
    }

    @PostMapping("/friends/{friendUserId}/blacklist")
    public ApiResponse<Void> blockUser(@PathVariable Long friendUserId,
                                       @RequestBody(required = false) BlacklistRequest request) {
        friendService.blockUser(AuthContext.getUserId(), friendUserId, request);
        return ApiResponse.success("拉黑成功", null);
    }

    @PostMapping("/friends/{friendUserId}/blacklist/cancel")
    public ApiResponse<Void> unblockUser(@PathVariable Long friendUserId) {
        friendService.unblockUser(AuthContext.getUserId(), friendUserId);
        return ApiResponse.success("取消拉黑成功", null);
    }
}
