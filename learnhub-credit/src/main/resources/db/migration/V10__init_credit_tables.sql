-- 额度账户：一个用户一行
CREATE TABLE `credit_account`
(
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id    BIGINT UNSIGNED NOT NULL COMMENT '用户 ID',
    balance    DECIMAL(12, 2)  NOT NULL DEFAULT 0.00 COMMENT '余额',
    updated_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_id (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='额度账户表';

-- 额度流水：只增不改，可还原历史
CREATE TABLE `credit_transaction`
(
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id       BIGINT UNSIGNED NOT NULL COMMENT '用户 ID',
    change_amount DECIMAL(12, 2)  NOT NULL COMMENT '变动金额（正=增加，负=扣减）',
    balance_after DECIMAL(12, 2)  NOT NULL COMMENT '变动后余额',
    type          VARCHAR(20)     NOT NULL COMMENT 'GRANT/CONSUME/REFUND',
    biz_no        VARCHAR(64)     NOT NULL COMMENT '业务单号（防重）',
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_no (biz_no),
    KEY idx_user_id (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='额度流水表';