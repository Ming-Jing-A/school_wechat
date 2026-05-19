package com.mingjin.school_wechat.mapper;

import com.mingjin.school_wechat.model.entity.AuthSession;
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
import com.mingjin.school_wechat.model.view.ConversationMessageView;
import com.mingjin.school_wechat.model.view.MessageReadReceiptView;
import com.mingjin.school_wechat.model.view.ConversationSummaryView;
import com.mingjin.school_wechat.model.view.FriendRequestView;
import com.mingjin.school_wechat.model.view.FriendView;
import com.mingjin.school_wechat.model.view.GroupDetailView;
import com.mingjin.school_wechat.model.view.GroupJoinRequestView;
import com.mingjin.school_wechat.model.view.GroupMemberView;
import com.mingjin.school_wechat.model.view.UserNotificationView;
import com.mingjin.school_wechat.model.view.UserSyncEventView;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface ConversationMapper {

    @Select("""
            SELECT c.id AS conversation_id,
                   c.conversation_type,
                   CASE
                       WHEN c.conversation_type = 'single' THEN (
                           SELECT CASE
                                      WHEN f.remark_name IS NOT NULL AND f.remark_name != u.nickname AND f.remark_name != u.username THEN f.remark_name
                                      ELSE COALESCE(u.nickname, u.username)
                                  END
                           FROM conversation_member cm2
                           JOIN wechat_user u ON u.id = cm2.user_id
                           LEFT JOIN friendship f ON f.user_id = #{userId} AND f.friend_user_id = u.id AND f.status = 1
                           WHERE cm2.conversation_id = c.id AND cm2.user_id <> #{userId}
                           LIMIT 1
                       )
                       ELSE c.name
                   END AS conversation_name,
                   CASE
                       WHEN c.conversation_type = 'single' THEN (
                           SELECT u.avatar_url
                           FROM conversation_member cm2
                           JOIN wechat_user u ON u.id = cm2.user_id
                           WHERE cm2.conversation_id = c.id AND cm2.user_id <> #{userId}
                           LIMIT 1
                       )
                       ELSE c.avatar_url
                   END AS avatar_url,
                   c.announcement,
                   c.last_message_type,
                   c.last_message_content,
                   c.last_sender_id,
                   c.last_message_at,
                   cus.unread_count,
                   cus.is_top,
                   cus.is_muted,
                   cus.is_hidden,
                   cus.draft_content,
                   cus.remark
            FROM conversation c
            JOIN conversation_member cm ON cm.conversation_id = c.id AND cm.user_id = #{userId} AND cm.status = 1
            LEFT JOIN conversation_user_setting cus ON cus.conversation_id = c.id AND cus.user_id = #{userId}
            WHERE c.status = 1
              AND COALESCE(cus.is_hidden, 0) = 0
              AND NOT (
                  c.conversation_type = 'single'
                  AND NOT EXISTS (
                      SELECT 1 FROM friendship f
                      JOIN conversation_member cm2 ON cm2.conversation_id = c.id AND cm2.user_id != #{userId}
                      WHERE f.user_id = #{userId}
                        AND f.friend_user_id = cm2.user_id
                        AND f.status = 1
                  )
              )
            ORDER BY cus.is_top DESC, c.last_message_at DESC, c.id DESC
            """)
    List<ConversationSummaryView> findConversationList(@Param("userId") Long userId);

    @Select("""
            SELECT c.id AS conversation_id,
                   c.conversation_type,
                   CASE
                       WHEN c.conversation_type = 'single' THEN (
                           SELECT CASE
                                      WHEN f.remark_name IS NOT NULL AND f.remark_name != u.nickname AND f.remark_name != u.username THEN f.remark_name
                                      ELSE COALESCE(u.nickname, u.username)
                                  END
                           FROM conversation_member cm2
                           JOIN wechat_user u ON u.id = cm2.user_id
                           LEFT JOIN friendship f ON f.user_id = #{userId} AND f.friend_user_id = u.id AND f.status = 1
                           WHERE cm2.conversation_id = c.id AND cm2.user_id <> #{userId}
                           LIMIT 1
                       )
                       ELSE c.name
                   END AS conversation_name,
                   CASE
                       WHEN c.conversation_type = 'single' THEN (
                           SELECT u.avatar_url
                           FROM conversation_member cm2
                           JOIN wechat_user u ON u.id = cm2.user_id
                           WHERE cm2.conversation_id = c.id AND cm2.user_id <> #{userId}
                           LIMIT 1
                       )
                       ELSE c.avatar_url
                   END AS avatar_url,
                   c.announcement,
                   c.last_message_type,
                   c.last_message_content,
                   c.last_sender_id,
                   c.last_message_at,
                   cus.unread_count,
                   cus.is_top,
                   cus.is_muted,
                   cus.is_hidden,
                   cus.draft_content,
                   cus.remark
            FROM conversation c
            JOIN conversation_member cm ON cm.conversation_id = c.id AND cm.user_id = #{userId} AND cm.status = 1
            LEFT JOIN conversation_user_setting cus ON cus.conversation_id = c.id AND cus.user_id = #{userId}
            WHERE c.status = 1
              AND c.id = #{conversationId}
            LIMIT 1
            """)
    ConversationSummaryView findConversationSummary(@Param("userId") Long userId,
                                                    @Param("conversationId") Long conversationId);

    @Select("""
            SELECT COUNT(1)
            FROM conversation_member
            WHERE conversation_id = #{conversationId}
              AND user_id = #{userId}
              AND status = 1
            """)
    int countConversationMember(@Param("conversationId") Long conversationId, @Param("userId") Long userId);

    @Select("""
            SELECT *
            FROM conversation
            WHERE id = #{conversationId}
            LIMIT 1
            """)
    Conversation findConversationById(@Param("conversationId") Long conversationId);

    @Select("""
            SELECT *
            FROM conversation_member
            WHERE conversation_id = #{conversationId}
              AND user_id = #{userId}
            LIMIT 1
            """)
    ConversationMember findConversationMember(@Param("conversationId") Long conversationId,
                                              @Param("userId") Long userId);

    @Select("""
            SELECT COUNT(1)
            FROM conversation_member
            WHERE conversation_id = #{conversationId}
              AND status = 1
            """)
    int countActiveMembers(@Param("conversationId") Long conversationId);

    @Select("""
            SELECT c.id AS conversation_id,
                   c.conversation_type,
                   c.name,
                   c.avatar_url,
                   c.owner_user_id,
                   c.description_text,
                   c.announcement,
                   c.join_rule,
                   c.max_member_count,
                   c.mute_all,
                   c.status,
                   (
                       SELECT COUNT(1)
                       FROM conversation_member cm
                       WHERE cm.conversation_id = c.id
                         AND cm.status = 1
                   ) AS member_count,
                   (
                       SELECT cm.member_role
                       FROM conversation_member cm
                       WHERE cm.conversation_id = c.id
                         AND cm.user_id = #{userId}
                       LIMIT 1
                   ) AS current_user_role,
                   (
                       SELECT cus.remark
                       FROM conversation_user_setting cus
                       WHERE cus.conversation_id = c.id
                         AND cus.user_id = #{userId}
                       LIMIT 1
                   ) AS remark,
                   (
                       SELECT cm.display_name
                       FROM conversation_member cm
                       WHERE cm.conversation_id = c.id
                         AND cm.user_id = #{userId}
                       LIMIT 1
                   ) AS my_nickname
            FROM conversation c
            WHERE c.id = #{conversationId}
            LIMIT 1
            """)
    GroupDetailView findGroupDetail(@Param("conversationId") Long conversationId,
                                    @Param("userId") Long userId);

    @Select("""
            SELECT cm.user_id,
                   u.nickname,
                   u.username,
                   u.avatar_url,
                   cm.member_role,
                   CASE
                       WHEN cm.display_name IS NOT NULL AND cm.display_name != u.nickname AND cm.display_name != u.username THEN cm.display_name
                       ELSE NULL
                   END AS display_name,
                   CASE
                       WHEN f.remark_name IS NOT NULL AND f.remark_name != u.nickname AND f.remark_name != u.username THEN f.remark_name
                       ELSE NULL
                   END AS remark_name,
                   cm.join_source,
                   cm.inviter_user_id,
                   cm.is_muted,
                   cm.mute_until,
                   cm.joined_at,
                   cm.status
            FROM conversation_member cm
            JOIN wechat_user u ON u.id = cm.user_id
            LEFT JOIN friendship f ON f.user_id = #{userId} AND f.friend_user_id = cm.user_id AND f.status = 1
            WHERE cm.conversation_id = #{conversationId}
              AND cm.status = 1
            ORDER BY CASE cm.member_role
                         WHEN 'owner' THEN 1
                         WHEN 'admin' THEN 2
                         ELSE 3
                     END,
                     cm.joined_at ASC
            """)
    List<GroupMemberView> findGroupMembers(@Param("conversationId") Long conversationId,
                                            @Param("userId") Long userId);

    @Insert("""
            INSERT INTO conversation (
                conversation_type, name, avatar_url, owner_user_id, description_text,
                announcement, join_rule, max_member_count, mute_all,
                last_message_id, last_message_type, last_message_content,
                last_sender_id, last_message_at, status
            ) VALUES (
                #{conversationType}, #{name}, #{avatarUrl}, #{ownerUserId}, #{descriptionText},
                #{announcement}, #{joinRule}, #{maxMemberCount}, #{muteAll},
                #{lastMessageId}, #{lastMessageType}, #{lastMessageContent},
                #{lastSenderId}, #{lastMessageAt}, #{status}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertConversation(Conversation conversation);

    @Insert("""
            INSERT INTO conversation_member (
                conversation_id, user_id, member_role, display_name, join_source,
                inviter_user_id, is_muted, mute_until, status, joined_at
            ) VALUES (
                #{conversationId}, #{userId}, #{memberRole}, #{displayName}, #{joinSource},
                #{inviterUserId}, #{isMuted}, #{muteUntil}, #{status}, #{joinedAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertConversationMember(ConversationMember member);

    @Insert("""
            INSERT INTO conversation_user_setting (
                conversation_id, user_id, is_top, is_muted, is_hidden,
                unread_count, draft_content, last_read_message_id, last_read_at, clear_message_before
            ) VALUES (
                #{conversationId}, #{userId}, #{isTop}, #{isMuted}, #{isHidden},
                #{unreadCount}, #{draftContent}, #{lastReadMessageId}, #{lastReadAt}, #{clearMessageBefore}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertConversationUserSetting(ConversationUserSetting setting);

    @Select("""
            SELECT COUNT(1)
            FROM conversation_user_setting
            WHERE conversation_id = #{conversationId}
              AND user_id = #{userId}
            """)
    int countConversationUserSetting(@Param("conversationId") Long conversationId,
                                     @Param("userId") Long userId);

    @Update("""
            UPDATE conversation_user_setting
            SET is_hidden = 0,
                updated_at = NOW()
            WHERE conversation_id = #{conversationId}
              AND user_id = #{userId}
            """)
    int reactivateConversationUserSetting(@Param("conversationId") Long conversationId,
                                          @Param("userId") Long userId);

    @Select("""
            SELECT *
            FROM conversation_user_setting
            WHERE conversation_id = #{conversationId}
              AND user_id = #{userId}
            LIMIT 1
            """)
    ConversationUserSetting findConversationUserSetting(@Param("conversationId") Long conversationId,
                                                        @Param("userId") Long userId);

    @Update("""
            UPDATE conversation_user_setting
            SET is_top = #{isTop},
                updated_at = NOW()
            WHERE conversation_id = #{conversationId}
              AND user_id = #{userId}
            """)
    int updateConversationTop(@Param("conversationId") Long conversationId,
                              @Param("userId") Long userId,
                              @Param("isTop") Integer isTop);

    @Update("""
            UPDATE conversation_user_setting
            SET is_muted = #{isMuted},
                updated_at = NOW()
            WHERE conversation_id = #{conversationId}
              AND user_id = #{userId}
            """)
    int updateConversationMuted(@Param("conversationId") Long conversationId,
                                @Param("userId") Long userId,
                                @Param("isMuted") Integer isMuted);

    @Update("""
            UPDATE conversation_user_setting
            SET draft_content = #{draftContent},
                updated_at = NOW()
            WHERE conversation_id = #{conversationId}
              AND user_id = #{userId}
            """)
    int updateConversationDraft(@Param("conversationId") Long conversationId,
                                @Param("userId") Long userId,
                                @Param("draftContent") String draftContent);

    @Update("""
            UPDATE conversation_user_setting
            SET is_hidden = #{isHidden},
                updated_at = NOW()
            WHERE conversation_id = #{conversationId}
              AND user_id = #{userId}
            """)
    int updateConversationHidden(@Param("conversationId") Long conversationId,
                                 @Param("userId") Long userId,
                                 @Param("isHidden") Integer isHidden);

    @Update("""
            UPDATE conversation_user_setting
            SET clear_message_before = #{clearMessageBefore},
                unread_count = 0,
                last_read_message_id = #{lastReadMessageId},
                last_read_at = NOW(),
                updated_at = NOW()
            WHERE conversation_id = #{conversationId}
              AND user_id = #{userId}
            """)
    int clearConversationMessagesForMe(@Param("conversationId") Long conversationId,
                                       @Param("userId") Long userId,
                                       @Param("clearMessageBefore") LocalDateTime clearMessageBefore,
                                       @Param("lastReadMessageId") Long lastReadMessageId);

    @Update("""
            UPDATE conversation_user_setting
            SET is_hidden = 1,
                is_top = 0,
                unread_count = 0,
                draft_content = NULL,
                clear_message_before = #{clearMessageBefore},
                last_read_message_id = #{lastReadMessageId},
                last_read_at = NOW(),
                updated_at = NOW()
            WHERE conversation_id = #{conversationId}
              AND user_id = #{userId}
            """)
    int deleteConversationForMe(@Param("conversationId") Long conversationId,
                                @Param("userId") Long userId,
                                @Param("clearMessageBefore") LocalDateTime clearMessageBefore,
                                @Param("lastReadMessageId") Long lastReadMessageId);

    @Select("""
            SELECT c.id
            FROM conversation c
            JOIN conversation_member cm1 ON cm1.conversation_id = c.id AND cm1.user_id = #{userId} AND cm1.status = 1
            JOIN conversation_member cm2 ON cm2.conversation_id = c.id AND cm2.user_id = #{friendUserId} AND cm2.status = 1
            WHERE c.conversation_type = 'single'
              AND c.status = 1
            LIMIT 1
            """)
    Long findSingleConversationId(@Param("userId") Long userId, @Param("friendUserId") Long friendUserId);

    @Select("""
            SELECT c.id AS conversation_id, cm2.user_id AS friend_user_id
            FROM conversation c
            JOIN conversation_member cm ON cm.conversation_id = c.id AND cm.user_id = #{userId} AND cm.status = 1
            JOIN conversation_member cm2 ON cm2.conversation_id = c.id AND cm2.user_id != #{userId} AND cm2.status = 1
            WHERE c.conversation_type = 'single'
              AND c.status = 1
            """)
    List<Map<String, Object>> findSingleConversationIdsForUser(@Param("userId") Long userId);

    @Select("""
            SELECT user_id
            FROM conversation_member
            WHERE conversation_id = #{conversationId}
              AND status = 1
            ORDER BY id ASC
            """)
    List<Long> findMemberUserIds(@Param("conversationId") Long conversationId);

    @Select("""
            SELECT user_id
            FROM conversation_member
            WHERE conversation_id = #{conversationId}
            ORDER BY id ASC
            """)
    List<Long> findMemberUserIdsIncludingDeleted(@Param("conversationId") Long conversationId);

    @Update("""
            UPDATE conversation_member
            SET status = 2,
                updated_at = NOW()
            WHERE conversation_id = #{conversationId}
            """)
    int deleteConversationMembers(@Param("conversationId") Long conversationId);

    @Update("""
            UPDATE conversation
            SET status = 2,
                updated_at = NOW()
            WHERE id = #{conversationId}
            """)
    int deleteConversation(@Param("conversationId") Long conversationId);

    @Update("""
            UPDATE conversation_member
            SET member_role = #{memberRole},
                display_name = #{displayName},
                join_source = #{joinSource},
                inviter_user_id = #{inviterUserId},
                status = 1,
                joined_at = #{joinedAt},
                updated_at = NOW()
            WHERE conversation_id = #{conversationId}
              AND user_id = #{userId}
            """)
    int reactivateConversationMember(ConversationMember member);

    @Update("""
            UPDATE conversation_member
            SET status = #{status},
                updated_at = NOW()
            WHERE conversation_id = #{conversationId}
              AND user_id = #{userId}
              AND status = 1
            """)
    int updateConversationMemberStatus(@Param("conversationId") Long conversationId,
                                       @Param("userId") Long userId,
                                       @Param("status") Integer status);

    @Update("""
            UPDATE conversation
            SET announcement = #{announcement},
                updated_at = NOW()
            WHERE id = #{conversationId}
            """)
    int updateConversationAnnouncement(@Param("conversationId") Long conversationId,
                                       @Param("announcement") String announcement);

    @Update("""
            UPDATE conversation
            SET name = #{name},
                updated_at = NOW()
            WHERE id = #{conversationId}
            """)
    int updateConversationName(@Param("conversationId") Long conversationId,
                               @Param("name") String name);

    @Update("""
            UPDATE conversation_user_setting
            SET remark = #{remark},
                updated_at = NOW()
            WHERE conversation_id = #{conversationId}
              AND user_id = #{userId}
            """)
    int updateConversationUserRemark(@Param("conversationId") Long conversationId,
                                     @Param("userId") Long userId,
                                     @Param("remark") String remark);

    @Update("""
            UPDATE conversation_member
            SET display_name = #{displayName},
                updated_at = NOW()
            WHERE conversation_id = #{conversationId}
              AND user_id = #{userId}
              AND status = 1
            """)
    int updateConversationMemberDisplayName(@Param("conversationId") Long conversationId,
                                            @Param("userId") Long userId,
                                            @Param("displayName") String displayName);

    @Update("""
            UPDATE conversation
            SET mute_all = #{muteAll},
                updated_at = NOW()
            WHERE id = #{conversationId}
            """)
    int updateConversationMuteAll(@Param("conversationId") Long conversationId,
                                  @Param("muteAll") Integer muteAll);

    @Update("""
            UPDATE conversation
            SET join_rule = #{joinRule},
                updated_at = NOW()
            WHERE id = #{conversationId}
            """)
    int updateConversationJoinRule(@Param("conversationId") Long conversationId,
                                   @Param("joinRule") String joinRule);

    @Update("""
            UPDATE conversation
            SET owner_user_id = #{ownerUserId},
                updated_at = NOW()
            WHERE id = #{conversationId}
            """)
    int updateConversationOwner(@Param("conversationId") Long conversationId,
                                @Param("ownerUserId") Long ownerUserId);

    @Update("""
            UPDATE conversation_member
            SET member_role = #{memberRole},
                updated_at = NOW()
            WHERE conversation_id = #{conversationId}
              AND user_id = #{userId}
              AND status = 1
            """)
    int updateConversationMemberRole(@Param("conversationId") Long conversationId,
                                     @Param("userId") Long userId,
                                     @Param("memberRole") String memberRole);

    @Update("""
            UPDATE conversation_member
            SET is_muted = #{isMuted},
                mute_until = #{muteUntil},
                updated_at = NOW()
            WHERE conversation_id = #{conversationId}
              AND user_id = #{userId}
              AND status = 1
            """)
    int updateConversationMemberMute(@Param("conversationId") Long conversationId,
                                     @Param("userId") Long userId,
                                     @Param("isMuted") Integer isMuted,
                                     @Param("muteUntil") LocalDateTime muteUntil);

    @Insert("""
            INSERT INTO conversation_join_request (
                conversation_id, applicant_user_id, inviter_user_id, request_message, status, handled_by, handled_at
            ) VALUES (
                #{conversationId}, #{applicantUserId}, #{inviterUserId}, #{requestMessage}, #{status}, #{handledBy}, #{handledAt}
            )
            """)
    int insertConversationJoinRequest(@Param("conversationId") Long conversationId,
                                      @Param("applicantUserId") Long applicantUserId,
                                      @Param("inviterUserId") Long inviterUserId,
                                      @Param("requestMessage") String requestMessage,
                                      @Param("status") String status,
                                      @Param("handledBy") Long handledBy,
                                      @Param("handledAt") LocalDateTime handledAt);

    @Select("""
            SELECT COUNT(1)
            FROM conversation_join_request
            WHERE conversation_id = #{conversationId}
              AND applicant_user_id = #{applicantUserId}
              AND status = 'pending'
            """)
    int countPendingJoinRequest(@Param("conversationId") Long conversationId,
                                @Param("applicantUserId") Long applicantUserId);

    @Select("""
            SELECT cjr.id,
                   cjr.conversation_id,
                   cjr.applicant_user_id,
                   applicant.nickname AS applicant_nickname,
                   applicant.avatar_url AS applicant_avatar_url,
                   cjr.inviter_user_id,
                   inviter.nickname AS inviter_nickname,
                   cjr.request_message,
                   cjr.status,
                   cjr.handled_by,
                   handler.nickname AS handled_by_nickname,
                   cjr.handled_at,
                   cjr.created_at
            FROM conversation_join_request cjr
            JOIN wechat_user applicant ON applicant.id = cjr.applicant_user_id
            LEFT JOIN wechat_user inviter ON inviter.id = cjr.inviter_user_id
            LEFT JOIN wechat_user handler ON handler.id = cjr.handled_by
            WHERE cjr.conversation_id = #{conversationId}
            ORDER BY CASE cjr.status WHEN 'pending' THEN 1 WHEN 'accepted' THEN 2 ELSE 3 END,
                     cjr.created_at DESC
            """)
    List<GroupJoinRequestView> findConversationJoinRequests(@Param("conversationId") Long conversationId);

    @Select("""
            SELECT cjr.id,
                   cjr.conversation_id,
                   cjr.applicant_user_id,
                   applicant.nickname AS applicant_nickname,
                   applicant.avatar_url AS applicant_avatar_url,
                   cjr.inviter_user_id,
                   inviter.nickname AS inviter_nickname,
                   cjr.request_message,
                   cjr.status,
                   cjr.handled_by,
                   handler.nickname AS handled_by_nickname,
                   cjr.handled_at,
                   cjr.created_at
            FROM conversation_join_request cjr
            JOIN wechat_user applicant ON applicant.id = cjr.applicant_user_id
            LEFT JOIN wechat_user inviter ON inviter.id = cjr.inviter_user_id
            LEFT JOIN wechat_user handler ON handler.id = cjr.handled_by
            WHERE cjr.id = #{requestId}
              AND cjr.conversation_id = #{conversationId}
            LIMIT 1
            """)
    GroupJoinRequestView findConversationJoinRequestById(@Param("conversationId") Long conversationId,
                                                         @Param("requestId") Long requestId);

    @Update("""
            UPDATE conversation_join_request
            SET status = #{status},
                handled_by = #{handledBy},
                handled_at = #{handledAt},
                updated_at = NOW()
            WHERE id = #{requestId}
              AND conversation_id = #{conversationId}
              AND status = 'pending'
            """)
    int handleConversationJoinRequest(@Param("conversationId") Long conversationId,
                                      @Param("requestId") Long requestId,
                                      @Param("status") String status,
                                      @Param("handledBy") Long handledBy,
                                      @Param("handledAt") LocalDateTime handledAt);

    @Insert("""
            INSERT INTO chat_message (
                conversation_id, sender_user_id, message_type, message_status,
                content, content_json, client_message_id, quote_message_id,
                forward_from_message_id, is_recalled, recall_at, sent_at
            ) VALUES (
                #{conversationId}, #{senderUserId}, #{messageType}, #{messageStatus},
                #{content}, #{contentJson}, #{clientMessageId}, #{quoteMessageId},
                #{forwardFromMessageId}, #{isRecalled}, #{recallAt}, #{sentAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertChatMessage(ChatMessage chatMessage);

    @Insert("""
            INSERT INTO message_user_status (
                message_id, user_id, delivered_at, read_at, is_deleted_for_me, is_mentioned
            ) VALUES (
                #{messageId}, #{userId}, #{deliveredAt}, #{readAt}, #{isDeletedForMe}, #{isMentioned}
            )
            """)
    int insertMessageUserStatus(@Param("messageId") Long messageId,
                                @Param("userId") Long userId,
                                @Param("deliveredAt") LocalDateTime deliveredAt,
                                @Param("readAt") LocalDateTime readAt,
                                @Param("isDeletedForMe") Integer isDeletedForMe,
                                @Param("isMentioned") Integer isMentioned);

    @Insert("""
            INSERT INTO message_user_status (
                message_id, user_id, delivered_at, read_at, is_deleted_for_me, is_mentioned
            ) VALUES (
                #{messageId}, #{userId}, #{deliveredAt}, #{readAt}, 1, #{isMentioned}
            )
            ON DUPLICATE KEY UPDATE
                is_deleted_for_me = 1,
                updated_at = NOW()
            """)
    int upsertDeletedMessageForMe(@Param("messageId") Long messageId,
                                  @Param("userId") Long userId,
                                  @Param("deliveredAt") LocalDateTime deliveredAt,
                                  @Param("readAt") LocalDateTime readAt,
                                  @Param("isMentioned") Integer isMentioned);

    @Insert("""
            INSERT INTO chat_message_attachment (message_id, file_id, sort_order)
            VALUES (#{messageId}, #{fileId}, 0)
            """)
    int insertMessageAttachment(@Param("messageId") Long messageId, @Param("fileId") Long fileId);

    @Select("""
            SELECT *
            FROM chat_message
            WHERE id = #{messageId}
            LIMIT 1
            """)
    ChatMessage findChatMessageById(@Param("messageId") Long messageId);

    @Update("""
            UPDATE conversation
            SET last_message_id = #{messageId},
                last_message_type = #{messageType},
                last_message_content = #{content},
                last_sender_id = #{senderUserId},
                last_message_at = #{sentAt},
                updated_at = NOW()
            WHERE id = #{conversationId}
            """)
    int updateConversationLastMessage(@Param("conversationId") Long conversationId,
                                      @Param("messageId") Long messageId,
                                      @Param("messageType") String messageType,
                                      @Param("content") String content,
                                      @Param("senderUserId") Long senderUserId,
                                      @Param("sentAt") LocalDateTime sentAt);

    @Update("""
            UPDATE chat_message
            SET message_type = 'revoke',
                message_status = 'recalled',
                content = #{content},
                is_recalled = 1,
                recall_at = NOW(),
                updated_at = NOW()
            WHERE id = #{messageId}
              AND sender_user_id = #{userId}
              AND is_recalled = 0
            """)
    int recallMessage(@Param("messageId") Long messageId,
                      @Param("userId") Long userId,
                      @Param("content") String content);

    @Update("""
            UPDATE conversation_user_setting
            SET unread_count = unread_count + 1,
                is_hidden = 0,
                updated_at = NOW()
            WHERE conversation_id = #{conversationId}
              AND user_id = #{userId}
            """)
    int increaseUnreadCount(@Param("conversationId") Long conversationId, @Param("userId") Long userId);

    @Update("""
            UPDATE conversation_user_setting
            SET unread_count = 0,
                last_read_message_id = CASE
                    WHEN clear_message_before IS NULL THEN #{lastReadMessageId}
                    ELSE last_read_message_id
                END,
                last_read_at = NOW(),
                updated_at = NOW()
            WHERE conversation_id = #{conversationId}
              AND user_id = #{userId}
            """)
    int markConversationRead(@Param("conversationId") Long conversationId,
                             @Param("userId") Long userId,
                             @Param("lastReadMessageId") Long lastReadMessageId);

    @Update("""
            UPDATE message_user_status
            SET read_at = NOW(),
                updated_at = NOW()
            WHERE message_id IN (
                SELECT id FROM chat_message WHERE conversation_id = #{conversationId}
            )
              AND user_id = #{userId}
              AND is_deleted_for_me = 0
              AND read_at IS NULL
            """)
    int markMessagesRead(@Param("conversationId") Long conversationId, @Param("userId") Long userId);

    @Update("""
            UPDATE message_user_status mus
            JOIN chat_message m ON m.id = mus.message_id
            SET mus.is_deleted_for_me = 1,
                mus.updated_at = NOW()
            WHERE mus.message_id = #{messageId}
              AND mus.user_id = #{userId}
              AND m.conversation_id = #{conversationId}
              AND mus.is_deleted_for_me = 0
            """)
    int deleteMessageForMe(@Param("conversationId") Long conversationId,
                           @Param("messageId") Long messageId,
                           @Param("userId") Long userId);

    @Select("""
            SELECT m.id AS message_id,
                   m.conversation_id,
                   m.sender_user_id,
                   u.nickname AS sender_nickname,
                   u.username AS sender_username,
                   CASE
                       WHEN cm.display_name IS NOT NULL AND cm.display_name != u.nickname AND cm.display_name != u.username THEN cm.display_name
                       ELSE NULL
                   END AS sender_display_name,
                   CASE
                       WHEN f.remark_name IS NOT NULL AND f.remark_name != u.nickname AND f.remark_name != u.username THEN f.remark_name
                       ELSE NULL
                   END AS sender_remark_name,
                   u.avatar_url AS sender_avatar_url,
                   m.message_type,
                   m.message_status,
                   m.content,
                   m.content_json,
                   m.quote_message_id,
                   qm.sender_user_id AS quote_sender_user_id,
                   qu.nickname AS quote_sender_nickname,
                   CASE
                       WHEN qcm.display_name IS NOT NULL AND qcm.display_name != qu.nickname AND qcm.display_name != qu.username THEN qcm.display_name
                       ELSE NULL
                   END AS quote_sender_display_name,
                   qm.message_type AS quote_message_type,
                   qm.content AS quote_message_content,
                   m.sent_at,
                   fr.id AS file_id,
                   fr.file_name,
                   fr.file_url,
                   fr.mime_type,
                   fr.thumbnail_url,
                   fr.width,
                   fr.height,
                   fr.duration_seconds
            FROM chat_message m
            JOIN wechat_user u ON u.id = m.sender_user_id
            LEFT JOIN conversation_member cm ON cm.conversation_id = m.conversation_id AND cm.user_id = m.sender_user_id AND cm.status = 1
            LEFT JOIN friendship f ON f.user_id = #{userId} AND f.friend_user_id = m.sender_user_id AND f.status = 1
            JOIN message_user_status mus ON mus.message_id = m.id AND mus.user_id = #{userId} AND mus.is_deleted_for_me = 0
            LEFT JOIN conversation_user_setting cus
                ON cus.conversation_id = m.conversation_id
               AND cus.user_id = #{userId}
            LEFT JOIN chat_message qm ON qm.id = m.quote_message_id
            LEFT JOIN wechat_user qu ON qu.id = qm.sender_user_id
            LEFT JOIN conversation_member qcm ON qcm.conversation_id = m.conversation_id AND qcm.user_id = qm.sender_user_id AND qcm.status = 1
            LEFT JOIN chat_message_attachment cma ON cma.message_id = m.id
            LEFT JOIN file_resource fr ON fr.id = cma.file_id
            WHERE m.conversation_id = #{conversationId}
              AND (
                  cus.clear_message_before IS NULL
                  OR m.sent_at > cus.clear_message_before
                  OR (m.sent_at = cus.clear_message_before AND m.id > COALESCE(cus.last_read_message_id, 0))
              )
            ORDER BY m.id ASC
            """)
    List<ConversationMessageView> findMessages(@Param("conversationId") Long conversationId,
                                               @Param("userId") Long userId);

    @Select("""
            SELECT page.message_id,
                   page.conversation_id,
                   page.sender_user_id,
                   page.sender_nickname,
                   page.sender_username,
                   page.sender_display_name,
                   page.sender_remark_name,
                   page.sender_avatar_url,
                   page.message_type,
                   page.message_status,
                   page.content,
                   page.content_json,
                   page.quote_message_id,
                   page.quote_sender_user_id,
                   page.quote_sender_nickname,
                   page.quote_sender_display_name,
                   page.quote_message_type,
                   page.quote_message_content,
                   page.sent_at,
                   page.file_id,
                   page.file_name,
                   page.file_url,
                   page.mime_type,
                   page.thumbnail_url,
                   page.width,
                   page.height,
                   page.duration_seconds
            FROM (
                SELECT m.id AS message_id,
                       m.conversation_id,
                       m.sender_user_id,
                       u.nickname AS sender_nickname,
                       u.username AS sender_username,
                       CASE
                           WHEN cm.display_name IS NOT NULL AND cm.display_name != u.nickname AND cm.display_name != u.username THEN cm.display_name
                           ELSE NULL
                       END AS sender_display_name,
                       CASE
                           WHEN f.remark_name IS NOT NULL AND f.remark_name != u.nickname AND f.remark_name != u.username THEN f.remark_name
                           ELSE NULL
                       END AS sender_remark_name,
                       u.avatar_url AS sender_avatar_url,
                       m.message_type,
                       m.message_status,
                       m.content,
                       m.content_json,
                       m.quote_message_id,
                       qm.sender_user_id AS quote_sender_user_id,
                       qu.nickname AS quote_sender_nickname,
                       CASE
                           WHEN qcm.display_name IS NOT NULL AND qcm.display_name != qu.nickname AND qcm.display_name != qu.username THEN qcm.display_name
                           ELSE NULL
                       END AS quote_sender_display_name,
                       qm.message_type AS quote_message_type,
                       qm.content AS quote_message_content,
                       m.sent_at,
                       fr.id AS file_id,
                       fr.file_name,
                       fr.file_url,
                       fr.mime_type,
                       fr.thumbnail_url,
                       fr.width,
                       fr.height,
                       fr.duration_seconds
                FROM chat_message m
                JOIN wechat_user u ON u.id = m.sender_user_id
                LEFT JOIN conversation_member cm ON cm.conversation_id = m.conversation_id AND cm.user_id = m.sender_user_id AND cm.status = 1
                LEFT JOIN friendship f ON f.user_id = #{userId} AND f.friend_user_id = m.sender_user_id AND f.status = 1
                JOIN message_user_status mus ON mus.message_id = m.id AND mus.user_id = #{userId} AND mus.is_deleted_for_me = 0
                LEFT JOIN conversation_user_setting cus
                    ON cus.conversation_id = m.conversation_id
                   AND cus.user_id = #{userId}
                LEFT JOIN chat_message qm ON qm.id = m.quote_message_id
                LEFT JOIN wechat_user qu ON qu.id = qm.sender_user_id
                LEFT JOIN conversation_member qcm ON qcm.conversation_id = m.conversation_id AND qcm.user_id = qm.sender_user_id AND qcm.status = 1
                LEFT JOIN chat_message_attachment cma ON cma.message_id = m.id
                LEFT JOIN file_resource fr ON fr.id = cma.file_id
                WHERE m.conversation_id = #{conversationId}
                  AND (#{beforeMessageId} IS NULL OR m.id < #{beforeMessageId})
                  AND (
                      cus.clear_message_before IS NULL
                      OR m.sent_at > cus.clear_message_before
                      OR (m.sent_at = cus.clear_message_before AND m.id > COALESCE(cus.last_read_message_id, 0))
                  )
                ORDER BY m.id DESC
                LIMIT #{limit}
            ) page
            ORDER BY page.message_id ASC
            """)
    List<ConversationMessageView> findMessagesPage(@Param("conversationId") Long conversationId,
                                                   @Param("userId") Long userId,
                                                   @Param("beforeMessageId") Long beforeMessageId,
                                                   @Param("limit") Integer limit);

    @Select("""
            SELECT page.message_id,
                   page.conversation_id,
                   page.sender_user_id,
                   page.sender_nickname,
                   page.sender_username,
                   page.sender_display_name,
                   page.sender_remark_name,
                   page.sender_avatar_url,
                   page.message_type,
                   page.message_status,
                   page.content,
                   page.content_json,
                   page.quote_message_id,
                   page.quote_sender_user_id,
                   page.quote_sender_nickname,
                   page.quote_sender_display_name,
                   page.quote_message_type,
                   page.quote_message_content,
                   page.sent_at,
                   page.file_id,
                   page.file_name,
                   page.file_url,
                   page.mime_type,
                   page.thumbnail_url,
                   page.width,
                   page.height,
                   page.duration_seconds
            FROM (
                SELECT m.id AS message_id,
                       m.conversation_id,
                       m.sender_user_id,
                       u.nickname AS sender_nickname,
                       u.username AS sender_username,
                       CASE
                           WHEN cm.display_name IS NOT NULL AND cm.display_name != u.nickname AND cm.display_name != u.username THEN cm.display_name
                           ELSE NULL
                       END AS sender_display_name,
                       CASE
                           WHEN f.remark_name IS NOT NULL AND f.remark_name != u.nickname AND f.remark_name != u.username THEN f.remark_name
                           ELSE NULL
                       END AS sender_remark_name,
                       u.avatar_url AS sender_avatar_url,
                       m.message_type,
                       m.message_status,
                       m.content,
                       m.content_json,
                       m.quote_message_id,
                       qm.sender_user_id AS quote_sender_user_id,
                       qu.nickname AS quote_sender_nickname,
                       CASE
                           WHEN qcm.display_name IS NOT NULL AND qcm.display_name != qu.nickname AND qcm.display_name != qu.username THEN qcm.display_name
                           ELSE NULL
                       END AS quote_sender_display_name,
                       qm.message_type AS quote_message_type,
                       qm.content AS quote_message_content,
                       m.sent_at,
                       fr.id AS file_id,
                       fr.file_name,
                       fr.file_url,
                       fr.mime_type,
                       fr.thumbnail_url,
                       fr.width,
                       fr.height,
                       fr.duration_seconds
                FROM chat_message m
                JOIN wechat_user u ON u.id = m.sender_user_id
                LEFT JOIN conversation_member cm ON cm.conversation_id = m.conversation_id AND cm.user_id = m.sender_user_id AND cm.status = 1
                LEFT JOIN friendship f ON f.user_id = #{userId} AND f.friend_user_id = m.sender_user_id AND f.status = 1
                JOIN message_user_status mus ON mus.message_id = m.id AND mus.user_id = #{userId} AND mus.is_deleted_for_me = 0
                LEFT JOIN conversation_user_setting cus
                    ON cus.conversation_id = m.conversation_id
                   AND cus.user_id = #{userId}
                LEFT JOIN chat_message qm ON qm.id = m.quote_message_id
                LEFT JOIN wechat_user qu ON qu.id = qm.sender_user_id
                LEFT JOIN conversation_member qcm ON qcm.conversation_id = m.conversation_id AND qcm.user_id = qm.sender_user_id AND qcm.status = 1
                LEFT JOIN chat_message_attachment cma ON cma.message_id = m.id
                LEFT JOIN file_resource fr ON fr.id = cma.file_id
                WHERE m.conversation_id = #{conversationId}
                  AND (#{beforeMessageId} IS NULL OR m.id < #{beforeMessageId})
                  AND (
                      cus.clear_message_before IS NULL
                      OR m.sent_at > cus.clear_message_before
                      OR (m.sent_at = cus.clear_message_before AND m.id > COALESCE(cus.last_read_message_id, 0))
                  )
                  AND (#{messageType} IS NULL OR m.message_type = #{messageType})
                  AND (
                      #{keywordLike} IS NULL
                      OR m.content LIKE #{keywordLike}
                      OR u.nickname LIKE #{keywordLike}
                      OR fr.file_name LIKE #{keywordLike}
                  )
                ORDER BY m.id DESC
                LIMIT #{limit}
            ) page
            ORDER BY page.message_id ASC
            """)
    List<ConversationMessageView> searchMessagesPage(@Param("conversationId") Long conversationId,
                                                     @Param("userId") Long userId,
                                                     @Param("beforeMessageId") Long beforeMessageId,
                                                     @Param("messageType") String messageType,
                                                     @Param("keywordLike") String keywordLike,
                                                     @Param("limit") Integer limit);

    @Select("""
            SELECT COUNT(1)
            FROM chat_message m
            JOIN message_user_status mus ON mus.message_id = m.id AND mus.user_id = #{userId} AND mus.is_deleted_for_me = 0
            LEFT JOIN conversation_user_setting cus
                ON cus.conversation_id = m.conversation_id
               AND cus.user_id = #{userId}
            WHERE m.conversation_id = #{conversationId}
              AND (#{beforeMessageId} IS NULL OR m.id < #{beforeMessageId})
              AND (
                  cus.clear_message_before IS NULL
                  OR m.sent_at > cus.clear_message_before
                  OR (m.sent_at = cus.clear_message_before AND m.id > COALESCE(cus.last_read_message_id, 0))
              )
            """)
    int countVisibleMessagesBefore(@Param("conversationId") Long conversationId,
                                   @Param("userId") Long userId,
                                   @Param("beforeMessageId") Long beforeMessageId);

    @Select("""
            SELECT mus.user_id,
                   u.nickname,
                   u.avatar_url,
                   mus.read_at
            FROM message_user_status mus
            JOIN wechat_user u ON u.id = mus.user_id
            JOIN chat_message m ON m.id = mus.message_id
            WHERE mus.message_id = #{messageId}
              AND m.conversation_id = #{conversationId}
              AND mus.user_id <> m.sender_user_id
              AND mus.read_at IS NOT NULL
            ORDER BY mus.read_at ASC, mus.user_id ASC
            """)
    List<MessageReadReceiptView.MessageReaderView> findMessageReaders(@Param("conversationId") Long conversationId,
                                                                      @Param("messageId") Long messageId);

    @Select("""
            SELECT COUNT(1)
            FROM message_user_status mus
            JOIN chat_message m ON m.id = mus.message_id
            WHERE mus.message_id = #{messageId}
              AND m.conversation_id = #{conversationId}
              AND mus.user_id <> m.sender_user_id
              AND mus.read_at IS NOT NULL
            """)
    int countMessageReaders(@Param("conversationId") Long conversationId,
                            @Param("messageId") Long messageId);

    @Select("""
            SELECT COUNT(1)
            FROM message_user_status mus
            JOIN chat_message m ON m.id = mus.message_id
            WHERE mus.message_id = #{messageId}
              AND m.conversation_id = #{conversationId}
              AND mus.user_id <> m.sender_user_id
              AND mus.read_at IS NULL
            """)
    int countMessageUnreadUsers(@Param("conversationId") Long conversationId,
                                @Param("messageId") Long messageId);

    @Select("""
            SELECT m.id AS message_id,
                   m.conversation_id,
                   m.sender_user_id,
                   u.nickname AS sender_nickname,
                   u.username AS sender_username,
                   CASE
                       WHEN cm.display_name IS NOT NULL AND cm.display_name != u.nickname AND cm.display_name != u.username THEN cm.display_name
                       ELSE NULL
                   END AS sender_display_name,
                   CASE
                       WHEN f.remark_name IS NOT NULL AND f.remark_name != u.nickname AND f.remark_name != u.username THEN f.remark_name
                       ELSE NULL
                   END AS sender_remark_name,
                   u.avatar_url AS sender_avatar_url,
                   m.message_type,
                   m.message_status,
                   m.content,
                   m.content_json,
                   m.quote_message_id,
                   qm.sender_user_id AS quote_sender_user_id,
                   qu.nickname AS quote_sender_nickname,
                   CASE
                       WHEN qcm.display_name IS NOT NULL AND qcm.display_name != qu.nickname AND qcm.display_name != qu.username THEN qcm.display_name
                       ELSE NULL
                   END AS quote_sender_display_name,
                   qm.message_type AS quote_message_type,
                   qm.content AS quote_message_content,
                   m.sent_at,
                   fr.id AS file_id,
                   fr.file_name,
                   fr.file_url,
                   fr.mime_type,
                   fr.thumbnail_url,
                   fr.width,
                   fr.height,
                   fr.duration_seconds
            FROM chat_message m
            JOIN wechat_user u ON u.id = m.sender_user_id
            LEFT JOIN conversation_member cm ON cm.conversation_id = m.conversation_id AND cm.user_id = m.sender_user_id AND cm.status = 1
            LEFT JOIN friendship f ON f.user_id = #{userId} AND f.friend_user_id = m.sender_user_id AND f.status = 1
            LEFT JOIN chat_message qm ON qm.id = m.quote_message_id
            LEFT JOIN wechat_user qu ON qu.id = qm.sender_user_id
            LEFT JOIN conversation_member qcm ON qcm.conversation_id = m.conversation_id AND qcm.user_id = qm.sender_user_id AND qcm.status = 1
            LEFT JOIN chat_message_attachment cma ON cma.message_id = m.id
            LEFT JOIN file_resource fr ON fr.id = cma.file_id
            WHERE m.id = #{messageId}
            LIMIT 1
            """)
    ConversationMessageView findMessageById(@Param("messageId") Long messageId,
                                             @Param("userId") Long userId);

    @Select("""
            SELECT MAX(id)
            FROM chat_message
            WHERE conversation_id = #{conversationId}
            """)
    Long findLatestMessageId(@Param("conversationId") Long conversationId);

    @Select("""
            SELECT mus.message_id
            FROM message_user_status mus
            JOIN chat_message m ON m.id = mus.message_id
            WHERE m.conversation_id = #{conversationId}
              AND mus.user_id = #{userId}
              AND mus.is_deleted_for_me = 0
              AND mus.read_at IS NULL
              AND m.sender_user_id <> #{userId}
            ORDER BY mus.message_id ASC
            """)
    List<Long> findUnreadMessageIdsForUser(@Param("conversationId") Long conversationId,
                                           @Param("userId") Long userId);

    @Select("""
            SELECT sender_user_id
            FROM chat_message
            WHERE id = #{messageId}
            LIMIT 1
            """)
    Long findMessageSenderUserId(@Param("messageId") Long messageId);
}
