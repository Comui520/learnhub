-- V7: document 拆分为 document_file（文件本体）+ knowledge_base_document（多对多关联）

CREATE TABLE `document_file`
(
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id      BIGINT UNSIGNED NOT NULL COMMENT '属主用户 ID',
    sha256       CHAR(64)        NOT NULL COMMENT '文件 SHA-256',
    object_name  VARCHAR(500)    NOT NULL COMMENT 'MinIO 对象名',
    file_name    VARCHAR(255)    NOT NULL COMMENT '原始文件名（首次上传时的名字）',
    file_size    BIGINT          NOT NULL COMMENT '文件大小（字节）',
    content_type VARCHAR(100)    NULL COMMENT 'MIME 类型',
    status       VARCHAR(20)     NOT NULL DEFAULT 'UPLOADED' COMMENT 'UPLOADED/PARSING/EMBEDDING/COMPLETED/FAILED',
    created_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_sha256 (user_id, sha256),
    KEY idx_user_id (user_id),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='文件表';

CREATE TABLE `knowledge_base_document`
(
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    knowledge_base_id BIGINT UNSIGNED NOT NULL COMMENT '知识库 ID',
    file_id           BIGINT UNSIGNED NOT NULL COMMENT '文件 ID',
    created_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_file (knowledge_base_id, file_id),
    KEY idx_knowledge_base_id (knowledge_base_id),
    KEY idx_file_id (file_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='知识库-文件关联表';

-- 老 document 数据搬进 document_file：同一用户同一 sha256 只保留一行
INSERT INTO `document_file`
    (user_id, sha256, object_name, file_name, file_size, content_type, status, created_at, updated_at)
SELECT user_id, sha256, MAX(object_name), MAX(file_name), MAX(file_size), MAX(content_type),
       MAX(status), MIN(created_at), MAX(updated_at)
FROM `document`
GROUP BY user_id, sha256;

-- 老 document 每条记录变成一条关联
INSERT INTO `knowledge_base_document` (knowledge_base_id, file_id, created_at)
SELECT d.knowledge_base_id, f.id, d.created_at
FROM `document` d
         JOIN `document_file` f ON f.user_id = d.user_id AND f.sha256 = d.sha256;

-- 解析任务改为关联文件 ID（Part 4 使用）
ALTER TABLE `document_task`
    CHANGE COLUMN `document_id` `file_id` BIGINT UNSIGNED NOT NULL COMMENT '文件 ID';

DROP TABLE `document`;
