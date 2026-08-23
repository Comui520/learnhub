# Session A：接入 RabbitMQ，绑定后发一条解析消息

> 目标：把 RabbitMQ 接进项目，文档绑定到知识库后自动向队列发一条“请解析”的消息，并在管理台亲眼看它进队。
> 档位：🧑‍🏫 我带（你跟着敲，每个文件我都逐行解释）。预计 2～3 小时。

## 0. 本 Session 完成时的样子

Part 3 把“上传”和“绑定”拆成了两步，本 Session 在**绑定成功后**多执行一条“发消息”的动作：

```text
上传：算 sha256 → 存 MinIO → 建 document_file(UPLOADED) → 返回 fileId
绑定：校验归属 → 去重 → insert 关联 → 发消息 → 返回
                                          ↓
                     learnhub.document.parse.queue（管理台能看到 1 条 Ready）
```

## 1. Step 1：确认 RabbitMQ 在跑

```powershell
docker compose ps
```

看到 `learnhub-rabbitmq-1` 状态 healthy。然后打开 <http://localhost:15672>，用 `learnhub` / `1234` 登录（账号来自 compose 的 `RABBITMQ_DEFAULT_USER` / `RABBITMQ_DEFAULT_PASS`，对应 `.env`）。登录不进去就先 `docker compose up -d rabbitmq`。

> 记牢两个端口：**5672** 是程序发消息用的 AMQP 端口；**15672** 只是管理台网页端口。别把 15672 写进 Java 配置。

## 2. Step 2：解开 AMQP 依赖

打开 `learnhub-infrastructure/pom.xml`，把注释掉的这段解开：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
```

**为什么放这里**：infrastructure 是“外部系统适配层”，RabbitMQ 和 MinIO 一样属于外部系统，客户端适配放这一层。`learnhub-knowledge` 依赖 infrastructure，会传递拿到这个依赖，不需要在 knowledge 里重复加。

改完跑一下，确认依赖能解析：

```powershell
mvn -pl learnhub-infrastructure -am compile
```

## 3. Step 3：配置连接（application-dev.yml）

打开 `learnhub-application/src/main/resources/application-dev.yml`，在 `spring:` 下面加：

```yaml
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_DEFAULT_USER:learnhub}
    password: ${RABBITMQ_DEFAULT_PASS:1234}
```

逐行解释：

- `spring.rabbitmq.*` 是 Spring Boot 读取 RabbitMQ 连接信息的固定前缀。配了它，Boot 自动创建 `ConnectionFactory`，你后面直接注入 `RabbitTemplate` 就能用。
- `${RABBITMQ_HOST:localhost}`：优先读环境变量 `RABBITMQ_HOST`，没有就用 `localhost`。冒号后面是默认值。和数据库的 `${MYSQL_HOST:localhost}` 一个套路。
- 5672 是 AMQP 协议端口。
- 账号密码默认值对齐你 `.env` 里的 `learnhub` / `1234`。

## 4. Step 4：消息转换器（infrastructure 模块）

新建 `learnhub-infrastructure/src/main/java/com/github/comui520/learnhub/infrastructure/rabbit/RabbitConfig.java`：

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
        return new Jackson2JsonMessageConverter();
    }
}
```

逐行理解：

- `@Configuration`：告诉 Spring 这是配置类，里面的 `@Bean` 方法会注册成 Bean（Part 1 学过 IOC）。
- `MessageConverter` 是 Spring AMQP 的接口，负责“Java 对象 ↔ 字节”。
- `Jackson2JsonMessageConverter` 用 Jackson 把对象转成 JSON。
- Spring Boot 发现容器里只有这一个 `MessageConverter` Bean，就自动让 `RabbitTemplate`（发消息）和监听容器（收消息）都用它——primer 5.5 说的“乱码问题”就是这一步解决的。

## 5. Step 5：声明交换机、队列、绑定（knowledge 模块）

新建 `learnhub-knowledge/src/main/java/com/github/comui520/learnhub/knowledge/config/DocumentParseRabbitConfig.java`：

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

这是本 Session 最重要的文件，逐段理解：

- **常量区**（`EXCHANGE` / `ROUTING_KEY` / `PARSE_QUEUE` / `DLX` / `DLQ`）：名字集中管理，发消息、收消息、配置三处都引用常量，避免手打字符串打错。命名规则统一 `learnhub.xxx`。
- `@Bean public DirectExchange documentExchange()`：声明一个直连交换机。`new DirectExchange(EXCHANGE, true, false)` 三个参数依次是：名字、durable（持久化，重启后交换机定义还在）、autoDelete（没人用了是否自动删，false）。
- `@Bean public Queue parseQueue()`：声明业务队列。
  - `QueueBuilder.durable(PARSE_QUEUE)`：创建持久化队列。
  - `.deadLetterExchange(DLX)`：队列的“死信参数”——这条消息变成死信时，投到这个交换机。
  - `.deadLetterRoutingKey(DLQ)`：死信消息投到 DLX 时用什么 routing key。
  - 这两行现在用不上（Session C 才用），但先声明好，Session C 就不用改队列定义了。`build()` 生成最终的 `Queue` 对象。
- `@Bean public DirectExchange deadLetterExchange()`：死信交换机，专门收“坏消息”。
- `@Bean public Queue parseDeadLetterQueue()`：死信队列，坏消息的“停尸房”，等人来排查。
- `@Bean public Binding parseBinding()`：把业务队列绑到业务交换机，routing key 是 `document.parse`。`BindingBuilder.bind(队列).to(交换机).with(routingKey)` 是 Spring AMQP 的链式写法。
- `@Bean public Binding deadLetterBinding()`：把死信队列绑到死信交换机。

这段代码跑起来后，RabbitMQ 里会自动出现这些交换机/队列（Spring Boot 启动时用这些 Bean 声明拓扑）。**验证**：重启应用后到管理台 Queues 页面，应该能看到 `learnhub.document.parse.queue` 和 `learnhub.document.parse.dlq` 两个队列。

## 6. Step 6：消息体

新建 `learnhub-knowledge/src/main/java/com/github/comui520/learnhub/knowledge/mq/DocumentParseMessage.java`：

```java
package com.github.comui520.learnhub.knowledge.mq;

public record DocumentParseMessage(Long fileId) {
}
```

- record 是 Java 16+ 的简洁不可变类，构造器和 getter 自动生成（访问方式是 `fileId()`）。
- 消息体只放 `fileId`：解析是文件级的，一个文件不管绑几个知识库只解析一次；知识库归属不参与消息（检索时按“库绑定的 fileId 列表”过滤，Part 5 讲过）。**不要放文件内容**——文件在 MinIO，消息里只放指针。

## 7. Step 7：绑定成功后发消息（结合你自己的代码）

`DocumentService.upload(userId, file)` 只建文件；`KnowledgeBaseService.bindDocuments(userId, kbId, fileIds)` 才把文件挂进知识库。**发消息的时机在绑定后**——文件只有绑定了才说明“要用了”，才需要解析。所以这步改的是 `KnowledgeBaseService`，不改 `DocumentService`。

### 7.1 注入 RabbitTemplate

给 `KnowledgeBaseService` 加字段和构造器参数：

```java
private final KnowledgeBaseDocumentMapper knowledgeBaseDocumentMapper;
private final DocumentFileMapper documentFileMapper;
private final RabbitTemplate rabbitTemplate;

public KnowledgeBaseService(
        KnowledgeBaseDocumentMapper knowledgeBaseDocumentMapper,
        DocumentFileMapper documentFileMapper,
        RabbitTemplate rabbitTemplate
) {
    this.knowledgeBaseDocumentMapper = knowledgeBaseDocumentMapper;
    this.documentFileMapper = documentFileMapper;
    this.rabbitTemplate = rabbitTemplate;
}
```

import 加：`org.springframework.amqp.rabbit.core.RabbitTemplate`。

### 7.2 在“绑定插入成功之后”发消息

在 `bindDocuments` 里，`knowledgeBaseDocumentMapper.insert(...)` 之后，对**这次实际插入的**每个关联发消息：

```java
for (KnowledgeBaseDocument relation : knowledgeBaseDocumentList) {
    sendParseMessage(relation.getFileId());
}
```

> 为什么用 `knowledgeBaseDocumentList` 而不是请求里的全部 fileId：这个列表已经是“同库去重后真正插入的”关联，重复绑定不会重复触发解析。

### 7.3 加一个私有方法

```java
private void sendParseMessage(Long fileId) {
    try {
        rabbitTemplate.convertAndSend(
                DocumentParseRabbitConfig.EXCHANGE,
                DocumentParseRabbitConfig.ROUTING_KEY,
                new DocumentParseMessage(fileId)
        );
        log.info("parse message sent: fileId={}", fileId);
    } catch (Exception e) {
        log.error("send parse message failed, will retry later: fileId={}", fileId, e);
    }
}
```

理解：

- `convertAndSend(交换机名, routing key, 消息体)`：发消息，一行搞定。消息体会被 Jackson 转成 JSON。
- **为什么 catch 住不抛**：关联已经建好了，消息只是“通知”，没发出去不该让绑定接口 500。任务状态以后靠启动补偿补发（Session C）——这就是“数据库是真相，消息是通知”。
- `log.info` / `log.error`：Part 2 学的日志用法，这里要记下“发了 / 没发”，排查全靠它。

> 提示：`KnowledgeBaseService` 现在没有日志字段，用 `log` 前先给类加 `@Slf4j`（lombok，Part 2 用过）。

## 8. Step 8：验证

1. 重启应用，日志里没有报错。
2. 打开管理台 Queues 页面，确认两个队列都在。
3. 用 Swagger 先上传一个文档（`POST /api/v1/document`），拿到响应里的 `fileId`。
4. 再调绑定接口（`POST /api/v1/knowledge-bases/bind-document`），body 里带 `knowledgeBaseId` 和刚才的 `documentFileIds`。
5. 回到管理台，看到 `learnhub.document.parse.queue` 的 Ready 从 0 变 1。
6. 点进队列 → Get Message → Get，能看到一条 JSON，形如：

```json
{"fileId":1}
```

这就证明：发送方代码通了、JSON 转换器生效了、消息在“绑定”时真的进了队列。

## 9. 主动制造错误

**错误 A：停掉 RabbitMQ 再绑定**

```powershell
docker compose stop rabbitmq
```

先上传一个文档（上传本来就不发消息，正常成功），再调绑定接口：应该仍然成功（catch 生效），但管理台打不开、消息没发出去。看应用日志有没有 “send parse message failed”。恢复：`docker compose start rabbitmq`。注意：这条消息丢了，Session C 的启动补偿会解决。

**错误 B：注释掉 messageConverter Bean**

把 `RabbitConfig` 里的 `@Bean` 注释掉，重启，重新上传，Get Message 看消息体——变成一坨 Java 序列化乱码（默认 `SimpleMessageConverter` 的杰作）。恢复注释，再发一条，又是 JSON。

## 10. 复盘题（做完要能口头回答）

1. 为什么解析要异步？同步解析的三个问题分别是什么？
2. Exchange / Queue / Routing Key / Binding 的关系，用“邮局”类比讲一遍。
3. durable 队列 vs 非 durable 队列，RabbitMQ 重启后消息还在吗？（提示：队列 durable + 消息持久化都要满足）
4. 为什么发消息失败要 catch 而不是让上传 500？“数据库是真相”是什么意思？
5. 5672 和 15672 分别是什么端口？
6. 消息发成功了但消费者还没处理，应用重启，消息会丢吗？

完成后进入 [Session B](session-b-parse-consumer.md)：写消费者。
