-- ============================================================
-- auth-service 初始化脚本 V1.0（冻结：详细设计基线）
-- database: seckill_auth
-- ============================================================
CREATE DATABASE IF NOT EXISTS `seckill_auth`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE `seckill_auth`;

CREATE TABLE IF NOT EXISTS `user` (
    `id`            BIGINT       NOT NULL COMMENT '用户ID（Snowflake）',
    `username`      VARCHAR(64)  NOT NULL COMMENT '登录名',
    `password_hash` VARCHAR(128) NOT NULL COMMENT '密码哈希（BCrypt）',
    `phone`         VARCHAR(20)  DEFAULT NULL COMMENT '手机号（展示脱敏）',
    `nickname`      VARCHAR(64)  DEFAULT NULL COMMENT '昵称',
    `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常 0禁用',
    `roles`         VARCHAR(64)  NOT NULL DEFAULT 'USER' COMMENT '角色（逗号分隔）：USER / USER,ADMIN',
    `created_at`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '软删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    KEY `idx_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户表';

CREATE TABLE IF NOT EXISTS `user_auth` (
    `id`         BIGINT       NOT NULL COMMENT '主键（Snowflake）',
    `user_id`    BIGINT       NOT NULL COMMENT '用户ID',
    `auth_type`  VARCHAR(20)  NOT NULL COMMENT '认证方式：PASSWORD/SMS/THIRD_PARTY',
    `credential` VARCHAR(256) NOT NULL COMMENT '凭据（密码哈希/短信凭据/第三方openId）',
    `expire_at`  DATETIME(3)  DEFAULT NULL COMMENT '凭据过期时间',
    `created_at` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `deleted`    TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_auth` (`user_id`, `auth_type`),
    KEY `idx_credential` (`credential`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户认证表';

CREATE TABLE IF NOT EXISTS `risk_blacklist` (
    `id`          BIGINT       NOT NULL COMMENT '主键（Snowflake）',
    `biz_type`    VARCHAR(20)  NOT NULL COMMENT '黑名单类型：USER/IP/DEVICE',
    `biz_value`   VARCHAR(128) NOT NULL COMMENT '用户ID/IP/设备指纹',
    `reason`      VARCHAR(256) DEFAULT NULL COMMENT '拉黑原因',
    `operator_id` BIGINT       DEFAULT NULL COMMENT '操作人',
    `expire_at`   DATETIME(3)  DEFAULT NULL COMMENT '过期时间，NULL为长期',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '1生效 0失效',
    `created_at`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `deleted`     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_blacklist` (`biz_type`, `biz_value`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='风险控制黑名单表';

CREATE TABLE IF NOT EXISTS `risk_record` (
    `id`                 BIGINT      NOT NULL COMMENT '主键（Snowflake）',
    `user_id`            BIGINT      DEFAULT NULL COMMENT '用户ID',
    `ip`                 VARCHAR(64) DEFAULT NULL COMMENT '来源IP',
    `device_fingerprint` VARCHAR(128) DEFAULT NULL COMMENT '设备指纹（预留）',
    `action`             VARCHAR(64) NOT NULL COMMENT '风控动作：LOGIN/SECKILL/PAY',
    `risk_score`         INT         DEFAULT NULL COMMENT '风险分值',
    `decision`           VARCHAR(20) NOT NULL COMMENT '决策：PASS/REJECT/CAPTCHA',
    `created_at`         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_user_created` (`user_id`, `created_at`),
    KEY `idx_ip_created` (`ip`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='风控记录表（流水，只增）';
