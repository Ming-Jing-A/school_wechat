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
public interface AuthMapper {

    @Select("""
            SELECT *
            FROM wechat_user
            WHERE username = #{username}
              AND status = 1
            LIMIT 1
            """)
    WechatUser findUserByUsername(@Param("username") String username);

    @Select("""
            SELECT *
            FROM wechat_user
            WHERE id = #{userId}
            LIMIT 1
            """)
    WechatUser findUserById(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(1)
            FROM wechat_user
            WHERE username = #{username}
            """)
    int countByUsername(@Param("username") String username);

    @Select("""
            SELECT COUNT(1)
            FROM wechat_user
            WHERE wechat_no = #{wechatNo}
            """)
    int countByWechatNo(@Param("wechatNo") String wechatNo);

    @Select("""
            SELECT COUNT(1)
            FROM wechat_user
            WHERE phone = #{phone}
            """)
    int countByPhone(@Param("phone") String phone);

    @Select("""
            SELECT COUNT(1)
            FROM wechat_user
            WHERE email = #{email}
            """)
    int countByEmail(@Param("email") String email);

    @Select("""
            SELECT COUNT(1)
            FROM wechat_user
            WHERE wechat_no = #{wechatNo}
              AND id <> #{userId}
            """)
    int countByWechatNoExcludeUser(@Param("wechatNo") String wechatNo, @Param("userId") Long userId);

    @Select("""
            SELECT COUNT(1)
            FROM wechat_user
            WHERE phone = #{phone}
              AND id <> #{userId}
            """)
    int countByPhoneExcludeUser(@Param("phone") String phone, @Param("userId") Long userId);

    @Select("""
            SELECT COUNT(1)
            FROM wechat_user
            WHERE email = #{email}
              AND id <> #{userId}
            """)
    int countByEmailExcludeUser(@Param("email") String email, @Param("userId") Long userId);

    @Insert("""
            INSERT INTO wechat_user (
                username, password_hash, nickname, wechat_no, phone, email, avatar_url,
                gender, birthday, region, signature, friend_add_policy, status, last_online_at
            ) VALUES (
                #{username}, #{passwordHash}, #{nickname}, #{wechatNo}, #{phone}, #{email}, #{avatarUrl},
                #{gender}, #{birthday}, #{region}, #{signature}, #{friendAddPolicy}, #{status}, #{lastOnlineAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertWechatUser(WechatUser user);

    @Insert("""
            INSERT INTO user_device (
                user_id, device_type, platform, device_name, browser_name, os_name,
                device_identifier, last_login_ip, last_login_at, last_active_at,
                last_sync_seq, is_online, status
            ) VALUES (
                #{userId}, #{deviceType}, #{platform}, #{deviceName}, #{browserName}, #{osName},
                #{deviceIdentifier}, #{lastLoginIp}, #{lastLoginAt}, #{lastActiveAt},
                #{lastSyncSeq}, #{isOnline}, #{status}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertUserDevice(UserDevice userDevice);

    @Insert("""
            INSERT INTO user_login_session (
                user_id, device_id, session_token, refresh_token,
                login_at, expire_at, last_active_at, status
            ) VALUES (
                #{userId}, #{deviceId}, #{sessionToken}, #{refreshToken},
                #{loginAt}, #{expireAt}, #{lastActiveAt}, #{status}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertUserLoginSession(UserLoginSession userLoginSession);

    @Select("""
            SELECT s.id AS session_id,
                   s.user_id,
                   s.device_id,
                   s.session_token,
                   u.nickname,
                   u.avatar_url
            FROM user_login_session s
            JOIN wechat_user u ON u.id = s.user_id
            WHERE s.session_token = #{token}
              AND s.status = 1
              AND s.expire_at > NOW()
            LIMIT 1
            """)
    AuthSession findAuthSessionByToken(@Param("token") String token);

    @Update("""
            UPDATE wechat_user
            SET last_online_at = NOW()
            WHERE id = #{userId}
            """)
    int updateUserOnline(@Param("userId") Long userId);

    @Update("""
            UPDATE user_device
            SET last_active_at = NOW(), is_online = 1
            WHERE id = #{deviceId}
            """)
    int updateDeviceActive(@Param("deviceId") Long deviceId);

    @Update("""
            UPDATE user_device
            SET last_active_at = NOW(), is_online = 1
            WHERE id = #{deviceId}
              AND last_active_at < DATE_SUB(NOW(), INTERVAL 60 SECOND)
            """)
    int updateDeviceActiveThrottled(@Param("deviceId") Long deviceId);

    @Update("""
            UPDATE user_login_session
            SET last_active_at = NOW()
            WHERE id = #{sessionId}
            """)
    int updateSessionActive(@Param("sessionId") Long sessionId);

    @Update("""
            UPDATE user_login_session
            SET last_active_at = NOW()
            WHERE id = #{sessionId}
              AND last_active_at < DATE_SUB(NOW(), INTERVAL 60 SECOND)
            """)
    int updateSessionActiveThrottled(@Param("sessionId") Long sessionId);

    @Update("""
            UPDATE user_login_session
            SET status = 2,
                last_active_at = NOW()
            WHERE session_token = #{token}
              AND status = 1
            """)
    int invalidateSessionByToken(@Param("token") String token);

    @Update("""
            UPDATE user_login_session
            SET status = 2,
                last_active_at = NOW()
            WHERE user_id = #{userId}
              AND status = 1
            """)
    int invalidateAllSessionsByUserId(@Param("userId") Long userId);

    @Update("""
            UPDATE user_device
            SET is_online = 0,
                updated_at = NOW()
            WHERE user_id = #{userId}
              AND is_online = 1
            """)
    int setAllDevicesOfflineByUserId(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(1)
            FROM user_login_session
            WHERE device_id = #{deviceId}
              AND status = 1
              AND expire_at > NOW()
            """)
    int countActiveSessionsByDevice(@Param("deviceId") Long deviceId);

    @Update("""
            UPDATE user_device
            SET is_online = #{isOnline},
                last_active_at = NOW(),
                updated_at = NOW()
            WHERE id = #{deviceId}
            """)
    int updateDeviceOnlineStatus(@Param("deviceId") Long deviceId, @Param("isOnline") Integer isOnline);

    @Update("""
            UPDATE wechat_user
            SET password_hash = #{passwordHash},
                updated_at = NOW()
            WHERE id = #{userId}
            """)
    int updatePasswordHash(@Param("userId") Long userId, @Param("passwordHash") String passwordHash);

    @Update("""
            UPDATE wechat_user
            SET nickname = #{nickname},
                wechat_no = #{wechatNo},
                phone = #{phone},
                email = #{email},
                avatar_url = #{avatarUrl},
                gender = #{gender},
                birthday = #{birthday},
                region = #{region},
                signature = #{signature},
                friend_add_policy = #{friendAddPolicy},
                updated_at = NOW()
            WHERE id = #{id}
            """)
    int updateUserProfile(WechatUser user);
}
