# 前置教学：RabbitMQ 与 Spring AMQP 从零认识

> 阅读对象：完全没用过消息队列的你。
> 目标：学完这一篇，你能用自己的话解释“一条消息从发送到消费发生了什么”，并且看得懂后面 Session 里的每一行代码。
> 建议：边读边打开管理台动手点一点，光看记不住。

## 1. 消息队列解决什么问题

### 1.1 先看没有 MQ 时我们是怎么做事的

现在的上传接口是同步的：客户端请求 → 存文件 → 建记录 → 返回。如果下一步要“解析文档”，同步做就是这样：

```text
请求 → 存文件 → 建记录 → 解析（可能 30 秒）→ 返回
```

三个问题：

1. 用户要等 30 秒，体验差，HTTP 请求容易超时。
2. 解析是 CPU/内存重活，占住 Tomcat 的工作线程，其他请求排队。
3. 解析失败要重试，但请求早就结束了，没人知道、没人管。

### 1.2 消息队列怎么解决

把“解析”从请求里拿出来，扔进一个“信箱”，立刻返回 200。另一个程序（消费者）从信箱里拿任务慢慢做。

类比：

- **食堂点餐**：你点完单拿小票走人（立刻返回），后厨做好叫号（异步）。而不是在窗口站着等 10 分钟。
- **寄快递**：你填单（发消息），快递公司分拣运输（队列流转），收件人签收（消费）。寄件人不需要跟着货车跑。

### 1.3 三个术语先认识

| 术语 | 大白话 | 本项目的对应 |
|---|---|---|
| Producer 生产者 | 发消息的人 | 上传接口 |
| Broker 代理 | 消息中转站本身 | RabbitMQ 服务 |
| Consumer 消费者 | 收消息干活的人 | 文档解析 Worker |

## 2. 一条消息的完整旅程

```text
生产者（上传接口）
   │  RabbitTemplate.convertAndSend(exchange, routingKey, 消息)
   ▼
Exchange 交换机（邮局分拣台）
   │  按 Binding 规则匹配
   ▼
Queue 队列（收件人的信箱）
   │
   ▼
消费者（@RabbitListener）
```

### 2.1 Connection 和 Channel

RabbitMQ 客户端和服务器之间先建立一条 TCP 长连接（Connection）。一条连接里可以开很多条“虚拟通道”（Channel）并发收发，避免每个线程都开一条 TCP。

Spring AMQP 的 `CachingConnectionFactory` 帮你管理连接和通道，日常代码里你基本不直接碰它们，但面试会问。

### 2.2 Exchange（交换机）：消息先到它这里

消息不会直接进队列，而是先到交换机。交换机不存消息，它只负责“看信封上的地址，决定投到哪个信箱”。

我们用的 `DirectExchange` 规则最简单：**Routing Key 完全相等才投递**。比如交换机上绑定了：

- 队列 A 绑定 routing key = `document.parse`
- 队列 B 绑定 routing key = `document.delete`

发一条 routing key = `document.parse` 的消息 → 只进队列 A。

> 常见的还有 `FanoutExchange`（广播给所有绑定的队列）、`TopicExchange`（通配符匹配 `*` 和 `#`）。现在只用 Direct，面试被问能说出区别即可。

### 2.3 Routing Key（路由键）：信封上的地址

就是一条字符串。生产者发消息时带上它，交换机靠它找队列。

### 2.4 Binding（绑定）：分拣规则

“把队列 X 绑到交换机 Y，当 routing key = Z 时投进去”。一条 binding 就是一条规则。管理台 Exchanges 页面点进某个交换机，能看到它下面的所有 binding。

### 2.5 Queue（队列）：消息排队等消费

队列是真正存消息的地方。关键属性：

- **durable（持久化）**：队列的“定义”在 RabbitMQ 重启后还在。注意：队列 durable 只保证队列本身不丢，**不保证队列里的消息不丢**。
- **消息持久化**：发消息时标记 DeliveryMode = PERSISTENT，消息本体写磁盘。**队列 durable + 消息持久化**两者都满足，RabbitMQ 重启才不丢消息。

### 2.6 Consumer 与 ack（回执）

消费者取走消息后，要告诉 RabbitMQ“我处理好了 / 处理砸了”。这声回执叫 ack。

- 处理成功 → ack：消息从队列删除。
- 处理失败或应用崩溃，没有回执 → RabbitMQ 认为消息还在，会重新投递给（可能是另一个）消费者。

这就是“**至少一次投递**”的由来——你永远无法保证消息只被处理一次，只能保证“至少一次”，所以消费者必须幂等（见第 4 节）。

## 3. 动手：先打开管理台看看（10 分钟）

1. 启动 RabbitMQ（在 `LearnHubBackend` 目录）：

   ```powershell
   docker compose up -d rabbitmq
   ```

2. 打开 <http://localhost:15672>，用 `.env` 里的账号（`learnhub` / `1234`）登录。
3. 认页面：
   - **Overview**：集群概况、连接数、队列数。
   - **Queues and Streams**：队列列表，现在应该是空的。
   - **Exchanges**：交换机列表，能看到系统自带的 `amq.direct` 等。
4. 不用写代码试一发：
   - Exchanges → 选 `amq.default` → Publish message，Routing key 填 `my.test.queue`，payload 填 `hello`，点 Publish。
   - 到 Queues 页面，看到出现了一个 `my.test.queue`（Ready = 1）。点进去 → Get Message → Get，把消息取出来。

这就完成了一次完整的“发消息 → 进队列 → 消费”。后面代码做的事和这个一模一样。

## 4. 至少一次投递 & 幂等

### 4.1 为什么会有重复消息

RabbitMQ 的投递语义是 **at-least-once（至少一次）**，不是 exactly-once（恰好一次）。重复可能来自：

1. 消费者处理成功了，但在回执 ack 之前连接断开/超时 → RabbitMQ 重投。
2. 消费者处理到一半崩溃 → 没 ack → 重投。
3. 网络抖动导致 ack 丢了 → 重投。
4. 我们自己的补偿任务把消息重发了（Session C）。

所以“同一业务消息被消费两次”是常态，不是 bug。

### 4.2 幂等 = 处理两遍等于处理一遍

幂等（idempotent）：同一个操作做 1 次和做 N 次，结果一样。

本项目方案：**数据库任务状态是唯一真相**。消费者先查 `document_task`：

```text
status 已是 SUCCESS → 什么都不做，直接 ack
status 不是 SUCCESS → 正常处理
```

再配合唯一索引兜底，防止并发下两条相同消息同时进来都执行（Session B 讲）。

### 4.3 三种确认模式（AUTO / MANUAL / NONE）

| 模式 | 谁决定 ack | 方法抛异常时 |
|---|---|---|
| AUTO（默认） | Spring 自动 ack | 默认把消息重新放回队列（无限重试） |
| MANUAL | 你自己调 `channel.basicAck` / `basicNack` | 你说了算 |
| NONE | 不确认 | Broker 认为已投递 |

我们先用 AUTO，Session C 加“有限重试 + 死信”，把它修成“最多试 3 次，再失败进死信队列”。

## 5. Spring AMQP：Java 这边怎么对应

| RabbitMQ 概念 | Spring AMQP 里是谁 |
|---|---|
| Connection / Channel | `CachingConnectionFactory`（Boot 自动配置，不用自己建） |
| 发消息 | `RabbitTemplate.convertAndSend(exchange, routingKey, object)` |
| 收消息 | `@RabbitListener(queues = "xxx")` 标记的方法 |
| 消息序列化 | `Jackson2JsonMessageConverter`（Java 对象 ↔ JSON） |
| 队列/交换机/绑定 | 在 `@Configuration` 里声明 `@Bean` |
| 失败重试 | `RetryOperationsInterceptor` + 监听容器工厂 |

### 5.1 依赖怎么写

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
```

- 这是 Spring Boot 官方 starter，自动配置 ConnectionFactory、RabbitTemplate、监听容器。
- 它传递带出 `spring-amqp`（API）、`amqp-client`（RabbitMQ 官方 Java 客户端）、`spring-retry`（重试支持）——所以后面的 `RetryOperationsInterceptor` 不用再单独加依赖。
- 我们把它放在 `learnhub-infrastructure`（外部系统适配层）；`learnhub-knowledge` 依赖 infrastructure，会传递拿到，不用重复声明。

### 5.2 连接配置（application-dev.yml）

```yaml
spring:
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_DEFAULT_USER:learnhub}
    password: ${RABBITMQ_DEFAULT_PASS:1234}
```

和数据库一样的教训：`.env` 只喂 Docker Compose，Java 进程要么给环境变量，要么用默认值兜底。

### 5.3 发消息：RabbitTemplate

```java
rabbitTemplate.convertAndSend(
        "learnhub.document.exchange",   // 交换机名
        "document.parse",               // routing key
        new DocumentParseMessage(1L));  // 消息体（瘦身后只有 fileId）
```

- `convertAndSend`：把对象序列化（JSON）后发出去。第一个参数是交换机名，第二个是 routing key，第三个是消息体。
- 如果只想发到默认交换机（routing key 直接当队列名），可以省略交换机参数：`convertAndSend("队列名", 消息)`。

### 5.4 收消息：@RabbitListener

```java
@Component
public class DemoConsumer {
    @RabbitListener(queues = "my.test.queue")
    public void onMessage(String payload) {
        System.out.println("收到：" + payload);
    }
}
```

方法正常返回后 Spring 自动 ack；方法抛异常则按容器配置处理（默认重回队列）。

### 5.5 为什么都要配 JSON 转换器

默认的 `SimpleMessageConverter` 用 Java 对象序列化（JDK Serializable），消息是乱码、跨语言没法读。配了 `Jackson2JsonMessageConverter` 后：

- 发送方：`DocumentParseMessage` record → JSON 字符串。
- 接收方：JSON → `DocumentParseMessage` record（靠消息头里的类型信息反序列化，所以发和收两边的 record 包名、字段要一致）。

## 6. 死信队列（DLQ）一句话

“死信”= 不想要了 / 处理不了的消息。三种情况会变死信：

1. 消费者显式拒绝（`basic.reject` / `basicNack`）且 `requeue=false`。
2. 消息过期（TTL）。
3. 队列满了。

死信队列就是给这些消息准备的“处理不了，先放这”，方便人工排查和重放。实现方式：业务队列上配置 `x-dead-letter-exchange`（死信交换机）+ `deadLetterRoutingKey`。

## 7. 现在可以开始 Session A 了

概念都认识后，[Session A](session-a-rabbitmq-basics.md) 会把它们一个一个变成你项目里的代码。
