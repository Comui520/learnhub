# Session C：事务边界、资源释放与并发初始化

## 1. 为什么要做这三类优化

它们都不是“把代码跑快一点”这么简单：

- 事务边界决定数据库连接被占多久；
- 资源释放决定 MinIO 和 HTTP 连接能否回收；
- 并发初始化决定两个第一次请求能否安全地创建同一份数据。

## 2. AI 调用为什么不应该包在事务里

生成题目过程包含两部分：

    Qdrant 检索 -> AI 网络调用 -> JSON 校验 -> MySQL 写题目

Qdrant 和 AI provider 都是外部调用。它们可能耗时几秒，也可能超时或重试。如果 public 方法一开始就开启 @Transactional，数据库连接会在整个等待期间被占用。

这会带来：

- 连接池更容易耗尽；
- 慢模型请求会拖住其他数据库请求；
- 事务日志和锁的生命周期变长。

## 3. TransactionTemplate 的作用

项目在 StudyService 中显式创建 TransactionTemplate：

    this.transactionTemplate = new TransactionTemplate(transactionManager);

生成方法先调用模型：

    List<GeneratedStudyQuestion> generated = generateService.generateStudyQuestions(...);

模型和 JSON 都成功以后，再执行：

    transactionTemplate.execute(status -> persistGeneratedQuestions(...));

TransactionTemplate 会在 execute 代码块开始时打开事务，代码块结束时提交；抛出运行时异常时回滚。

这比在同一个类里写一个 private @Transactional 方法更可靠，因为 private 方法和同类自调用不会经过 Spring AOP 代理。

## 4. 短事务中应该放什么

短事务只放数据库写操作：

    插入 study_question
    根据生成题目插入 study_question_option
    组装返回对象

不要把以下操作放进这个事务：

- 调用 ChatClient；
- 调用 Qdrant；
- 读取 MinIO 文件；
- 等待 RabbitMQ；
- 睡眠或人工重试。

## 5. MinIO 流为什么必须关闭

MinIO 的 getObject 返回的是网络输入流。只要没有关闭，它就可能占用底层 HTTP 连接。

项目现在使用：

    try (InputStream inputStream = minioClient.getObject(args)) {
        return documentParseService.parseAndChunk(..., inputStream);
    }

try-with-resources 的关键点是：

- 正常 return 前会 close；
- parse 抛异常时也会 close；
- 不需要手动写 finally；
- close 异常会按 Java 资源规则处理。

如果以后同时读取多个文件，要特别注意不要把所有 InputStream 放进一个长生命周期集合。

## 6. “先查再插”为什么不安全

原始思路：

    account = select where user_id = ?
    if account == null:
        insert account

两个并发请求都可能在 insert 前查到 null。应用层 if 不是锁。

数据库表中已经有：

    UNIQUE KEY uk_user_id (user_id)

因此可以使用：

    INSERT INTO credit_account (user_id, balance)
    VALUES (?, 0)
    ON DUPLICATE KEY UPDATE id = id

这表示：不存在就插入，已存在就什么也不改变。之后再 select 得到账户。

## 7. 数据库唯一键和应用检查的分工

应用层检查的价值是给用户友好错误：

    同名知识库 -> KNOWLEDGE_BASE_NAME_EXISTS

数据库唯一键的价值是并发兜底：

    两个请求同时创建 -> 只有一条能成功

两者不能互相替代。正式项目通常同时保留。

## 8. 这次没有处理的事务问题

文档上传仍然包含 MinIO 上传和数据库写入，严格来说也可以继续拆分。更强的方案需要：

- 文件状态机；
- 上传成功标记；
- 失败补偿；
- Outbox 或后台清理任务。

当前先保留原有上传流程，因为它属于下一阶段的可靠性专题，不在本次有限优化中扩大范围。
