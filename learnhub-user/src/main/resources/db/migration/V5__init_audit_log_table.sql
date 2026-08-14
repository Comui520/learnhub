CREATE TABLE `audit_log`
(
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    username   VARCHAR(30)     NOT NULL COMMENT '操作人',
    action     VARCHAR(50)     NOT NULL COMMENT '动作，如 LOGIN_SUCCESS / LOGIN_FAILED',
    ip         VARCHAR(45)     NULL COMMENT '来源 IP',
    detail     VARCHAR(500)    NULL COMMENT '补充信息',
    created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_audit_username (username),
    KEY idx_audit_created_at (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='审计日志表';