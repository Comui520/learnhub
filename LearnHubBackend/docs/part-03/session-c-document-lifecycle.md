# Session C：文档生命周期（下载 / 删除 / 状态机 / 一致性）

> 目标：补全文档的下载和删除，把状态机真正用起来，并解决“数据库记录 vs MinIO 对象”的一致性。
>
> ⚠️ **2026-08-19**：上传与绑定分离后，本 Session 的“删除 = 删关联记录”已演进为“删除 = 删文件本体（级联解绑）”，下载/分享也改为按 fileId 直查文件。**当前语义以 [Session D](session-d-model-refactor-to-many-to-many.md) 为准**，本页保留为当时的教学思路。
>
> 档位：概念 🧑‍🏫 我带，实现 🏃 你自己做。预计 3～4 小时。

## 1. 下载：从 MinIO 拉回流

### 概念

`MinioClient.getObject()` 返回 `InputStream`，Spring 可以把它直接写进响应：

```java
@GetMapping("/documents/{id}/download")
public ResponseEntity<InputStreamResource> download(@PathVariable Long id) {
    DocumentFile file = documentService.getOwnedFile(currentUserId(), id);
    InputStream stream = documentService.downloadContent(file);
    return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(file.getContentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getFileName() + "\"")
            .body(new InputStreamResource(stream));
}
```

### 你来做

> **2026-08-18 重构后**（见 [Session D](session-d-model-refactor-to-many-to-many.md)）：这里的 `id` 是关联记录 `knowledge_base_document.id`，先查关联（join 文件表）校验归属，再拿 `DocumentFile` 操作：

1. `DocumentService` 加两个方法：
   - `getOwnedFile(userId, id)`：`knowledgeBaseDocumentMapper.findByIdAndUserId(id, userId)`（join 校验 `document_file.user_id`），没有 → 404，返回关联对应的 `DocumentFile`。
   - `downloadContent(file)`：`minioClient.getObject(...)` 用 `file.getObjectName()` 返回流。
2. `DocumentController` 加 download 接口（放 `@RequestMapping("/api/v1/documents")` 下）。
3. 验证：上传一个 PDF，下载回来，和原文件一致（可以比较 sha256）。

### 两种下载方案怎么选（面试常问）

| | Presigned URL | 流式（InputStreamResource） |
|---|---|---|
| 文件流向 | MinIO 直接到客户端，后端零负担 | 经过后端中转，占线程和带宽 |
| 权限/审计 | 生成时校验一次，下载行为后端不可见 | 每次下载都过后端，可审计/限流/统计 |
| 时效 | URL 有过期时间 | 无 |
| 网络要求 | 客户端必须能直连 MinIO | 客户端只访问后端 |
| 适合 | 大文件、高并发、分享链接 | 中小文件、受控下载 |

本 Part 用**流式**（要练权限校验和审计）；presigned 作为“分享链接”功能可以另留一个接口。若用 presigned，注意 bucket 不能公开读，否则签名失去意义。

## 2. 删除与一致性：数据库记录 vs MinIO 对象

### 问题本质

删除一个文档涉及两个系统：

```text
① 删关联记录 / 文件记录（数据库事务）
② 删 MinIO 对象（外部系统，不在事务里）
```

无论先删哪个，都可能出现不一致：

- 先删数据库、后删 MinIO：MinIO 删失败 → 留下孤儿对象。
- 先删 MinIO、后删数据库：数据库删失败 → 记录还在但文件没了。

### 常见方案（面试必讲）

| 方案 | 思路 | 评价 |
|---|---|---|
| 尽力而为 + 日志 | 先删数据库，MinIO 删除失败就记日志、定时任务补偿 | 简单，适合学习项目；有补偿任务兜底 |
| 软删除 | 记录标记 DELETED，异步任务真正删除 MinIO | 更稳，但要加状态和任务 |
| 消息队列 | 删记录后发消息，Worker 删对象，失败重试/死信 | 最终一致的标准做法，Part 4 学完 RabbitMQ 再来升级 |

**本 Session 的实现（重构后）**：删除“库里的文档”分两层：

- 先删 `knowledge_base_document` 关联记录（事务内，永远成功——删自己库里的条目，不被别的库拦住）。
- 再查 `existsByFileId(fileId)`：还有关联引用 → 不动文件；没有引用了 → 删 `document_file` 记录 + 删 MinIO 对象。
- MinIO 删除失败**不抛异常**，而是 `log.error` 记录（尽力而为），对象变孤儿待补偿。

### 你来做

```java
@Transactional
public void delete(Long userId, Long documentId) {
    KnowledgeBaseDocument relation = knowledgeBaseDocumentMapper.findByIdAndUserId(documentId, userId);
    if (relation == null) {
        throw new BusinessException(KnowledgeErrorCode.DOCUMENT_NOT_FOUND);
    }

    Long fileId = relation.getFileId();
    knowledgeBaseDocumentMapper.deleteById(documentId);

    if (!knowledgeBaseDocumentMapper.existsByFileId(fileId)) {
        DocumentFile file = documentFileMapper.selectById(fileId);
        if (file != null) {
            documentFileMapper.deleteById(fileId);
        }
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(minioProperties.getBucket())
                    .object(file.getObjectName())
                    .build());
        } catch (Exception e) {
            log.error("MinIO object delete failed, need compensation: object={}", file.getObjectName(), e);
        }
    }
}
```

注意 `@Transactional` 的位置：事务在方法返回时才提交，所以顺序是“先删关联 → 再删文件记录 → 最后删 MinIO”；MinIO 抛异常被吞掉，事务正常提交。吞异常是为了不让删除接口 500——代价是可能留下孤儿对象（日志里等着补偿）。

## 3. 状态机真正用起来

状态机目前只有 `UPLOADED` 一个入口。为了让它“活起来”，做一个简单的查询维度：

`GET /api/v1/knowledge-bases/{id}/documents`：返回该知识库的文档列表（含 status），只允许知识库主人。

提示：

- `KnowledgeBaseDocumentMapper` 加 `selectPageWithFile(page, userId, kbId)`：`knowledge_base_document` JOIN `document_file`，`WHERE kbd.knowledge_base_id = #{kbId} AND df.user_id = #{userId}`，XML 实现（参考 [Session D](session-d-model-refactor-to-many-to-many.md)）。
- 响应用 `DocumentResponse`。
- 面试点：`status` 字段现在只有 UPLOADED，但枚举和列已经为 Part 4/5 的 PARSING/EMBEDDING 预留了——讲状态机时可以说“状态是显式建模的，不是散落的 if-else 字符串”。

## 4. 主动制造错误

1. **下载别人的文档**：B 用户下载 A 的文档 → 404。
2. **删 MinIO 失败**：`docker compose stop minio` 后删除文档，观察“记录删了、日志有 error、接口 200”，再 `start minio` 看 MinIO 里的孤儿对象——亲眼理解“尽力而为”的代价。
3. **上传空文件**：传 0 字节文件，观察 sha256 也能算（是空文件指纹），思考要不要拦截空文件（练习：在 Service 加 `file.isEmpty()` 检查，返回 400 业务错误）。

## 5. 最终验收（Part 3）

- [ ] `mvn clean verify` 全绿。
- [ ] 两个用户分别建知识库，互不可见、互不可操作（列表/详情/上传/下载/删除全试一遍）。
- [ ] 重复上传同一文件 409。
- [ ] 下载的文件与原文件 sha256 一致。
- [ ] 删除文档：记录没了、MinIO 对象没了（正常情况）。
- [ ] 在 Swagger 里完整演示：建库 → 上传 → 列表 → 下载 → 删除。
- [ ] 更新 `LEARNHUB_PLAN.md`：勾选 Part 3，追加决策记录（删除一致性方案、去重策略、状态机设计）。
- [ ] 能回答 README 里的 6 道答辩题。

## 6. 复盘题

1. 下载时为什么用 `InputStreamResource` 而不是把整个文件读进内存？大文件会怎样？
2. “先删数据库再删 MinIO”和“先删 MinIO 再删数据库”各自的失败场景是什么？
3. 重构后“删关联”和“删文件”为什么是两步？`existsByFileId` 检查解决什么问题？
4. 为什么“访问别人的资源”用 404 而不是 403？
5. 状态机为什么要“先设计后实现”？把状态散落成字符串会有什么问题？

完成 Part 3 验收后，下一 Part 是 **RabbitMQ 异步文档解析**——`document_task` 表终于要派上用场了。
