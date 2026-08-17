CREATE TABLE `knowledge_base`
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id     BIGINT UNSIGNED NOT NULL COMMENT '所属用户 ID',
    name        VARCHAR(50)     NOT NULL COMMENT '知识库名称',
    description VARCHAR(255)    NULL COMMENT '描述',
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_name (user_id, name),
    KEY idx_user_id (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='知识库表';

CREATE TABLE `document`
(
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    knowledge_base_id BIGINT UNSIGNED NOT NULL COMMENT '所属知识库 ID',
    user_id           BIGINT UNSIGNED NOT NULL COMMENT '所属用户 ID',
    file_name         VARCHAR(255)    NOT NULL COMMENT '原始文件名',
    file_size         BIGINT          NOT NULL COMMENT '文件大小（字节）',
    content_type      VARCHAR(100)    NULL COMMENT 'MIME 类型',
    sha256            CHAR(64)        NOT NULL COMMENT '文件 SHA-256',
    object_name       VARCHAR(500)    NOT NULL COMMENT 'MinIO 对象名',
    status            VARCHAR(20)     NOT NULL DEFAULT 'UPLOADED' COMMENT 'UPLOADED/PARSING/EMBEDDING/COMPLETED/FAILED',
    created_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_sha256 (knowledge_base_id, sha256),
    KEY idx_knowledge_base_id (knowledge_base_id),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='文档表';

CREATE TABLE `document_task`
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    document_id BIGINT UNSIGNED NOT NULL COMMENT '文档 ID',
    type        VARCHAR(30)     NOT NULL DEFAULT 'PARSE' COMMENT '任务类型',
    status      VARCHAR(20)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/FAILED',
    retry_count INT             NOT NULL DEFAULT 0 COMMENT '重试次数',
    last_error  VARCHAR(500)    NULL COMMENT '最近一次错误',
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_document_id (document_id),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='文档解析任务表';