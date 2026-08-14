CREATE TABLE `user`
(
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    username      VARCHAR(30)     NOT NULL COMMENT '登录名',
    password_hash VARCHAR(100)    NOT NULL COMMENT 'BCrypt 哈希后的密码',
    status        TINYINT         NOT NULL DEFAULT 1 COMMENT '账号状态：1 正常，0 禁用',
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='用户表';