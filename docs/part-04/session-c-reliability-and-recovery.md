# Session C：有限重试、死信与恢复

> 目标：把 Session B 的“AUTO + 抛异常 = 无限重试”改成“最多重试 3 次 → 进死信队列”，并实现启动补偿、失败任务重跑、任务列表分页。
>
> 档位：概念 🧑‍🏫 我带，实现 🏃 你自己做。预计 3～4 小时。

## 1. 三种失败场景和对应方案

| 失败场景 | 方案 |
|---|---|
| 消费者处理临时失败（DB 抖动、网络） | **有限重试**：重试 3 次，间隔递增 |
| 重试仍失败（消息本身有问题/永久失败） | **死信队列**：进 DLQ，人工排查 |
| 应用崩溃、重启（消息还没 ack） | **未 ack 重新投递** + 启动补偿 |
| RabbitMQ 不可用时上传 | 上传照常成功，任务留 PENDING，**启动补偿重发** |

核心思想：**数据库是唯一真相，消息是通知**。状态和重试次数以 `document_task` 表为准，消息丢了、重复了都能靠数据库恢复。

---

## 2. 有限重试 + 死信（🧑‍🏫 概念 + 配置）

Session A 已经给业务队列配了死信参数（`x-dead-letter-exchange` + routing key）。现在再配“有限重试”：用 Spring Retry 的拦截器包住消费者，最多 3 次、间隔退避；3 次都失败后，`RejectAndDontRequeueRecoverer` 让消息被拒绝且不重回队列 → 按死信参数进 DLQ。

`learnhub-knowledge` 建 `config/RabbitListenerConfig.java`：

```java
package com.github.comui520.learnhub.knowledge.config;

import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;

@Configuration
public class RabbitListenerConfig {

    @Bean
    public RetryOperationsInterceptor retryInterceptor() {
        return RetryInterceptorBuilder.stateless()
                .maxAttempts(3)                          // 最多 3 次（含首次）
                .backOffOptions(1000, 2.0, 10000)        // 1s -> 2s -> 4s，最大 10s
                .recoverer(new RejectAndDontRequeueRecoverer())  // 3 次失败 -> 拒绝且不重回 -> 死信
                .build();
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter messageConverter,
            RetryOperationsInterceptor retryInterceptor) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setAdviceChain(retryInterceptor);
        return factory;
    }
}
```

把消费者改成用这个容器工厂：

```java
@RabbitListener(containerFactory = "rabbitListenerContainerFactory",
                queues = DocumentParseRabbitConfig.PARSE_QUEUE)
public void onParse(DocumentParseMessage message) { ... }
```

**验证死信**：让 `parseContent()` 一直抛异常 → 观察管理台：消息先被消费 3 次（Ready 数变化、Unacked 出现），最后出现在 `learnhub.document.parse.dlq` 里。同时 `document_task.retry_count` 应该是 3。

> 面试点：Spring Retry 的重试是**进程内重试**，进程崩溃就不算数了；所以 `retry_count` 必须记在数据库，不能依赖 MQ 的投递次数。

---

## 3. 启动补偿：应用重启后恢复任务（🏃 你自己做）

实现一个 `ApplicationRunner`：应用启动后，扫描 `document_task` 里 `status IN ('PENDING','RUNNING')` 的任务，把对应的解析消息**重新发送**。

提示：

- 用 `LambdaQueryWrapper`（primer 里学的）查 PENDING/RUNNING 任务。
- 对每个任务调 `rabbitTemplate.convertAndSend(...)`（复用 Session A 的 exchange/routing key）。
- 消息体要重新构造 `DocumentParseMessage(task.documentId, ...)`——documentId 有了，knowledgeBaseId 和 userId 需要查 document 表补全。
- 注意：启动补偿要**幂等**——重发后消费者自己会做幂等检查，所以重复启动补偿也不会重复处理。

## 4. 失败任务重跑接口（🏃 你自己做）

需求：`POST /api/v1/documents/{documentId}/retry`，把 `FAILED` 的任务重新发一条解析消息。

- 权限：文档必须属于当前用户（数据隔离 SQL 老规矩）。
- 行为：查 task → status 必须是 FAILED（否则 409）→ 重置 `retryCount = 0`、`status = PENDING` → 发消息 → 返回 200。
- 在 Swagger 标注 200/401/404/409。

## 5. 任务列表接口：XML + 分页（🏃 自己做，用上 primer）

需求：`GET /api/v1/knowledge-bases/{kbId}/tasks?status=&page=1&size=10`，返回该知识库的任务列表（含文档文件名），支持按状态筛选和分页。

要求：

- **XML Mapper** 写多表查询：`document_task` JOIN `document`，带 `user_id` 数据隔离条件，`<if>` 动态拼 status。
- **分页插件**返回 `IPage<TaskWithDocument>`。
- 参考 [MyBatis-Plus 高级用法与 XML](../part-03/primer-mybatis-plus-and-xml.md) 第 5.3 节，把 `searchTasks(Page<?> page, @Param...)` 的 Page 参数加上，插件自动改写 SQL。

## 6. 最终验收（Part 4）

- [ ] `mvn clean verify` 全绿。
- [ ] 上传 → 自动解析 → COMPLETED；管理台消息进队出队正常。
- [ ] 模拟重复投递：不重复处理（幂等）。
- [ ] 模拟失败：重试 3 次 → 进 DLQ，retry_count=3，last_error 有内容。
- [ ] 重启应用：PENDING/RUNNING 任务自动补发。
- [ ] 失败任务能通过 retry 接口重跑。
- [ ] 任务列表接口：分页 + 状态筛选 + 只能看自己的。
- [ ] `docker compose stop rabbitmq` 时上传不炸；恢复后补发成功。
- [ ] 更新 `LEARNHUB_PLAN.md`：勾选 Part 4，追加决策记录（重试策略、幂等方案、补偿机制）。
- [ ] 能回答 README 里的 6 道答辩题。

## 7. 复盘题

1. “有限重试 + 死信”和“无限重试”的差别？死信队列里躺着的消息通常意味着什么？
2. 为什么 `retry_count` 要记数据库而不是信 MQ 的投递次数？
3. 启动补偿为什么天然幂等？（提示：消费者侧的幂等检查）
4. “至少一次投递 + 幂等消费 + 补偿”三个机制分别解决什么问题？组合起来达到什么效果？
5. XML Mapper 和 `@Select` 你这次分别在什么时候用了？选择依据是什么？

完成 Part 4 验收后，进入 **Part 5：RAG 与流式问答**——文档解析的真实内容（PDF/Word/Markdown → 文本 → 向量）和 Spring AI 要登场了。

