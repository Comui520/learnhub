# Part 4 课程：RabbitMQ 异步文档解析

> 前置要求：Part 3 完成（知识库、上传、MinIO 都通），Docker Compose 里 RabbitMQ 正常运行。
>
> 目标：把“上传后同步等着解析”改成“上传后发消息，Worker 异步解析”——学会消息队列最核心的四个能力：**异步解耦、削峰、至少一次投递、重试与死信**。

## 1. 本 Part 新增的技术

| 技术 | 干嘛的 | 依赖 |
|---|---|---|
| RabbitMQ | 消息队列：异步任务、解耦、削峰 | `spring-boot-starter-amqp`（infrastructure 模块 POM 里已注释，解开即可） |
| Spring AMQP | `RabbitTemplate` 发送、`@RabbitListener` 消费 | 同上 |
| 死信队列（DLQ） | 永久失败的消息归宿 | RabbitMQ 自带能力 |

## 2. 三个 Session

| Session | 主题 | 档位 |
|---|---|---|
| [A](session-a-rabbitmq-basics.md) | RabbitMQ 概念 + 接入 + 上传后发消息 | 🧑‍🏫 我带 |
| [B](session-b-parse-consumer.md) | 解析消费者 + 状态机 + 幂等 | 🧑‍🏫 概念 + 🤝 实现 |
| [C](session-c-reliability-and-recovery.md) | 有限重试 + 死信 + 恢复 + 失败任务重跑 | 🧑‍🏫 概念 + 🏃 自己做 |

## 3. 固定约定

- 队列/交换机命名统一 `learnhub.xxx`；业务队列声明放 **knowledge 模块**，消息转换器这类技术配置放 **infrastructure 模块**。
- 任务状态以 `document_task` 表为准（数据库是最终真相），消息只是“通知”，丢了可以补偿重发。
- 消费者必须**幂等**：重复消息不重复处理（用任务状态做防重）。
- 先保证“至少一次投递 + 幂等消费”，再谈“恰好一次”。

## 4. 最终验收清单

- [ ] 上传文档后，RabbitMQ 管理台（http://localhost:15672）能看到消息进队。
- [ ] 消费者收到消息后，文档状态 UPLOADED → PARSING → COMPLETED/FAILED。
- [ ] 同一条消息重复投递，不会重复更新任务（幂等）。
- [ ] 解析失败自动重试 3 次，最终失败进死信队列，`retry_count`/`last_error` 有记录。
- [ ] 应用重启后，PENDING/RUNNING 的任务能恢复处理。
- [ ] RabbitMQ 停掉时上传不炸，恢复后任务能补发。
- [ ] 任务列表接口支持分页和状态筛选（用 XML + 分页插件）。
- [ ] `mvn clean verify` 全绿。

## 5. 答辩题预告

1. 为什么文档解析要异步，而不是在 HTTP 请求里同步做？
2. Exchange / Queue / Routing Key 是什么关系？
3. RabbitMQ 为什么可能产生重复消息？“至少一次投递”怎么配合“幂等消费”？
4. 消费者执行到一半崩溃会发生什么？
5. 有限重试和无限重试的区别？死信队列解决什么问题？
6. RabbitMQ 不可用时，上传接口应该怎么办？

从 [Session A](session-a-rabbitmq-basics.md) 开始。
