# Session F：本次优化的代码变更索引

这篇不是抽象概念，而是“从文档定位到源码”的索引。每一项都写出文件、方法、原问题、改法和验证方式。

## 1. 知识库详情缓存隔离

文件：`learnhub-knowledge/src/main/java/com/github/comui520/learnhub/knowledge/service/KnowledgeBaseService.java`

修改位置：`getById`、`update`、`delete`、`buildDetailCacheKey`。

原来的 key：

```text
kb:detail:{knowledgeBaseId}
```

问题：如果用户 A 的知识库详情已经进入缓存，用户 B 请求相同 ID 时，缓存命中可能绕过数据库归属查询。

现在的 key：

```text
kb:detail:{userId}:{knowledgeBaseId}
```

同时，更新和删除时使用同一个 `buildDetailCacheKey` 删除缓存，避免读写 key 不一致。缓存 JSON 解析失败时也会先删除损坏缓存，再回源数据库。

验证：

1. 用户 A 查询自己的知识库；
2. 检查 Redis key 含有 A 的 userId；
3. 用户 B 使用自己的 Token 访问同一个 ID；
4. 必须经过自己的归属校验，不能只因为缓存命中就返回。

## 2. 绑定文件列表缓存

文件：`learnhub-knowledge/src/main/java/com/github/comui520/learnhub/knowledge/service/KnowledgeBaseService.java`

修改位置：`listBoundFileIds`、`bindDocuments`、`unbindDocuments`、`evictFileIds`。

改动：

- key 统一由 `buildFileIdsCacheKey` 生成；
- 数据库查询后写入 Redis List；
- 写入后设置 60 秒 TTL；
- 空列表使用 `__EMPTY__` 哨兵；
- 绑定、解绑、删除知识库后删除该 key；
- 缓存值不是合法 Long 时删除并回源。

为什么要缓存空列表：Redis List 没有元素时无法区分“还没缓存”和“缓存结果为空”。哨兵解决的是这个语义问题。

## 3. 解绑不重复投递解析任务

文件：`learnhub-knowledge/src/main/java/com/github/comui520/learnhub/knowledge/service/KnowledgeBaseService.java`

修改位置：`unbindDocuments`。

原来的流程：

```text
删除全部关联
调用 bindDocuments 恢复保留文件
bindDocuments 发送 RabbitMQ 解析消息
```

问题：保留的文件可能早已解析成功，但解绑操作会再次发送解析消息。消费者虽然可能因为 SUCCESS 状态跳过，但队列、日志和检查仍然被浪费。

现在的流程：

```text
读取保留的 fileId
删除当前知识库全部关联
直接重新插入保留关联
删除关联缓存
```

只有真正执行 `bindDocuments` 的新绑定才会发送解析消息。

## 4. AI 出题事务边界

文件：`learnhub-study/src/main/java/com/github/comui520/learnhub/study/service/StudyService.java`

修改位置：`generateQuestion`、新增 `persistGeneratedQuestions`。

原来的流程在 `@Transactional generateQuestion` 中完成：

```text
开启事务
调用 Qdrant
调用 AI provider
解析 JSON
写入题目
提交事务
```

现在：

```text
generateQuestion：调用模型和校验，不开启数据库事务
transactionTemplate.execute：只写题目和选项
```

`TransactionTemplate` 显式包住 `persistGeneratedQuestions`，让模型网络延迟不会长时间占用数据库连接。

验证：编译通过；生成题目功能仍能返回题目；模型调用失败时不会插入半成品题目。

## 5. 额度账户并发初始化

文件：

- `learnhub-credit/src/main/java/com/github/comui520/learnhub/credit/mapper/CreditAccountMapper.java`
- `learnhub-credit/src/main/java/com/github/comui520/learnhub/credit/service/CreditService.java`

修改位置：`insertIfAbsent`、`ensureAccount`。

新增 SQL：

```sql
INSERT INTO credit_account (user_id, balance)
VALUES (#{userId}, 0)
ON DUPLICATE KEY UPDATE id = id
```

它依赖表上的 `uk_user_id` 唯一键。两个并发请求同时初始化时，数据库保证最终只有一条账户记录。

`ensureAccount` 现在先执行幂等插入，再查询账户，不再依赖应用层“先查再插”的竞态流程。

## 6. MinIO 输入流释放

文件：`learnhub-knowledge/src/main/java/com/github/comui520/learnhub/knowledge/consumer/DocumentParseConsumer.java`

修改位置：`parseContent`。

原来取得 MinIO `InputStream` 后直接传给解析器，没有显式关闭。现在使用：

```java
try (InputStream inputStream = minioClient.getObject(args)) {
    return documentParseService.parseAndChunk(..., inputStream);
}
```

验证重点不是返回值，而是异常路径也会执行 close；长期解析时不会持续占用网络连接。

## 7. RAG metadata 和引用 JSON

文件：

- `learnhub-ai/src/main/java/com/github/comui520/learnhub/ai/service/RagChatService.java`
- `learnhub-ai/src/main/java/com/github/comui520/learnhub/ai/utils/VectorUtil.java`

修改位置：`buildReferencesJson`、`readChunkIndex`、`buildContext`。

原来手工拼接：

```java
sb.append("{\"fileName\":\"").append(fileName)...
```

问题：文件名中有引号或反斜杠时可能产生非法 JSON。现在使用 `ObjectMapper.writeValueAsString` 序列化 `Reference` record。

chunkIndex 读取支持：

- `Number` 的 `longValue()`；
- 字符串 `Long.parseLong`；
- 旧字段名 `chunk_index`；
- 缺失或非法值回退为 `-1` 并记录日志。

## 8. 请求校验和 OpenAPI

文件：`learnhub-knowledge/src/main/java/com/github/comui520/learnhub/knowledge/dto/BindDocumentRequest.java`

新增：

- `@NotEmpty documentFileIds`；
- `@NotNull knowledgeBaseId`。

这样空绑定请求会在 Controller 参数校验阶段返回 400，而不是进入 Service 后静默返回。Swagger 也会知道这两个字段是必需的。

文件：`learnhub-study/src/main/java/com/github/comui520/learnhub/study/controller/StudyController.java`

生成题目方法使用 `@Valid @RequestBody GenerateStudyQuestionRequest`，确保 OpenAPI 将其描述为 JSON body。

## 9. 配置和依赖整理

文件：`learnhub-application/src/main/resources/application-dev.yml`

公开默认值改为占位符：MySQL、RabbitMQ、MinIO、JWT 和 AI key 由环境变量提供。

文件：`learnhub-ai/src/main/java/com/github/comui520/learnhub/ai/service/GenerateStudyQuestionService.java`

删除没有实际使用的 `CurrentUser` 构造器依赖，避免 Service 同时依赖两套用户上下文来源。当前用户 ID 由 `StudyService` 从 SecurityContext 获取后显式传入。

## 10. 如何阅读这次提交

先看：

```powershell
git show --stat b8ba533
git show b8ba533 -- LearnHubBackend/learnhub-knowledge/src/main/java/com/github/comui520/learnhub/knowledge/service/KnowledgeBaseService.java
```

如果要回到优化前：

```powershell
git diff part8-pre-optimization-20260914..main
```

`part8-pre-optimization-20260914` 是优化前的 checkpoint，主分支 `b8ba533` 是优化后的版本。
