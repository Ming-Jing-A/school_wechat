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
public interface FileMapper {

    @Insert("""
            INSERT INTO file_resource (
                uploader_user_id, storage_type, bucket_name, file_key, file_name,
                file_ext, mime_type, file_size, checksum, file_url,
                thumbnail_url, width, height, duration_seconds
            ) VALUES (
                #{uploaderUserId}, #{storageType}, #{bucketName}, #{fileKey}, #{fileName},
                #{fileExt}, #{mimeType}, #{fileSize}, #{checksum}, #{fileUrl},
                #{thumbnailUrl}, #{width}, #{height}, #{durationSeconds}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertFileResource(FileResource fileResource);

    @Select("""
            SELECT *
            FROM file_resource
            WHERE id = #{fileId}
            LIMIT 1
            """)
    FileResource findById(@Param("fileId") Long fileId);
}
