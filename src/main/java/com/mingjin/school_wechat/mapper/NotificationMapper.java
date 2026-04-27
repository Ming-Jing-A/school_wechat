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
import com.mingjin.school_wechat.model.view.ConversationSummaryView;
import com.mingjin.school_wechat.model.view.FriendRequestView;
import com.mingjin.school_wechat.model.view.FriendView;
import com.mingjin.school_wechat.model.view.UserNotificationView;
import com.mingjin.school_wechat.model.view.UserSyncEventView;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface NotificationMapper {

    @Insert("""
            INSERT INTO user_notification (
                user_id, notification_type, title, content, related_type,
                related_id, is_read, read_at, extra_json
            ) VALUES (
                #{userId}, #{notificationType}, #{title}, #{content}, #{relatedType},
                #{relatedId}, #{isRead}, #{readAt}, #{extraJson}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertNotification(UserNotification userNotification);

    @Select("""
            SELECT id, notification_type, title, content, related_type,
                   related_id, is_read, read_at, extra_json, created_at
            FROM user_notification
            WHERE user_id = #{userId}
            ORDER BY created_at DESC, id DESC
            """)
    List<UserNotificationView> findByUserId(@Param("userId") Long userId);

    @Select("""
            SELECT id, notification_type, title, content, related_type,
                   related_id, is_read, read_at, extra_json, created_at
            FROM user_notification
            WHERE id = #{id}
              AND user_id = #{userId}
            LIMIT 1
            """)
    UserNotificationView findById(@Param("id") Long id, @Param("userId") Long userId);

    @Update("""
            UPDATE user_notification
            SET is_read = 1,
                read_at = NOW()
            WHERE id = #{id}
              AND user_id = #{userId}
            """)
    int markAsRead(@Param("id") Long id, @Param("userId") Long userId);

    @Delete("""
            DELETE FROM user_notification
            WHERE user_id = #{userId}
              AND related_type = #{relatedType}
              AND related_id = #{relatedId}
            """)
    int deleteByRelated(@Param("userId") Long userId,
                        @Param("relatedType") String relatedType,
                        @Param("relatedId") Long relatedId);

    @Delete("""
            DELETE FROM user_notification
            WHERE user_id = #{userId}
              AND is_read = 0
            """)
    int clearUnread(@Param("userId") Long userId);

    @Delete("""
            DELETE FROM user_notification
            WHERE user_id = #{userId}
            """)
    int clearAll(@Param("userId") Long userId);

    @Delete("""
            DELETE FROM user_notification
            WHERE id = #{id}
              AND user_id = #{userId}
            """)
    int deleteById(@Param("id") Long id, @Param("userId") Long userId);

    @Delete("""
            DELETE FROM user_notification
            WHERE created_at < #{beforeDate}
            """)
    int deleteOlderThan(@Param("beforeDate") LocalDateTime beforeDate);
}
