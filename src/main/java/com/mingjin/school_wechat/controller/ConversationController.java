package com.mingjin.school_wechat.controller;

import com.mingjin.school_wechat.common.api.ApiResponse;
import com.mingjin.school_wechat.common.auth.AuthContext;
import com.mingjin.school_wechat.model.entity.ChatMessage;
import com.mingjin.school_wechat.model.entity.Conversation;
import com.mingjin.school_wechat.model.entity.FileResource;
import com.mingjin.school_wechat.model.entity.FriendRequest;
import com.mingjin.school_wechat.model.entity.WechatUser;
import com.mingjin.school_wechat.model.request.ConversationDraftRequest;
import com.mingjin.school_wechat.model.request.CreateFileRequest;
import com.mingjin.school_wechat.model.request.CreateGroupRequest;
import com.mingjin.school_wechat.model.request.HandleFriendRequestRequest;
import com.mingjin.school_wechat.model.request.HandleGroupJoinRequest;
import com.mingjin.school_wechat.model.request.InviteGroupMembersRequest;
import com.mingjin.school_wechat.model.request.JoinGroupRequest;
import com.mingjin.school_wechat.model.request.LoginRequest;
import com.mingjin.school_wechat.model.request.MuteGroupMemberRequest;
import com.mingjin.school_wechat.model.request.SendFriendRequestRequest;
import com.mingjin.school_wechat.model.request.SendMessageRequest;
import com.mingjin.school_wechat.model.request.TransferGroupOwnerRequest;
import com.mingjin.school_wechat.model.request.UpdateGroupAnnouncementRequest;
import com.mingjin.school_wechat.model.request.UpdateGroupMemberRoleRequest;
import com.mingjin.school_wechat.model.request.UpdateGroupMuteAllRequest;
import com.mingjin.school_wechat.model.request.UpdateGroupJoinRuleRequest;
import com.mingjin.school_wechat.model.request.UpdateGroupNameRequest;
import com.mingjin.school_wechat.model.request.UpdateGroupRemarkRequest;
import com.mingjin.school_wechat.model.request.UpdateGroupMyNicknameRequest;
import com.mingjin.school_wechat.model.view.ConversationMessageView;
import com.mingjin.school_wechat.model.view.ConversationMessagePageView;
import com.mingjin.school_wechat.model.view.ConversationSummaryView;
import com.mingjin.school_wechat.model.view.FriendRequestView;
import com.mingjin.school_wechat.model.view.FriendView;
import com.mingjin.school_wechat.model.view.GroupDetailView;
import com.mingjin.school_wechat.model.view.GroupJoinRequestView;
import com.mingjin.school_wechat.model.view.GroupMemberView;
import com.mingjin.school_wechat.model.view.LoginResponse;
import com.mingjin.school_wechat.model.view.MessageReadReceiptView;
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
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @GetMapping("/conversations")
    public ApiResponse<List<ConversationSummaryView>> listConversations() {
        return ApiResponse.success(conversationService.listConversations(AuthContext.getUserId()));
    }

    @PostMapping("/conversations/{conversationId}/top")
    public ApiResponse<ConversationSummaryView> topConversation(@PathVariable Long conversationId) {
        return ApiResponse.success("会话已置顶", conversationService.topConversation(AuthContext.getUserId(), conversationId, true));
    }

    @PostMapping("/conversations/{conversationId}/top/cancel")
    public ApiResponse<ConversationSummaryView> cancelTopConversation(@PathVariable Long conversationId) {
        return ApiResponse.success("会话已取消置顶", conversationService.topConversation(AuthContext.getUserId(), conversationId, false));
    }

    @PostMapping("/conversations/{conversationId}/mute")
    public ApiResponse<ConversationSummaryView> muteConversation(@PathVariable Long conversationId) {
        return ApiResponse.success("会话已开启免打扰", conversationService.muteConversation(AuthContext.getUserId(), conversationId, true));
    }

    @PostMapping("/conversations/{conversationId}/mute/cancel")
    public ApiResponse<ConversationSummaryView> cancelMuteConversation(@PathVariable Long conversationId) {
        return ApiResponse.success("会话已关闭免打扰", conversationService.muteConversation(AuthContext.getUserId(), conversationId, false));
    }

    @PostMapping("/conversations/{conversationId}/hide")
    public ApiResponse<ConversationSummaryView> hideConversation(@PathVariable Long conversationId) {
        return ApiResponse.success("会话已隐藏", conversationService.hideConversation(AuthContext.getUserId(), conversationId, true));
    }

    @PostMapping("/conversations/{conversationId}/hide/cancel")
    public ApiResponse<ConversationSummaryView> cancelHideConversation(@PathVariable Long conversationId) {
        return ApiResponse.success("会话已取消隐藏", conversationService.hideConversation(AuthContext.getUserId(), conversationId, false));
    }

    @PostMapping("/conversations/{conversationId}/draft")
    public ApiResponse<ConversationSummaryView> saveDraft(@PathVariable Long conversationId,
                                                          @RequestBody ConversationDraftRequest request) {
        return ApiResponse.success("草稿已保存", conversationService.saveDraft(AuthContext.getUserId(), conversationId, request));
    }

    @PostMapping("/conversations/{conversationId}/unread/clear")
    public ApiResponse<ConversationSummaryView> clearUnread(@PathVariable Long conversationId) {
        return ApiResponse.success("未读消息已清空", conversationService.clearUnread(AuthContext.getUserId(), conversationId));
    }

    @PostMapping("/conversations/{conversationId}/messages/clear")
    public ApiResponse<ConversationSummaryView> clearMessagesForMe(@PathVariable Long conversationId) {
        return ApiResponse.success("聊天记录已清空", conversationService.clearMessagesForMe(AuthContext.getUserId(), conversationId));
    }

    @PostMapping("/conversations/{conversationId}/delete")
    public ApiResponse<ConversationSummaryView> deleteConversationForMe(@PathVariable Long conversationId) {
        return ApiResponse.success("会话已删除", conversationService.deleteConversationForMe(AuthContext.getUserId(), conversationId));
    }

    @GetMapping("/conversations/{conversationId}/detail")
    public ApiResponse<GroupDetailView> getGroupDetail(@PathVariable Long conversationId) {
        return ApiResponse.success(conversationService.getGroupDetail(AuthContext.getUserId(), conversationId));
    }

    @GetMapping("/conversations/search")
    public ApiResponse<ConversationSummaryView> searchGroupByNo(@RequestParam Long conversationId) {
        return ApiResponse.success(conversationService.searchGroupByConversationId(AuthContext.getUserId(), conversationId));
    }

    @PostMapping("/conversations/group")
    public ApiResponse<Conversation> createGroup(@RequestBody CreateGroupRequest request) {
        return ApiResponse.success("群聊创建成功", conversationService.createGroup(AuthContext.getUserId(), request));
    }

    @GetMapping("/conversations/{conversationId}/members")
    public ApiResponse<List<GroupMemberView>> listGroupMembers(@PathVariable Long conversationId) {
        return ApiResponse.success(conversationService.listGroupMembers(AuthContext.getUserId(), conversationId));
    }

    @PostMapping("/conversations/{conversationId}/join")
    public ApiResponse<String> applyJoinGroup(@PathVariable Long conversationId,
                                              @RequestBody(required = false) JoinGroupRequest request) {
        return ApiResponse.success(conversationService.applyJoinGroup(AuthContext.getUserId(), conversationId, request));
    }

    @GetMapping("/conversations/{conversationId}/join-requests")
    public ApiResponse<List<GroupJoinRequestView>> listGroupJoinRequests(@PathVariable Long conversationId) {
        return ApiResponse.success(conversationService.listGroupJoinRequests(AuthContext.getUserId(), conversationId));
    }

    @PostMapping("/conversations/{conversationId}/join-requests/{requestId}/handle")
    public ApiResponse<List<GroupJoinRequestView>> handleGroupJoinRequest(@PathVariable Long conversationId,
                                                                          @PathVariable Long requestId,
                                                                          @RequestBody HandleGroupJoinRequest request) {
        return ApiResponse.success("入群申请处理成功", conversationService.handleGroupJoinRequest(AuthContext.getUserId(), conversationId, requestId, request));
    }

    @PostMapping("/conversations/{conversationId}/members/invite")
    public ApiResponse<List<GroupMemberView>> inviteGroupMembers(@PathVariable Long conversationId,
                                                                 @RequestBody InviteGroupMembersRequest request) {
        return ApiResponse.success("邀请成员成功", conversationService.inviteGroupMembers(AuthContext.getUserId(), conversationId, request));
    }

    @PostMapping("/conversations/{conversationId}/members/{memberUserId}/remove")
    public ApiResponse<List<GroupMemberView>> removeGroupMember(@PathVariable Long conversationId,
                                                                @PathVariable Long memberUserId) {
        return ApiResponse.success("移除成员成功", conversationService.removeGroupMember(AuthContext.getUserId(), conversationId, memberUserId));
    }

    @PostMapping("/conversations/{conversationId}/leave")
    public ApiResponse<Void> leaveGroup(@PathVariable Long conversationId) {
        conversationService.leaveGroup(AuthContext.getUserId(), conversationId);
        return ApiResponse.success("退群成功", null);
    }

    @PostMapping("/conversations/{conversationId}/dissolve")
    public ApiResponse<Void> dissolveGroup(@PathVariable Long conversationId) {
        conversationService.dissolveGroup(AuthContext.getUserId(), conversationId);
        return ApiResponse.success("群聊已解散", null);
    }

    @PostMapping("/conversations/{conversationId}/announcement")
    public ApiResponse<GroupDetailView> updateGroupAnnouncement(@PathVariable Long conversationId,
                                                                @RequestBody UpdateGroupAnnouncementRequest request) {
        return ApiResponse.success("群公告更新成功", conversationService.updateGroupAnnouncement(AuthContext.getUserId(), conversationId, request));
    }

    @PostMapping("/conversations/{conversationId}/name")
    public ApiResponse<GroupDetailView> updateGroupName(@PathVariable Long conversationId,
                                                        @RequestBody UpdateGroupNameRequest request) {
        return ApiResponse.success("群名称更新成功", conversationService.updateGroupName(AuthContext.getUserId(), conversationId, request));
    }

    @PostMapping("/conversations/{conversationId}/remark")
    public ApiResponse<GroupDetailView> updateGroupRemark(@PathVariable Long conversationId,
                                                          @RequestBody UpdateGroupRemarkRequest request) {
        return ApiResponse.success("群备注更新成功", conversationService.updateGroupRemark(AuthContext.getUserId(), conversationId, request));
    }

    @PostMapping("/conversations/{conversationId}/my-nickname")
    public ApiResponse<GroupDetailView> updateGroupMyNickname(@PathVariable Long conversationId,
                                                              @RequestBody UpdateGroupMyNicknameRequest request) {
        return ApiResponse.success("群昵称更新成功", conversationService.updateGroupMyNickname(AuthContext.getUserId(), conversationId, request));
    }

    @PostMapping("/conversations/{conversationId}/mute-all")
    public ApiResponse<GroupDetailView> updateGroupMuteAll(@PathVariable Long conversationId,
                                                           @RequestBody UpdateGroupMuteAllRequest request) {
        return ApiResponse.success("群禁言设置已更新", conversationService.updateGroupMuteAll(AuthContext.getUserId(), conversationId, request));
    }

    @PostMapping("/conversations/{conversationId}/join-rule")
    public ApiResponse<GroupDetailView> updateGroupJoinRule(@PathVariable Long conversationId,
                                                            @RequestBody UpdateGroupJoinRuleRequest request) {
        return ApiResponse.success("入群方式已更新", conversationService.updateGroupJoinRule(AuthContext.getUserId(), conversationId, request));
    }

    @PostMapping("/conversations/{conversationId}/members/{memberUserId}/role")
    public ApiResponse<List<GroupMemberView>> updateGroupMemberRole(@PathVariable Long conversationId,
                                                                    @PathVariable Long memberUserId,
                                                                    @RequestBody UpdateGroupMemberRoleRequest request) {
        return ApiResponse.success("群成员角色更新成功", conversationService.updateGroupMemberRole(AuthContext.getUserId(), conversationId, memberUserId, request));
    }

    @PostMapping("/conversations/{conversationId}/members/{memberUserId}/mute")
    public ApiResponse<List<GroupMemberView>> muteGroupMember(@PathVariable Long conversationId,
                                                              @PathVariable Long memberUserId,
                                                              @RequestBody MuteGroupMemberRequest request) {
        return ApiResponse.success("成员禁言状态已更新", conversationService.muteGroupMember(AuthContext.getUserId(), conversationId, memberUserId, request));
    }

    @PostMapping("/conversations/{conversationId}/owner/transfer")
    public ApiResponse<GroupDetailView> transferGroupOwner(@PathVariable Long conversationId,
                                                           @RequestBody TransferGroupOwnerRequest request) {
        return ApiResponse.success("群主转让成功", conversationService.transferGroupOwner(AuthContext.getUserId(), conversationId, request));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public ApiResponse<ConversationMessagePageView> listMessages(@PathVariable Long conversationId,
                                                                 @RequestParam(required = false) Long beforeMessageId,
                                                                 @RequestParam(required = false) Integer limit) {
        return ApiResponse.success(conversationService.listMessagesPage(AuthContext.getUserId(), conversationId, beforeMessageId, limit));
    }

    @GetMapping("/conversations/{conversationId}/messages/search")
    public ApiResponse<ConversationMessagePageView> searchMessages(@PathVariable Long conversationId,
                                                                   @RequestParam(required = false) String keyword,
                                                                   @RequestParam(required = false) String messageType,
                                                                   @RequestParam(required = false) Long beforeMessageId,
                                                                   @RequestParam(required = false) Integer limit) {
        return ApiResponse.success(conversationService.searchMessages(
                AuthContext.getUserId(),
                conversationId,
                keyword,
                messageType,
                beforeMessageId,
                limit
        ));
    }

    @GetMapping("/conversations/{conversationId}/messages/{messageId}/read-receipt")
    public ApiResponse<MessageReadReceiptView> getMessageReadReceipt(@PathVariable Long conversationId,
                                                                     @PathVariable Long messageId) {
        return ApiResponse.success(conversationService.getMessageReadReceipt(AuthContext.getUserId(), conversationId, messageId));
    }

    @PostMapping("/conversations/{conversationId}/messages/{messageId}/delete-for-me")
    public ApiResponse<Void> deleteMessageForMe(@PathVariable Long conversationId,
                                                @PathVariable Long messageId) {
        conversationService.deleteMessageForMe(AuthContext.getUserId(), conversationId, messageId);
        return ApiResponse.success("消息已删除", null);
    }

    @PostMapping("/conversations/{conversationId}/messages")
    public ApiResponse<ChatMessage> sendMessage(@PathVariable Long conversationId, @RequestBody SendMessageRequest request) {
        return ApiResponse.success("消息发送成功", conversationService.sendMessage(AuthContext.getUserId(), conversationId, request));
    }

    @PostMapping("/conversations/{conversationId}/messages/{messageId}/recall")
    public ApiResponse<ConversationMessageView> recallMessage(@PathVariable Long conversationId, @PathVariable Long messageId) {
        return ApiResponse.success("消息撤回成功", conversationService.recallMessage(AuthContext.getUserId(), conversationId, messageId));
    }
}
