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
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SyncMapper {

    @Select("""
            SELECT GET_LOCK(CONCAT('sync_seq_', #{userId}), 5)
            """)
    Integer acquireUserSyncLock(@Param("userId") Long userId);

    @Select("""
            SELECT RELEASE_LOCK(CONCAT('sync_seq_', #{userId}))
            """)
    Integer releaseUserSyncLock(@Param("userId") Long userId);

    @Select("""
            SELECT sync_seq
            FROM user_sync_event
            WHERE user_id = #{userId}
            ORDER BY sync_seq DESC
            LIMIT 1
            """)
    Long findLatestSyncSeq(@Param("userId") Long userId);

    @Insert("""
            INSERT INTO user_sync_event (
                user_id, source_device_id, sync_seq, event_type,
                action_type, related_type, related_id, event_payload
            ) VALUES (
                #{userId}, #{sourceDeviceId}, #{syncSeq}, #{eventType},
                #{actionType}, #{relatedType}, #{relatedId}, #{eventPayload}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertSyncEvent(UserSyncEvent userSyncEvent);

    @Select("""
            SELECT id, sync_seq, event_type, action_type,
                   related_type, related_id, event_payload, created_at
            FROM user_sync_event
            WHERE user_id = #{userId}
              AND sync_seq > #{fromSeq}
            ORDER BY sync_seq ASC
            LIMIT #{limit}
            """)
    List<UserSyncEventView> findEventsAfter(@Param("userId") Long userId,
                                            @Param("fromSeq") Long fromSeq,
                                            @Param("limit") Integer limit);

    @Select("""
            SELECT id, sync_seq, event_type, action_type,
                   related_type, related_id, event_payload, created_at
            FROM user_sync_event
            WHERE user_id = #{userId}
              AND id = #{id}
            LIMIT 1
            """)
    UserSyncEventView findById(@Param("userId") Long userId, @Param("id") Long id);

    @Update("""
            UPDATE user_device
            SET last_sync_seq = #{lastSyncSeq},
                last_active_at = NOW()
            WHERE id = #{deviceId}
            """)
    int updateDeviceLastSyncSeq(@Param("deviceId") Long deviceId, @Param("lastSyncSeq") Long lastSyncSeq);
}
