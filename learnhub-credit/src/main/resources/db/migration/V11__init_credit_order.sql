CREATE TABLE `credit_order`
(
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_no   VARCHAR(64)     NOT NULL COMMENT '订单号（对外唯一）',
    user_id    BIGINT UNSIGNED NOT NULL COMMENT '用户 ID',
    amount     DECIMAL(12, 2)  NOT NULL COMMENT '金额',
    status     VARCHAR(20)     NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/PAID/CLOSED',
    paid_at    DATETIME        NULL COMMENT '支付时间',
    expire_at  DATETIME        NOT NULL COMMENT '过期时间',
    created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_user_id (user_id),
    KEY idx_status_expire (status, expire_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='额度订单表';