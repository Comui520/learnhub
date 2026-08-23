# Session D（重构）：document 拆分为 document_file + knowledge_base_document

> 日期：2026-08-18。用户已掌握 Part 3 的基础 CRUD，本次由助手代工完成数据模型重构。
> 目的：把“文件本体”和“文件-知识库归属”拆开，消除删除时的死链风险，顺便把多对多关系表练熟。

## 1. 为什么拆：老模型的问题

老模型只有一张 `document` 表，一行同时承担两个职责：

- “文件本体”：sha256、object_name、status（解析状态机）。
- “归属关系”：knowledge_base_id（这个文件属于哪个知识库）。

而 MinIO 对象名是 `documents/{userId}/{sha256}/{文件名}`，**不含 knowledge_base_id**——同一个用户把同一个文件传到两个知识库时，两条 document 记录指向同一个对象。

结果：删除一条记录时，对象可能还被另一条记录引用；如果无条件删对象，另一条就变死链。

纠结的根源：**一张表想同时当“文件表”和“关联表”**。解决方案就是拆开：

```text
document_file（文件本体，一份物理文件一条）
   ↑ 1   n ↓
knowledge_base_document（知识库 ↔ 文件 的多对多关联）
```

语义变得干净：

- 一个知识库可以拥有很多文件；一个文件可以进多个知识库（多对多）。
- 删除“库里的文档” = 删关联记录，永远成功，不被别的库拦住。
- 物理文件（document_file + MinIO 对象）只在**没有任何关联引用**时删除。
- 解析状态机挂在 document_file 上——同一个文件不管进几个库，解析结果都一样。

## 2. 最终表结构（V6 → V7 之后）

### document_file（文件本体）

```sql
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
```

唯一键 `uk_user_sha256 (user_id, sha256)`：同一用户同一内容只存一份。

### knowledge_base_document（多对多关联）

```sql
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
```

唯一键 `uk_kb_file (knowledge_base_id, file_id)`：同一个知识库里同一个文件只能关联一次（数据库层去重兜底）。

### document_task 同步改列名

解析任务是“文件级”的（不管文件进几个库，只解析一次），所以任务关联 `file_id`：

```sql
ALTER TABLE `document_task`
    CHANGE COLUMN `document_id` `file_id` BIGINT UNSIGNED NOT NULL COMMENT '文件 ID';
```

## 3. V7 迁移（含老数据搬迁）

```text
learnhub-knowledge/src/main/resources/db/migration/V7__split_document_into_file_and_relation.sql
```

```sql
CREATE TABLE `document_file` (...上面那张表...);
CREATE TABLE `knowledge_base_document` (...上面那张表...);

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

ALTER TABLE `document_task`
    CHANGE COLUMN `document_id` `file_id` BIGINT UNSIGNED NOT NULL COMMENT '文件 ID';

DROP TABLE `document`;
```

> 备注：老表里同一文件跨库时 status 理论上一致，用 MAX(status) 取一行足够；实际数据量很小，不会出错。

## 4. 代码变化清单

### 实体（3 个）

- 删除 `entity/Document.java`。
- 新增 `entity/DocumentFile.java`：id、userId、sha256、objectName、fileName、fileSize、contentType、status、createdAt、updatedAt。
- 新增 `entity/KnowledgeBaseDocument.java`：id、knowledgeBaseId、fileId、createdAt。
- `entity/DocumentTask.java`：字段 `documentId` → `fileId`（其余不变）。

### Mapper（2 个新 + 1 删）

- 删除 `mapper/DocumentMapper.java` 和它的 XML。
- 新增 `mapper/DocumentFileMapper.java`：
  - `findByUserIdAndSha256(userId, sha256)`：上传时查文件是否已存在。
  - `selectByIdsAndUserId(documentIds, userId, knowledgeBaseId)`：绑定前过滤——只返回“属于该用户、且该知识库还没绑定”的文件（XML 实现）。
- 新增 `mapper/KnowledgeBaseDocumentMapper.java`：
  - `countByKnowledgeBaseId(kbId)`：删除知识库前查有没有文档。
  - `countByFileId(fileId)`：删除文件后查是否还被任何知识库引用。
  - `selectPageWithFile(page, userId, kbId)`：列表分页，join 文件表（XML）。

### Service 关键流程

**上传（只建文件，不碰知识库）**：

```text
① 算 sha256
② document_file 已有同 sha256 → 复用，跳过 MinIO 上传
   没有 → putObject + insert document_file（status = UPLOADED）
③ 返回 DocumentResponse(fileId, ...)
```

**绑定（追加）**：

```text
① 校验 kb 归属（findOwned）
② 空列表直接返回
③ selectByIdsAndUserId(ids, userId, kbId)：只取“自己的 + 该库还没绑定的”
④ insert knowledge_base_document（撞 uk_kb_file 由 ③ 提前挡住）
```

**解绑（全部解绑后重绑保留的）**：

```text
① 校验 kb 归属
② 查出该库当前绑定的 fileId 列表，过滤掉要解绑的 → 得到保留列表
③ 删除该库全部关联
④ 重新绑定保留列表（复用 bindDocuments）
```

**删除文件（文件级，级联）**：

```text
① getOwnedFile(userId, fileId) 校验归属（无则 404）
② 删掉该文件在所有知识库里的关联
③ countByFileId(fileId) == 0 → 删 document_file 记录 + 删 MinIO 对象
   MinIO 删除失败 catch + log（尽力而为，对象变孤儿待补偿）
```

**下载 / 分享**：

```text
getOwnedFile(userId, fileId)：document_file 按 id + user_id 查 → 用 file.objectName 下载 / 生成 URL
```

**知识库删除**：

```text
countByKnowledgeBaseId(kbId) > 0 → 409（有文档先解绑/删文件）
```

### DTO / 接口

- `DocumentResponse` 现在是 `(fileId, fileName, fileSize, sha256, status, createdAt)`——`fileId` 是文件本体 id，上传的产物。
- 对外 API 分两组：
  - **文件（资产）**：`POST /api/v1/document`（上传）、`GET /api/v1/document`（我的文件列表）、`GET /api/v1/document/{fileId}/download`、`GET /api/v1/document/{fileId}/share-url`、`DELETE /api/v1/document/{fileId}`（删文件，级联解绑）。
  - **知识库（归属）**：`POST /api/v1/knowledge-bases/bind-document`（绑定）、`POST /api/v1/knowledge-bases/unbind-document`（解绑）、`GET /api/v1/knowledge-bases/{kbId}/file`（库内文档列表）。
- 新增内部查询 DTO `DocumentRow`（`@Data` 类，XML 映射用）。
- 铁律不变：所有按 id 访问文件的查询，SQL 都带 `user_id`（文件是资产，id 可枚举，不校验就是越权）。

## 5. 验证清单

- [ ] `mvn -pl learnhub-knowledge -am compile` 通过。
- [ ] 重启应用，Flyway 执行 V7，`flyway_schema_history` success = 1。
- [ ] 老数据迁移成功：`SELECT * FROM document_file` 有数据、`knowledge_base_document` 关联数 ≥ 老 document 行数。
- [ ] 上传文件 → 返回 fileId；同内容再传一次 → 返回同一个 fileId（复用，不重复存）。
- [ ] 绑定：文件绑到知识库 → `GET /knowledge-bases/{kbId}/file` 能看到；重复绑定同文件 → 不会重复插（幂等）。
- [ ] 解绑：unbind 传要移除的 id → 文件从知识库消失，但文件还在（`GET /api/v1/document` 仍能看到）。
- [ ] 同一文件绑两个知识库：两条关联、一条 document_file；删除文件 → 两个知识库的关联都没了，无其他引用时 MinIO 对象也删了。
- [ ] 越权验证：用户 B 拿用户 A 的 fileId 下载/删除 → 404。
- [ ] 删除有文档的知识库 → 409。

> 本重构不影响 Part 4 的异步解析设计：消息/任务里的 `fileId` 现在指向 `document_file.id`；发消息时机从“上传后”挪到“绑定后”。
