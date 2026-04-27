package com.mingjin.school_wechat.mapper;

import com.mingjin.school_wechat.model.entity.AuthSession;
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
import com.mingjin.school_wechat.model.view.ConversationMessageView;
import com.mingjin.school_wechat.model.view.ConversationSummaryView;
import com.mingjin.school_wechat.model.view.FriendRequestView;
import com.mingjin.school_wechat.model.view.FriendView;
import com.mingjin.school_wechat.model.view.UserSearchView;
import com.mingjin.school_wechat.model.view.UserNotificationView;
import com.mingjin.school_wechat.model.view.UserSyncEventView;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FriendMapper {

    @Select("""
            SELECT f.friend_user_id,
                   f.remark_name,
                   f.is_starred,
                   f.is_muted,
                   u.nickname,
                   u.wechat_no,
                   u.avatar_url,
                   u.signature,
                   u.region
            FROM friendship f
            JOIN wechat_user u ON u.id = f.friend_user_id
            WHERE f.user_id = #{userId}
              AND f.status = 1
            ORDER BY f.is_starred DESC, u.nickname ASC
            """)
    List<FriendView> findFriendsByUserId(@Param("userId") Long userId);

    @Select("""
            SELECT fr.id,
                   fr.from_user_id,
                   fu.username AS from_username,
                   fu.nickname AS from_nickname,
                   fu.avatar_url AS from_avatar_url,
                   fr.to_user_id,
                   tu.username AS to_username,
                   tu.nickname AS to_nickname,
                   tu.avatar_url AS to_avatar_url,
                   fr.request_message,
                   fr.source,
                   fr.status,
                   fr.created_at
            FROM friend_request fr
            JOIN wechat_user fu ON fu.id = fr.from_user_id
            JOIN wechat_user tu ON tu.id = fr.to_user_id
            WHERE fr.to_user_id = #{userId}
               OR fr.from_user_id = #{userId}
            ORDER BY fr.created_at DESC
            """)
    List<FriendRequestView> findFriendRequestsByUserId(@Param("userId") Long userId);

    @Insert("""
            INSERT INTO friend_request (
                from_user_id, to_user_id, request_message, source, status
            ) VALUES (
                #{fromUserId}, #{toUserId}, #{requestMessage}, #{source}, #{status}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertFriendRequest(FriendRequest friendRequest);

    @Select("""
            SELECT *
            FROM friend_request
            WHERE id = #{id}
            LIMIT 1
            """)
    FriendRequest findFriendRequestById(@Param("id") Long id);

    @Select("""
            SELECT *
            FROM friend_request
            WHERE from_user_id = #{fromUserId}
              AND to_user_id = #{toUserId}
              AND status = 'pending'
            ORDER BY id DESC
            LIMIT 1
            """)
    FriendRequest findPendingFriendRequest(@Param("fromUserId") Long fromUserId,
                                           @Param("toUserId") Long toUserId);

    @Select("""
            SELECT *
            FROM friend_request
            WHERE status = 'pending'
              AND (
                (from_user_id = #{leftUserId} AND to_user_id = #{rightUserId})
                OR (from_user_id = #{rightUserId} AND to_user_id = #{leftUserId})
              )
            ORDER BY id DESC
            """)
    List<FriendRequest> findPendingFriendRequestsBetweenUsers(@Param("leftUserId") Long leftUserId,
                                                              @Param("rightUserId") Long rightUserId);

    @Update("""
            UPDATE friend_request
            SET status = #{status},
                handled_by = #{handledBy},
                handled_at = NOW(),
                updated_at = NOW()
            WHERE id = #{id}
            """)
    int updateFriendRequestStatus(@Param("id") Long id,
                                  @Param("status") String status,
                                  @Param("handledBy") Long handledBy);

    @Select("""
            SELECT COUNT(1)
            FROM friendship
            WHERE user_id = #{userId}
              AND friend_user_id = #{friendUserId}
              AND status = 1
            """)
    int countFriendship(@Param("userId") Long userId, @Param("friendUserId") Long friendUserId);

    @Select("""
            SELECT *
            FROM friendship
            WHERE user_id = #{userId}
              AND friend_user_id = #{friendUserId}
            LIMIT 1
            """)
    Friendship findFriendship(@Param("userId") Long userId, @Param("friendUserId") Long friendUserId);

    @Update("""
            UPDATE friendship
            SET status = 1,
                source_request_id = #{requestId},
                updated_at = NOW()
            WHERE user_id = #{userId}
              AND friend_user_id = #{friendUserId}
              AND status = 2
            """)
    int restoreDeletedFriendship(@Param("userId") Long userId,
                                  @Param("friendUserId") Long friendUserId,
                                  @Param("requestId") Long requestId);

    @Insert("""
            INSERT INTO friendship (
                user_id, friend_user_id, friend_group_id, source_request_id,
                remark_name, is_starred, is_muted, status
            ) VALUES (
                #{userId}, #{friendUserId}, #{friendGroupId}, #{sourceRequestId},
                #{remarkName}, #{isStarred}, #{isMuted}, #{status}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertFriendship(Friendship friendship);

    @Update("""
            UPDATE friendship
            SET status = #{status},
                updated_at = NOW()
            WHERE user_id = #{userId}
              AND friend_user_id = #{friendUserId}
              AND status = 1
            """)
    int updateFriendshipStatus(@Param("userId") Long userId,
                               @Param("friendUserId") Long friendUserId,
                               @Param("status") Integer status);

    @Update("""
            UPDATE friendship
            SET remark_name = #{remarkName},
                updated_at = NOW()
            WHERE user_id = #{userId}
              AND friend_user_id = #{friendUserId}
              AND status = 1
            """)
    int updateFriendRemark(@Param("userId") Long userId,
                           @Param("friendUserId") Long friendUserId,
                           @Param("remarkName") String remarkName);

    @Select("""
            SELECT *
            FROM wechat_user
            WHERE id = #{userId}
            LIMIT 1
            """)
    WechatUser findTargetUserById(@Param("userId") Long userId);

    @Select("""
            SELECT u.id,
                   u.username,
                   u.nickname,
                   u.wechat_no,
                   u.avatar_url,
                   u.region,
                   u.signature,
                   CASE
                       WHEN EXISTS (
                           SELECT 1
                           FROM friendship f
                           WHERE f.user_id = #{currentUserId}
                             AND f.friend_user_id = u.id
                             AND f.status = 1
                       ) THEN 1
                       ELSE 0
                   END AS is_friend
            FROM wechat_user u
            WHERE u.status = 1
              AND u.id <> #{currentUserId}
              AND (
                  (#{targetUserId} IS NOT NULL AND u.id = #{targetUserId})
                  OR (#{keywordLike} IS NOT NULL AND (
                      u.username LIKE #{keywordLike}
                      OR u.wechat_no LIKE #{keywordLike}
                      OR u.nickname LIKE #{keywordLike}
                  ))
              )
            ORDER BY is_friend ASC, u.nickname ASC, u.id ASC
            LIMIT 20
            """)
    List<UserSearchView> searchUsers(@Param("currentUserId") Long currentUserId,
                                     @Param("targetUserId") Long targetUserId,
                                     @Param("keywordLike") String keywordLike);

    @Select("""
            SELECT COUNT(1)
            FROM user_blacklist
            WHERE user_id = #{userId}
              AND blocked_user_id = #{blockedUserId}
            """)
    int countBlacklist(@Param("userId") Long userId, @Param("blockedUserId") Long blockedUserId);

    @Insert("""
            INSERT INTO user_blacklist (user_id, blocked_user_id, reason)
            VALUES (#{userId}, #{blockedUserId}, #{reason})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertBlacklist(UserBlacklist userBlacklist);

    @Delete("""
            DELETE FROM user_blacklist
            WHERE user_id = #{userId}
              AND blocked_user_id = #{blockedUserId}
            """)
    int deleteBlacklist(@Param("userId") Long userId, @Param("blockedUserId") Long blockedUserId);
}
