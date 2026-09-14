# Part 4 课程：RabbitMQ 异步文档解析

> 前置要求：Part 3 完成（知识库、上传、MinIO 都通），Docker Compose 里 RabbitMQ 正常运行。
>
> 本 Part 是**第一次接触消息队列**，请务必先读 [前置教学：RabbitMQ 与 Spring AMQP 从零认识](primer-rabbitmq-and-spring-amqp.md)，再开始 Session A。

## 0. 本 Part 到底在做什么（一句话）

把“上传后同步等解析”改成“上传建文件、**绑定后**发一条消息，消费者异步解析”，并保证消息**丢了能补、重复了不坏事、永远失败的有地方去**。

`document_task` 表在 Part 3 建好了但一直闲置，本 Part 让它正式上岗：任务状态、重试次数都以它为准。

## 1. 本 Part 新增的技术（第一次见面，先认识）

| 技术 | 是干嘛的 | 依赖怎么写 |
|---|---|---|
| RabbitMQ | 消息队列软件，消息的中转站 | 不需要 Java 依赖，是 Docker 里跑的服务 |
| `spring-boot-starter-amqp` | Spring Boot 官方的 RabbitMQ 客户端 | `learnhub-infrastructure/pom.xml` 里解开注释即可 |
| Spring AMQP | 发消息（`RabbitTemplate`）、收消息（`@RabbitListener`） | 上面的 starter 自带 |
| spring-retry | 消费者失败自动重试 | starter 传递带进来，不用自己加 |

**为什么依赖放 infrastructure 而不是 knowledge**：infrastructure 是“外部系统适配层”，RabbitMQ 和 MinIO 一样属于外部系统；`learnhub-knowledge` 依赖 infrastructure，会传递拿到，不需要在 knowledge 里重复声明。

## 2. 学习路径

| 文档 | 主题 | 档位 |
|---|---|---|
| [primer](primer-rabbitmq-and-spring-amqp.md) | RabbitMQ 概念 + Spring AMQP 对应关系（零基础） | 🧑‍🏫 阅读 |
| [Session A](session-a-rabbitmq-basics.md) | 接入 RabbitMQ + 绑定后发消息 | 🧑‍🏫 我带 |
| [Session B](session-b-parse-consumer.md) | 消费者 + 状态机 + 幂等 | 🧑‍🏫 概念 + 🤝 各写一半 |
| [Session C](session-c-reliability-and-recovery.md) | 有限重试 + 死信 + 补偿 + 重跑 + 任务列表 | 🧑‍🏫 概念 + 🤝/🏃 实现 |

不要跳过 primer 直接做 Session A——你现在看不懂，不是智商问题，是缺概念，primer 就是补概念的。

## 3. 固定约定

- 队列/交换机命名统一 `learnhub.xxx`；业务拓扑（交换机/队列/绑定）放 **knowledge 模块**，技术配置（连接、消息转换器）放 **infrastructure 模块**。
- 任务状态以 `document_task` 表为准（**数据库是最终真相**），消息只是“通知”，丢了可以补偿重发。
- 解析状态机（UPLOADED/PARSING/COMPLETED/FAILED）挂在 `document_file` 表上——Part 3 拆表后，同一文件不管进几个知识库，只解析一次。
- 消费者必须**幂等**：重复消息不重复处理（用任务状态 + 唯一索引做防重）。
- 先保证“至少一次投递 + 幂等消费”，再谈“恰好一次”。
- 消费者不是 HTTP 接口，不走 Spring Security 过滤器链，不需要登录态；数据隔离靠 SQL 里的 user_id 条件。

## 4. 最终验收清单

> 测试按你的要求后置到 Part 7，这里全部是手动验证项。

- [ ] 上传文档 → 绑定到知识库后，RabbitMQ 管理台（<http://localhost:15672>）能看到消息进队，消息体是 JSON。
- [ ] 消费者收到消息后，文档状态 UPLOADED → PARSING → COMPLETED/FAILED。
- [ ] 同一条消息重复投递，不会重复更新任务（幂等）。
- [ ] 解析失败自动重试 3 次，最终失败进死信队列，`retry_count`/`last_error` 有记录。
- [ ] 应用重启后，PENDING/RUNNING 的任务能恢复处理（启动补偿）。
- [ ] RabbitMQ 停掉时上传不炸，恢复后任务能补发。
- [ ] 任务列表接口支持分页和状态筛选（用 XML + 分页插件）。

## 5. 答辩题预告

1. 为什么文档解析要异步，而不是在 HTTP 请求里同步做？
2. Exchange / Queue / Routing Key 是什么关系？
3. RabbitMQ 为什么可能产生重复消息？“至少一次投递”怎么配合“幂等消费”？
4. 消费者执行到一半崩溃会发生什么？
5. 有限重试和无限重试的区别？死信队列解决什么问题？
6. RabbitMQ 不可用时，上传接口应该怎么办？

从 [前置教学](primer-rabbitmq-and-spring-amqp.md) 开始。
