-- 登录限制配置表（全局只有一条记录）
CREATE TABLE IF NOT EXISTS `login_restriction_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `mode` varchar(20) NOT NULL DEFAULT 'open' COMMENT 'open=全部允许登录, closed=全部禁止登录, restricted=仅白名单用户可登录',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='登录限制全局配置';

-- 初始化默认配置：开放模式
INSERT INTO `login_restriction_config` (`mode`) VALUES ('open');

-- 登录白名单用户表
CREATE TABLE IF NOT EXISTS `login_allowed_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '添加时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_login_allowed_user_user_id` (`user_id`),
  CONSTRAINT `fk_login_allowed_user_user` FOREIGN KEY (`user_id`) REFERENCES `wechat_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='登录白名单用户';
