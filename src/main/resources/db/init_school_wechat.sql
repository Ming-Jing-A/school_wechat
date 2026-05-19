-- MySQL dump 10.13  Distrib 8.4.7, for macos15 (x86_64)
--
-- Host: localhost    Database: school_wechat
-- ------------------------------------------------------
-- Server version	8.4.7

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Current Database: `school_wechat`
--

CREATE DATABASE /*!32312 IF NOT EXISTS*/ `school_wechat` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci */ /*!80016 DEFAULT ENCRYPTION='N' */;

USE `school_wechat`;

--
-- Table structure for table `chat_message`
--

DROP TABLE IF EXISTS `chat_message`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_message` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '消息ID',
  `conversation_id` bigint NOT NULL COMMENT '会话ID',
  `sender_user_id` bigint NOT NULL COMMENT '发送人ID',
  `message_type` varchar(20) COLLATE utf8mb4_general_ci NOT NULL COMMENT 'text image file voice video system revoke',
  `message_status` varchar(20) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'sent' COMMENT 'sending sent failed recalled deleted',
  `content` text COLLATE utf8mb4_general_ci COMMENT '文本消息内容',
  `content_json` text COLLATE utf8mb4_general_ci COMMENT '结构化扩展内容',
  `client_message_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '客户端消息ID',
  `quote_message_id` bigint DEFAULT NULL COMMENT '引用消息ID',
  `forward_from_message_id` bigint DEFAULT NULL COMMENT '转发来源消息ID',
  `is_recalled` tinyint NOT NULL DEFAULT '0' COMMENT '是否已撤回',
  `recall_at` datetime DEFAULT NULL COMMENT '撤回时间',
  `sent_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chat_message_client_message_id` (`client_message_id`),
  KEY `idx_chat_message_conversation_id` (`conversation_id`),
  KEY `idx_chat_message_sender_user_id` (`sender_user_id`),
  KEY `idx_chat_message_sent_at` (`sent_at`),
  KEY `fk_chat_message_quote_message` (`quote_message_id`),
  KEY `fk_chat_message_forward_message` (`forward_from_message_id`),
  CONSTRAINT `fk_chat_message_conversation` FOREIGN KEY (`conversation_id`) REFERENCES `conversation` (`id`),
  CONSTRAINT `fk_chat_message_forward_message` FOREIGN KEY (`forward_from_message_id`) REFERENCES `chat_message` (`id`),
  CONSTRAINT `fk_chat_message_quote_message` FOREIGN KEY (`quote_message_id`) REFERENCES `chat_message` (`id`),
  CONSTRAINT `fk_chat_message_sender_user` FOREIGN KEY (`sender_user_id`) REFERENCES `wechat_user` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=349 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='聊天消息表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `chat_message_attachment`
--

DROP TABLE IF EXISTS `chat_message_attachment`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_message_attachment` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '消息附件ID',
  `message_id` bigint NOT NULL COMMENT '消息ID',
  `file_id` bigint NOT NULL COMMENT '文件资源ID',
  `sort_order` int NOT NULL DEFAULT '0' COMMENT '排序',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_chat_message_attachment_message_id` (`message_id`),
  KEY `idx_chat_message_attachment_file_id` (`file_id`),
  CONSTRAINT `fk_chat_message_attachment_file` FOREIGN KEY (`file_id`) REFERENCES `file_resource` (`id`),
  CONSTRAINT `fk_chat_message_attachment_message` FOREIGN KEY (`message_id`) REFERENCES `chat_message` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=37 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='消息附件关联表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `conversation`
--

DROP TABLE IF EXISTS `conversation`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `conversation` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '会话ID',
  `conversation_type` varchar(20) COLLATE utf8mb4_general_ci NOT NULL COMMENT 'single group',
  `name` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '群聊名称或会话显示名',
  `avatar_url` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '会话头像',
  `owner_user_id` bigint DEFAULT NULL COMMENT '群主用户ID',
  `description_text` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '会话描述',
  `announcement` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '群公告',
  `join_rule` varchar(20) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'direct' COMMENT 'direct approval invite_only',
  `max_member_count` int NOT NULL DEFAULT '500' COMMENT '最大成员数',
  `mute_all` tinyint NOT NULL DEFAULT '0' COMMENT '是否全员禁言',
  `last_message_id` bigint DEFAULT NULL COMMENT '最后消息ID',
  `last_message_type` varchar(20) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '最后消息类型',
  `last_message_content` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '最后消息摘要',
  `last_sender_id` bigint DEFAULT NULL COMMENT '最后发送人ID',
  `last_message_at` datetime DEFAULT NULL COMMENT '最后消息时间',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1正常 2已解散',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_conversation_owner_user_id` (`owner_user_id`),
  KEY `idx_conversation_last_message_at` (`last_message_at`),
  KEY `idx_conversation_last_sender_id` (`last_sender_id`),
  CONSTRAINT `fk_conversation_last_sender` FOREIGN KEY (`last_sender_id`) REFERENCES `wechat_user` (`id`),
  CONSTRAINT `fk_conversation_owner_user` FOREIGN KEY (`owner_user_id`) REFERENCES `wechat_user` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=39 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='会话表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `conversation_join_request`
--

DROP TABLE IF EXISTS `conversation_join_request`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `conversation_join_request` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '入群申请ID',
  `conversation_id` bigint NOT NULL COMMENT '会话ID',
  `applicant_user_id` bigint NOT NULL COMMENT '申请人ID',
  `inviter_user_id` bigint DEFAULT NULL COMMENT '邀请人ID',
  `request_message` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '申请信息',
  `status` varchar(20) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'pending' COMMENT 'pending accepted rejected',
  `handled_by` bigint DEFAULT NULL COMMENT '处理人ID',
  `handled_at` datetime DEFAULT NULL COMMENT '处理时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_conversation_join_request_conversation_id` (`conversation_id`),
  KEY `idx_conversation_join_request_applicant_user_id` (`applicant_user_id`),
  KEY `fk_conversation_join_request_inviter` (`inviter_user_id`),
  KEY `fk_conversation_join_request_handled_by` (`handled_by`),
  CONSTRAINT `fk_conversation_join_request_applicant` FOREIGN KEY (`applicant_user_id`) REFERENCES `wechat_user` (`id`),
  CONSTRAINT `fk_conversation_join_request_conversation` FOREIGN KEY (`conversation_id`) REFERENCES `conversation` (`id`),
  CONSTRAINT `fk_conversation_join_request_handled_by` FOREIGN KEY (`handled_by`) REFERENCES `wechat_user` (`id`),
  CONSTRAINT `fk_conversation_join_request_inviter` FOREIGN KEY (`inviter_user_id`) REFERENCES `wechat_user` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=14 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='入群申请表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `conversation_member`
--

DROP TABLE IF EXISTS `conversation_member`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `conversation_member` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '会话成员ID',
  `conversation_id` bigint NOT NULL COMMENT '会话ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `member_role` varchar(20) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'member' COMMENT 'owner admin member',
  `display_name` varchar(50) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '群内昵称',
  `join_source` varchar(30) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'direct' COMMENT 'direct invite apply',
  `inviter_user_id` bigint DEFAULT NULL COMMENT '邀请人ID',
  `is_muted` tinyint NOT NULL DEFAULT '0' COMMENT '是否被单独禁言',
  `mute_until` datetime DEFAULT NULL COMMENT '禁言截止时间',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1在群 2已退出 3已移除',
  `joined_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conversation_member` (`conversation_id`,`user_id`),
  KEY `idx_conversation_member_user_id` (`user_id`),
  KEY `idx_conversation_member_inviter_user_id` (`inviter_user_id`),
  CONSTRAINT `fk_conversation_member_conversation` FOREIGN KEY (`conversation_id`) REFERENCES `conversation` (`id`),
  CONSTRAINT `fk_conversation_member_inviter` FOREIGN KEY (`inviter_user_id`) REFERENCES `wechat_user` (`id`),
  CONSTRAINT `fk_conversation_member_user` FOREIGN KEY (`user_id`) REFERENCES `wechat_user` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=86 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='会话成员表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `conversation_user_setting`
--

DROP TABLE IF EXISTS `conversation_user_setting`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `conversation_user_setting` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '用户会话设置ID',
  `conversation_id` bigint NOT NULL COMMENT '会话ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `is_top` tinyint NOT NULL DEFAULT '0' COMMENT '是否置顶',
  `is_muted` tinyint NOT NULL DEFAULT '0' COMMENT '是否免打扰',
  `is_hidden` tinyint NOT NULL DEFAULT '0' COMMENT '是否隐藏会话',
  `unread_count` int NOT NULL DEFAULT '0' COMMENT '未读数',
  `draft_content` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '草稿',
  `last_read_message_id` bigint DEFAULT NULL COMMENT '最后已读消息ID',
  `last_read_at` datetime DEFAULT NULL COMMENT '最后已读时间',
  `clear_message_before` datetime DEFAULT NULL COMMENT '清空聊天记录时间点',
  `remark` varchar(50) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '群聊备注',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conversation_user_setting` (`conversation_id`,`user_id`),
  KEY `idx_conversation_user_setting_user_id` (`user_id`),
  CONSTRAINT `fk_conversation_user_setting_conversation` FOREIGN KEY (`conversation_id`) REFERENCES `conversation` (`id`),
  CONSTRAINT `fk_conversation_user_setting_user` FOREIGN KEY (`user_id`) REFERENCES `wechat_user` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=88 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户会话设置表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `file_resource`
--

DROP TABLE IF EXISTS `file_resource`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `file_resource` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '文件资源ID',
  `uploader_user_id` bigint NOT NULL COMMENT '上传用户ID',
  `storage_type` varchar(20) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'local' COMMENT 'local oss s3 minio',
  `bucket_name` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '存储桶',
  `file_key` varchar(255) COLLATE utf8mb4_general_ci NOT NULL COMMENT '文件存储Key',
  `file_name` varchar(255) COLLATE utf8mb4_general_ci NOT NULL COMMENT '原始文件名',
  `file_ext` varchar(20) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '文件扩展名',
  `mime_type` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'MIME类型',
  `file_size` bigint NOT NULL DEFAULT '0' COMMENT '文件大小',
  `checksum` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '校验值',
  `file_url` varchar(255) COLLATE utf8mb4_general_ci NOT NULL COMMENT '访问地址',
  `thumbnail_url` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '缩略图地址',
  `width` int DEFAULT NULL COMMENT '图片或视频宽度',
  `height` int DEFAULT NULL COMMENT '图片或视频高度',
  `duration_seconds` int DEFAULT NULL COMMENT '时长秒数',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_file_resource_file_key` (`file_key`),
  KEY `idx_file_resource_uploader_user_id` (`uploader_user_id`),
  CONSTRAINT `fk_file_resource_uploader` FOREIGN KEY (`uploader_user_id`) REFERENCES `wechat_user` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=47 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='文件资源表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `friend_group`
--

DROP TABLE IF EXISTS `friend_group`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `friend_group` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '好友分组ID',
  `user_id` bigint NOT NULL COMMENT '所属用户ID',
  `group_name` varchar(50) COLLATE utf8mb4_general_ci NOT NULL COMMENT '分组名',
  `sort_order` int NOT NULL DEFAULT '0' COMMENT '排序值',
  `is_default` tinyint NOT NULL DEFAULT '0' COMMENT '是否默认分组',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_friend_group_user_name` (`user_id`,`group_name`),
  KEY `idx_friend_group_user_id` (`user_id`),
  CONSTRAINT `fk_friend_group_user` FOREIGN KEY (`user_id`) REFERENCES `wechat_user` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='好友分组表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `friend_request`
--

DROP TABLE IF EXISTS `friend_request`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `friend_request` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '好友申请ID',
  `from_user_id` bigint NOT NULL COMMENT '申请发起人',
  `to_user_id` bigint NOT NULL COMMENT '申请接收人',
  `request_message` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '申请留言',
  `source` varchar(30) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'search' COMMENT '来源 search qrcode group card',
  `status` varchar(20) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'pending' COMMENT 'pending accepted rejected ignored',
  `handled_by` bigint DEFAULT NULL COMMENT '处理人',
  `handled_at` datetime DEFAULT NULL COMMENT '处理时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_friend_request_from_user` (`from_user_id`),
  KEY `idx_friend_request_to_user` (`to_user_id`),
  KEY `idx_friend_request_status` (`status`),
  KEY `fk_friend_request_handled_by` (`handled_by`),
  CONSTRAINT `fk_friend_request_from_user` FOREIGN KEY (`from_user_id`) REFERENCES `wechat_user` (`id`),
  CONSTRAINT `fk_friend_request_handled_by` FOREIGN KEY (`handled_by`) REFERENCES `wechat_user` (`id`),
  CONSTRAINT `fk_friend_request_to_user` FOREIGN KEY (`to_user_id`) REFERENCES `wechat_user` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=50 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='好友申请表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `friendship`
--

DROP TABLE IF EXISTS `friendship`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `friendship` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '好友关系ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `friend_user_id` bigint NOT NULL COMMENT '好友用户ID',
  `friend_group_id` bigint DEFAULT NULL COMMENT '好友分组ID',
  `source_request_id` bigint DEFAULT NULL COMMENT '来源申请ID',
  `remark_name` varchar(50) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '好友备注',
  `is_starred` tinyint NOT NULL DEFAULT '0' COMMENT '是否星标好友',
  `is_muted` tinyint NOT NULL DEFAULT '0' COMMENT '是否消息免打扰',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1正常 2已删除',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_friendship_pair` (`user_id`,`friend_user_id`),
  KEY `idx_friendship_friend_user` (`friend_user_id`),
  KEY `idx_friendship_group_id` (`friend_group_id`),
  KEY `fk_friendship_request` (`source_request_id`),
  CONSTRAINT `fk_friendship_friend_user` FOREIGN KEY (`friend_user_id`) REFERENCES `wechat_user` (`id`),
  CONSTRAINT `fk_friendship_group` FOREIGN KEY (`friend_group_id`) REFERENCES `friend_group` (`id`),
  CONSTRAINT `fk_friendship_request` FOREIGN KEY (`source_request_id`) REFERENCES `friend_request` (`id`),
  CONSTRAINT `fk_friendship_user` FOREIGN KEY (`user_id`) REFERENCES `wechat_user` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=53 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='好友关系表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `message_user_status`
--

DROP TABLE IF EXISTS `message_user_status`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `message_user_status` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '消息用户状态ID',
  `message_id` bigint NOT NULL COMMENT '消息ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `delivered_at` datetime DEFAULT NULL COMMENT '送达时间',
  `read_at` datetime DEFAULT NULL COMMENT '已读时间',
  `is_deleted_for_me` tinyint NOT NULL DEFAULT '0' COMMENT '是否仅对自己删除',
  `is_mentioned` tinyint NOT NULL DEFAULT '0' COMMENT '是否被@',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_message_user_status` (`message_id`,`user_id`),
  KEY `idx_message_user_status_user_id` (`user_id`),
  CONSTRAINT `fk_message_user_status_message` FOREIGN KEY (`message_id`) REFERENCES `chat_message` (`id`),
  CONSTRAINT `fk_message_user_status_user` FOREIGN KEY (`user_id`) REFERENCES `wechat_user` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=795 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='消息用户状态表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `user_blacklist`
--

DROP TABLE IF EXISTS `user_blacklist`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_blacklist` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '拉黑记录ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `blocked_user_id` bigint NOT NULL COMMENT '被拉黑用户ID',
  `reason` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '拉黑原因',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_blacklist_pair` (`user_id`,`blocked_user_id`),
  KEY `idx_user_blacklist_blocked_user_id` (`blocked_user_id`),
  CONSTRAINT `fk_user_blacklist_blocked_user` FOREIGN KEY (`blocked_user_id`) REFERENCES `wechat_user` (`id`),
  CONSTRAINT `fk_user_blacklist_user` FOREIGN KEY (`user_id`) REFERENCES `wechat_user` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户黑名单表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `user_device`
--

DROP TABLE IF EXISTS `user_device`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_device` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '设备ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `device_type` varchar(20) COLLATE utf8mb4_general_ci NOT NULL COMMENT 'web mobile desktop',
  `platform` varchar(30) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '平台',
  `device_name` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '设备名',
  `browser_name` varchar(50) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '浏览器',
  `os_name` varchar(50) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '操作系统',
  `device_identifier` varchar(100) COLLATE utf8mb4_general_ci NOT NULL COMMENT '设备唯一标识',
  `last_login_ip` varchar(45) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '最近登录IP',
  `last_login_at` datetime DEFAULT NULL COMMENT '最近登录时间',
  `last_active_at` datetime DEFAULT NULL COMMENT '最近活跃时间',
  `last_sync_seq` bigint NOT NULL DEFAULT '0' COMMENT '最近同步序号',
  `is_online` tinyint NOT NULL DEFAULT '0' COMMENT '是否在线',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1正常 2下线 3禁用',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_device_identifier` (`device_identifier`),
  KEY `idx_user_device_user_id` (`user_id`),
  CONSTRAINT `fk_user_device_user` FOREIGN KEY (`user_id`) REFERENCES `wechat_user` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=246 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户设备表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `user_login_session`
--

DROP TABLE IF EXISTS `user_login_session`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_login_session` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '登录会话ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `device_id` bigint NOT NULL COMMENT '设备ID',
  `session_token` varchar(128) COLLATE utf8mb4_general_ci NOT NULL COMMENT '登录令牌',
  `refresh_token` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '刷新令牌',
  `login_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登录时间',
  `expire_at` datetime NOT NULL COMMENT '过期时间',
  `last_active_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最后活跃时间',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1有效 2失效',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session_token` (`session_token`),
  UNIQUE KEY `uk_refresh_token` (`refresh_token`),
  KEY `idx_login_session_user_id` (`user_id`),
  KEY `idx_login_session_device_id` (`device_id`),
  CONSTRAINT `fk_login_session_device` FOREIGN KEY (`device_id`) REFERENCES `user_device` (`id`),
  CONSTRAINT `fk_login_session_user` FOREIGN KEY (`user_id`) REFERENCES `wechat_user` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=246 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户登录会话表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `user_notification`
--

DROP TABLE IF EXISTS `user_notification`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_notification` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '通知ID',
  `user_id` bigint NOT NULL COMMENT '接收用户ID',
  `notification_type` varchar(30) COLLATE utf8mb4_general_ci NOT NULL COMMENT 'friend_request group_invite system mention message',
  `title` varchar(100) COLLATE utf8mb4_general_ci NOT NULL COMMENT '标题',
  `content` varchar(500) COLLATE utf8mb4_general_ci NOT NULL COMMENT '内容',
  `related_type` varchar(30) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '关联类型',
  `related_id` bigint DEFAULT NULL COMMENT '关联ID',
  `is_read` tinyint NOT NULL DEFAULT '0' COMMENT '是否已读',
  `read_at` datetime DEFAULT NULL COMMENT '已读时间',
  `extra_json` text COLLATE utf8mb4_general_ci COMMENT '扩展数据',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_notification_user_id` (`user_id`),
  KEY `idx_user_notification_is_read` (`is_read`),
  CONSTRAINT `fk_user_notification_user` FOREIGN KEY (`user_id`) REFERENCES `wechat_user` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=130 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户通知表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `user_sync_event`
--

DROP TABLE IF EXISTS `user_sync_event`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_sync_event` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '同步事件ID',
  `user_id` bigint NOT NULL COMMENT '所属用户ID',
  `source_device_id` bigint DEFAULT NULL COMMENT '来源设备ID',
  `sync_seq` bigint NOT NULL COMMENT '用户内递增同步序号',
  `event_type` varchar(30) COLLATE utf8mb4_general_ci NOT NULL COMMENT 'friendship conversation message notification setting',
  `action_type` varchar(30) COLLATE utf8mb4_general_ci NOT NULL COMMENT 'create update delete read',
  `related_type` varchar(30) COLLATE utf8mb4_general_ci NOT NULL COMMENT '关联数据类型',
  `related_id` bigint NOT NULL COMMENT '关联数据ID',
  `event_payload` text COLLATE utf8mb4_general_ci COMMENT '事件载荷',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_sync_event_seq` (`user_id`,`sync_seq`),
  KEY `idx_user_sync_event_device_id` (`source_device_id`),
  KEY `idx_user_sync_event_created_at` (`created_at`),
  CONSTRAINT `fk_user_sync_event_device` FOREIGN KEY (`source_device_id`) REFERENCES `user_device` (`id`),
  CONSTRAINT `fk_user_sync_event_user` FOREIGN KEY (`user_id`) REFERENCES `wechat_user` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=10373499 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户设备同步事件表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `wechat_user`
--

DROP TABLE IF EXISTS `wechat_user`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wechat_user` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `username` varchar(50) COLLATE utf8mb4_general_ci NOT NULL COMMENT '登录用户名',
  `password_hash` varchar(255) COLLATE utf8mb4_general_ci NOT NULL COMMENT '密码哈希',
  `nickname` varchar(50) COLLATE utf8mb4_general_ci NOT NULL COMMENT '昵称',
  `wechat_no` varchar(50) COLLATE utf8mb4_general_ci NOT NULL COMMENT '微信号',
  `phone` varchar(20) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '手机号',
  `email` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '邮箱',
  `avatar_url` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '头像地址',
  `gender` tinyint NOT NULL DEFAULT '0' COMMENT '0未知 1男 2女',
  `birthday` date DEFAULT NULL COMMENT '生日',
  `region` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '地区',
  `signature` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '个性签名',
  `friend_add_policy` varchar(20) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'need_confirm' COMMENT 'need_confirm direct deny',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1正常 2禁用',
  `last_online_at` datetime DEFAULT NULL COMMENT '最后在线时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wechat_user_username` (`username`),
  UNIQUE KEY `uk_wechat_user_wechat_no` (`wechat_no`),
  UNIQUE KEY `uk_wechat_user_phone` (`phone`),
  UNIQUE KEY `uk_wechat_user_email` (`email`)
) ENGINE=InnoDB AUTO_INCREMENT=28 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping routines for database 'school_wechat'
--
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-04-22 14:16:04
