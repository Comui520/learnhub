# Session A：RabbitMQ 概念 + 接入 + 上传后发消息

> 目标：搞懂 Exchange/Queue/Routing Key 三个概念，把 RabbitMQ 接进项目，上传文档后自动发一条“解析任务”消息。
>
> 档位：🧑‍🏫 我带。预计 3～4 小时。

## 0. 今天到底要学会什么

1. 为什么“上传完直接同步解析”不行，需要消息队列。
2. Exchange、Queue、Routing Key 分别是什么、怎么配合。
3. `RabbitTemplate.convertAndSend` 怎么发消息，JSON 序列化怎么配。
4. 在 RabbitMQ 管理台亲眼看到消息进出。

---

## 1. 先建立直觉：为什么要异步

假设上传文档后直接在 HTTP 请求里解析（读 PDF、切文本、算向量……）：

- 用户要等几十秒，请求超时。
- 解析是 CPU/内存重活，占住 Tomcat 线程，拖垮其他接口。
- 解析失败要重试，但请求早就结束了，没人管。

用消息队列改成：

```text
上传接口：存文件 + 建记录 + 发消息 -> 立刻返回 200
Worker：从队列拿消息 -> 慢慢解析 -> 更新状态
```

这就是**异步解耦**：上传和解析不再互相拖累，还能靠队列**削峰**（消息先排队，Worker 慢慢消费）。

---

## 2. 三个核心概念

```text
生产者（上传接口）
   |  发消息到 Exchange
   v
Exchange（交换机：按 Routing Key 路由）
   |  匹配 Binding
   v
Queue（队列：消息排队，等消费者）
   |  推送
   v
消费者（Worker）
```

- **Queue（队列）**：消息的“信箱”，消息进了队列就等着被消费。`durable` 队列重启不丢。
- **Exchange（交换机）**：消息先到交换机，它按 **Routing Key** 决定投到哪个队列。我们用的 `DirectExchange` 规则是：Routing Key 完全匹配。
- **Binding（绑定）**：把队列绑到交换机，并声明“什么 Routing Key 走这个队列”。

我们只用一个交换机 + 两个队列（业务队列 + 死信队列），Routing Key 用 `document.parse`。

---

## 3. 跟着做一遍

### Step 1：确认 RabbitMQ 在跑

```powershell
docker compose ps
```

`learnhub-rabbitmq-1` 应该 healthy。管理台：`http://localhost:15672`，账号密码用 `.env` 里的 `learnhub` / `1234`。

### Step 2：解开 AMQP 依赖

`learnhub-infrastructure/pom.xml` 里 `spring-boot-starter-amqp` 被注释了，解开：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
```

### Step 3：配置连接

`application-dev.yml` 加：

```yaml
spring:
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_DEFAULT_USER:learnhub}
    password: ${RABBITMQ_DEFAULT_PASS:1234}
```

> 记住 Part 2 的教训：`.env` 只喂 Docker Compose，Java 进程要用环境变量或默认值，这里默认值已经对齐你的 `.env`。

### Step 4：消息转换器（JSON）

`infrastructure` 模块建 `rabbit` 包：

```java
package com.github.comui520.learnhub.infrastructure.rabbit;

import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    @Bean
    public MessageConverter messageConverter() {
        // 让 RabbitTemplate 和 @RabbitListener 都用 JSON 序列化对象
        return new Jackson2JsonMessageConverter();
    }
}
```

Spring Boot 检测到唯一的 `MessageConverter` Bean 后，`RabbitTemplate` 和监听容器都会自动使用它。

### Step 5：声明队列和交换机（业务拓扑放 knowledge 模块）

`learnhub-knowledge` 模块建 `config/DocumentParseRabbitConfig.java`：

```java
package com.github.comui520.learnhub.knowledge.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DocumentParseRabbitConfig {

    public static final String EXCHANGE = "learnhub.document.exchange";
    public static final String ROUTING_KEY = "document.parse";
    public static final String PARSE_QUEUE = "learnhub.document.parse.queue";
    public static final String DLX = "learnhub.dlx";
    public static final String DLQ = "learnhub.document.parse.dlq";

    @Bean
    public DirectExchange documentExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    /** 业务队列：持久化；消息被拒绝且不重回队列时，进死信交换机 */
    @Bean
    public Queue parseQueue() {
        return QueueBuilder.durable(PARSE_QUEUE)
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(DLQ)
                .build();
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX, true, false);
    }

    @Bean
    public Queue parseDeadLetterQueue() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    public Binding parseBinding() {
        return BindingBuilder.bind(parseQueue()).to(documentExchange()).with(ROUTING_KEY);
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(parseDeadLetterQueue()).to(deadLetterExchange()).with(DLQ);
    }
}
```

逐行理解：

- `new DirectExchange(name, durable, autoDelete)`：持久化交换机。
- `QueueBuilder.durable(...)`：持久化队列，RabbitMQ 重启消息不丢。
- `.deadLetterExchange(DLX).deadLetterRoutingKey(DLQ)`：队列的“死信参数”——消息被拒绝且不重回队列时，投到 DLX，Routing Key 是 DLQ。
- Binding 把队列和交换机连起来，Routing Key 是 `document.parse`。

### Step 6：定义消息体

`knowledge` 模块建 `mq/DocumentParseMessage.java`：

```java
public record DocumentParseMessage(Long documentId, Long knowledgeBaseId, Long userId) {
}
```

### Step 7：上传后发消息

修改 `DocumentService`：注入 `RabbitTemplate`，上传成功、插入记录后发送：

```java
@Service
public class DocumentService {

    private final DocumentMapper documentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final RabbitTemplate rabbitTemplate;
    // 构造器注入

    @Transactional
    public DocumentResponse upload(Long userId, Long knowledgeBaseId, MultipartFile file) throws Exception {
        // ... ①②③④ 和 Session A 一样：查归属、算 sha256、查重、传 MinIO ...

        // ⑤ 建记录（状态 UPLOADED）
        document.setStatus(DocumentStatus.UPLOADED.name());
        documentMapper.insert(document);

        // ⑥ 发解析任务消息（MQ 挂了对上传不致命：catch 住，任务留待补偿）
        try {
            rabbitTemplate.convertAndSend(
                    DocumentParseRabbitConfig.EXCHANGE,
                    DocumentParseRabbitConfig.ROUTING_KEY,
                    new DocumentParseMessage(document.getId(), knowledgeBaseId, userId)
            );
        } catch (Exception e) {
            log.error("send parse message failed, will retry later: documentId={}", document.getId(), e);
        }

        return toResponse(document);
    }
}
```

为什么 `catch` 住不抛：消息发送失败不应该让上传接口 500——文档已经存好了，任务只是“没通知到”，Session C 会写启动补偿把漏掉的任务补发。这是**最终一致**的思路：数据库是真相，消息丢了能重来。

### Step 8：验证

1. 重启应用，看日志有没有报错。
2. 上传一个文档（用 Part 3 的接口）。
3. 打开 `http://localhost:15672` → Queues，看到 `learnhub.document.parse.queue` 有 1 条 Ready 消息；点进队列 → Get Message 能看到 JSON 格式的消息体（含 documentId）。

---

## 4. 主动制造错误

**错误 A：把 RabbitMQ 停掉再上传**

```powershell
docker compose stop rabbitmq
```

上传文档：应该仍然成功（消息发送异常被 catch），但队列里没有消息。恢复后启动应用，Session C 会补发。先观察“上传不炸”这个行为。

**错误 B：消息没有 JSON 序列化**

把 `messageConverter` Bean 注释掉再发一次，看消息体变成 Java 序列化乱码（`convertAndSend` 默认用 JDK 序列化）。恢复。

---

## 5. 复盘题

1. 为什么解析要异步？同步解析的三个问题分别是什么？
2. Exchange、Queue、Routing Key、Binding 的关系，用自己的话讲一遍。
3. 队列 `durable` 和不 durable 的区别？重启后消息还在吗？
4. 为什么发消息失败要 catch 而不是让上传 500？“数据库是真相”是什么意思？
5. 消息只进队列、还没被消费，应用就重启了，消息会丢吗？（提示：持久化队列 + 消息持久化）

完成后进入 [Session B](session-b-parse-consumer.md)：写消费者。

