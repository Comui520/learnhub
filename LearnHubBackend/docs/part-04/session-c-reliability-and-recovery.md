# Session C：有限重试 + 死信 + 启动补偿 + 失败重跑 + 任务列表

> 目标：把 Session B 的“失败就 ack”升级成“失败最多重试 3 次，仍失败进死信队列”；应用重启后自动补发未完成任务；提供失败任务手动重跑接口；任务列表支持分页和状态筛选。
> 档位：概念 🧑‍🏫 我带，实现 🤝 + 🏃（我给完整参考，但建议先自己写再对答案）。预计 4～5 小时。

## 0. 本 Session 要解决的问题

Session B 的消费者有个明显缺陷：解析失败后只是把状态标成 FAILED，消息被 ack 掉，再也没有人管它了。真实系统里，很多失败是暂时的（数据库抖动、网络抖动），应该自动重试几次；实在不行，也要让消息进入“死信队列”等人排查，而不是消失。

## 1. 失败场景总览

| 场景 | 方案 |
|---|---|
| 暂时失败（DB 抖动、网络） | 有限重试：最多 3 次，间隔递增 |
| 重试仍失败（消息本身有问题） | 死信队列：进 DLQ 人工排查 |
| 应用崩溃、重启 | 未 ack 的消息自动重投 + 启动补偿 |
| RabbitMQ 不可用时上传 | 上传照常成功，任务留 PENDING，启动补偿重发 |

核心思想再强调一遍：**数据库是唯一真相，消息只是通知**。任务状态、重试次数都在 `document_task` 表里；消息丢了大不了重发。

## 2. 有限重试 + 死信（🧑‍🏫 我带）

### 2.1 原理：重试拦截器

Spring Retry 提供一个“拦截器”（`RetryOperationsInterceptor`），包在消费者方法外面：方法抛异常 → 拦截器捕获 → 等一会儿 → 重新调用你的方法 → 又抛 → 再等 → 再调用……直到次数用完 → 调用 recoverer（善后器）。

```text
第 1 次调用 onParse → 抛异常
    ↓ 等 1 秒
第 2 次调用 onParse → 抛异常
    ↓ 等 2 秒
第 3 次调用 onParse → 抛异常
    ↓ 次数用完
RejectAndDontRequeueRecoverer：拒绝消息且不重回队列
    ↓
消息进死信队列（因为业务队列配了 DLX + DLQ）
```

### 2.2 消费者改成“失败先记录，再抛异常”

Session B 里失败分支 catch 住就结束了。现在要让失败能触发重试，必须把异常再抛出去。修改 `DocumentParseConsumer` 的 catch 块，最后加一行：

```java
} catch (Exception e) {
    // TODO 3b：更新 document_file.status = FAILED、task.status = FAILED、retryCount + 1、lastError
    log.error("parse document failed: fileId={}", fileId, e);
    throw e;   // 关键：把异常交给重试拦截器
}
```

注意顺序：**先更新数据库，再抛异常**。这样即使应用在重试过程中崩溃，数据库里也已经记了失败次数；重启后补偿逻辑可以接着这个次数继续，不会因为“进程内重试”而丢失次数。

### 2.3 配置容器工厂和重试拦截器

新建 `learnhub-knowledge/src/main/java/com/github/comui520/learnhub/knowledge/config/RabbitListenerConfig.java`：

```java
package com.github.comui520.learnhub.knowledge.config;

import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
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
                .maxAttempts(3)
                .backOffOptions(1000, 2.0, 10000)
                .recoverer(new RejectAndDontRequeueRecoverer())
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

逐行理解：

- `RetryInterceptorBuilder.stateless()`：无状态重试（每次重试都是重新调方法，不需要跨请求状态）。与之相对 stateful 用于“消息是否处理过”需要跟踪的场景，面试知道有这个区别就行。
- `.maxAttempts(3)`：包括第一次在内最多调用 3 次。
- `.backOffOptions(1000, 2.0, 10000)`：退避参数，三个值依次是 initialInterval = 1000ms（第一次失败后等 1 秒）、multiplier = 2.0（每次翻倍：1s → 2s → 4s）、maxInterval = 10000ms（上限 10 秒）。
- `.recoverer(new RejectAndDontRequeueRecoverer())`：3 次都失败后调用它。它抛 `AmqpRejectAndDontRequeueException`，Spring 会把消息 reject 且 `requeue=false` → 因为业务队列配了死信参数，消息进 DLQ。名字直译：“拒绝且不重新入队”。
- `SimpleRabbitListenerContainerFactory`：监听容器的“工厂”。默认情况下 Spring Boot 自动创建了一个，现在我们自己定义一个，并：
  - `setConnectionFactory`：告诉它连哪个 RabbitMQ。
  - `setMessageConverter`：JSON 转换器，和发送方保持一致。
  - `setAdviceChain(retryInterceptor)`：把重试拦截器装进监听流程。
- 方法参数直接要这三个 Bean，由 Spring 自动注入：`ConnectionFactory` 和 `MessageConverter` 是 Boot/我们配好的，`retryInterceptor` 是同一个类里的 `@Bean`。这就是 IOC 的日常用法。

### 2.4 让消费者使用这个容器工厂

改 `@RabbitListener` 注解，加 `containerFactory` 属性：

```java
@RabbitListener(
        containerFactory = "rabbitListenerContainerFactory",
        queues = DocumentParseRabbitConfig.PARSE_QUEUE
)
public void onParse(DocumentParseMessage message) { ... }
```

`containerFactory` 的值是容器工厂 Bean 的名字。不加的话，用默认工厂（没有重试拦截器）。

### 2.5 验证：亲眼看到 3 次重试 + 死信

1. 把 `parseContent()` 改成 `throw new RuntimeException("boom");`。
2. 上传一个文档（或管理台 Requeue 一条消息）。
3. 观察应用日志：同一个 fileId 的 “parse document failed” 出现 3 次，中间间隔约 1 秒、2 秒。
4. 管理台：消息从业务队列消失，`learnhub.document.parse.dlq` 里出现 1 条 Ready（消息“死”了，去了停尸房）。
5. 查库：

```sql
SELECT file_id, status, retry_count, last_error FROM document_task;
SELECT id, status FROM document_file;
```

`document_task`：status = FAILED、retry_count = 3、last_error = "boom"；`document_file`：status = FAILED。

6. 改回 `Thread.sleep(1000);`。

> 面试点：Spring Retry 的重试是**进程内重试**，进程崩溃就不算了。所以重试次数必须记在数据库（`retry_count`），不能依赖 MQ 的投递次数。这也是为什么我们要“先更新数据库再抛异常”。

## 3. 启动补偿：重启后把没做完的任务重新发一遍（🏃 建议先自己写）

### 3.1 为什么需要它

两种情况会留下“做了半截”的任务：

1. 上传成功但发消息时 RabbitMQ 挂了 → 任务 PENDING（或还没建），消息没进队列。
2. 应用在消费到一半时崩溃 → 未 ack 的消息会被自动重投，但如果消息已经被 ack 而状态还没推进完，就需要补偿。

启动补偿做的事：应用启动完成后，扫描 `document_task` 里 status IN ('PENDING','RUNNING') 的任务，重新发一条解析消息。就算消息其实还在队列里，重发的消息也会被消费者的幂等检查跳过——所以补偿天然安全。

### 3.2 需求拆解

- 类名：`TaskRecoveryRunner`，放 `learnhub-knowledge` 的 `component` 包。
- 实现 `ApplicationRunner`（Spring Boot 启动完成后自动调用 `run` 方法）。
- 查任务：`documentTaskMapper.selectList(new LambdaQueryWrapper<DocumentTask>().in(DocumentTask::getStatus, "PENDING", "RUNNING"))`。
- 对每个任务：查 `DocumentFile` 拿 `file.getId()`（消息瘦身后只需要 fileId），然后 `rabbitTemplate.convertAndSend(...)`。
- 发之前打日志（fileId + 当前 retry_count），发失败 catch 住，别让 Runner 崩溃。

### 3.3 参考实现（先自己写，写完再对）

```java
package com.github.comui520.learnhub.knowledge.component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.comui520.learnhub.knowledge.config.DocumentParseRabbitConfig;
import com.github.comui520.learnhub.knowledge.entity.DocumentFile;
import com.github.comui520.learnhub.knowledge.entity.DocumentTask;
import com.github.comui520.learnhub.knowledge.mapper.DocumentFileMapper;
import com.github.comui520.learnhub.knowledge.mapper.DocumentTaskMapper;
import com.github.comui520.learnhub.knowledge.mq.DocumentParseMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class TaskRecoveryRunner implements ApplicationRunner {

    private final DocumentTaskMapper documentTaskMapper;
    private final DocumentFileMapper documentFileMapper;
    private final RabbitTemplate rabbitTemplate;

    public TaskRecoveryRunner(DocumentTaskMapper documentTaskMapper,
                              DocumentFileMapper documentFileMapper,
                              RabbitTemplate rabbitTemplate) {
        this.documentTaskMapper = documentTaskMapper;
        this.documentFileMapper = documentFileMapper;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<DocumentTask> pending = documentTaskMapper.selectList(
                new LambdaQueryWrapper<DocumentTask>()
                        .in(DocumentTask::getStatus, "PENDING", "RUNNING"));
        for (DocumentTask task : pending) {
            DocumentFile file = documentFileMapper.selectById(task.getFileId());
            if (file == null) {
                log.warn("recovery: file not found, skip task id={}", task.getId());
                continue;
            }
            try {
                rabbitTemplate.convertAndSend(
                        DocumentParseRabbitConfig.EXCHANGE,
                        DocumentParseRabbitConfig.ROUTING_KEY,
                        new DocumentParseMessage(file.getId()));
                log.info("recovery: re-sent parse message for fileId={}", file.getId());
            } catch (Exception e) {
                log.error("recovery: re-send failed for fileId={}", file.getId(), e);
            }
        }
    }
}
```

### 3.4 验证

1. 上传一个文档（消息被正常消费，任务 SUCCESS）。
2. 手动把一条任务改成 PENDING：`UPDATE document_task SET status='PENDING' WHERE id=1;`
3. 重启应用：日志出现 `recovery: re-sent...`，消费者处理，任务又变 SUCCESS。
4. 这就是“启动补偿”。

## 4. 失败任务重跑接口（🏃 你先做，再对答案）

需求：`POST /api/v1/document/{documentId}/retry`（沿用你自己的 `/api/v1/document` 前缀），把 FAILED 的任务重新发一条解析消息。

行为：

1. 数据隔离：文档条目必须属于当前用户（复用 `documentService.getOwnedFile(userId, documentId)`，内部 join 校验 user_id，不存在 → 404）。
2. 查 `document_task`：不存在 → 404；status != FAILED → 409（业务错误码自己加一个，比如 `TASK_NOT_FAILED`）。
3. 重置：`retryCount = 0`、`lastError = null`、`status = PENDING`，updateById。
4. 发消息（复用 Session A 的 send 逻辑）。
5. 返回 `ApiResponse.success()`。

提示：Controller 加 `@PostMapping("/{id}/retry")`，Swagger 标注 200/401/404/409。

参考 Service 核心逻辑：

```java
// 数据隔离 + 拿到文件 id（Part 3 拆表后，文档 id = fileId，文件本体）
DocumentFile file = documentService.getOwnedFile(userId, documentId);

DocumentTask task = documentTaskMapper.findByFileId(file.getId());
if (task == null) {
    throw new BusinessException(KnowledgeErrorCode.TASK_NOT_FOUND);
}
if (!"FAILED".equals(task.getStatus())) {
    throw new BusinessException(KnowledgeErrorCode.TASK_NOT_FAILED);
}

task.setStatus("PENDING");
task.setRetryCount(0);
task.setLastError(null);
task.setUpdatedAt(LocalDateTime.now());
documentTaskMapper.updateById(task);

// 发消息，失败也要 catch + log，不让接口 500
```

验证：把一条任务改成 FAILED → 调 retry 接口 → 任务重新被消费 → SUCCESS。status 是 RUNNING 时调 retry → 409。

### 4.1 状态和错误信息必须保持一致

`last_error` 表示最近一次失败的诊断信息，不是永久日志。状态转换时要遵守以下约定：

```text
FAILED  -> PENDING：清空 last_error，准备一次新的尝试
RUNNING -> SUCCESS：清空 last_error，表示本次成功
RUNNING -> FAILED ：写入本次异常信息
```

否则会出现 `status = SUCCESS` 但 `last_error` 仍然保存旧错误的矛盾数据，前端和运维人员会误判任务状态。

## 5. 任务列表接口：XML + 分页（🏃 你做，primer 派上用场）

需求：`GET /api/v1/knowledge-bases/{kbId}/tasks?status=&page=1&size=10`，返回该知识库的任务列表（含文档文件名），支持状态筛选和分页，只能看自己的数据。

### 5.1 DTO

任务列表要带文件名，`DocumentTask` 表里没有，所以要 join `document_file` 表；还要按知识库过滤，所以要再 join `knowledge_base_document`。结果用一个 DTO。注意：MyBatis 映射需要 setter，所以用 `@Data` 类，不用 record：

```java
package com.github.comui520.learnhub.knowledge.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TaskWithDocument {
    private Long taskId;
    private Long fileId;
    private String fileName;
    private String status;
    private Integer retryCount;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

### 5.2 Mapper 方法 + XML

在 `DocumentTaskMapper` 加方法（`Page` 参数放第一位，分页插件靠它识别）：

```java
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.comui520.learnhub.knowledge.dto.TaskWithDocument;

IPage<TaskWithDocument> selectTaskPage(Page<TaskWithDocument> page,
                                       @Param("userId") Long userId,
                                       @Param("kbId") Long kbId,
                                       @Param("status") String status);
```

XML（新建 `learnhub-knowledge/src/main/resources/com/github/comui520/learnhub/knowledge/mapper/DocumentTaskMapper.xml`，路径跟其他 Mapper.xml 一致）：

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper
        PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.github.comui520.learnhub.knowledge.mapper.DocumentTaskMapper">

    <select id="selectTaskPage" resultType="com.github.comui520.learnhub.knowledge.dto.TaskWithDocument">
        select t.id          as task_id,
               t.file_id,
               df.file_name,
               t.status,
               t.retry_count,
               t.last_error,
               t.created_at,
               t.updated_at
        from document_task t
                 join document_file df on df.id = t.file_id
                 join knowledge_base_document kbd on kbd.file_id = t.file_id
        where kbd.knowledge_base_id = #{kbId}
          and df.user_id = #{userId}
        <if test="status != null and status != ''">
            and t.status = #{status}
        </if>
        order by t.created_at desc
    </select>

</mapper>
```

讲解：

- `resultType` 是 DTO，列名 `task_id` 自动映射到 `taskId`（MyBatis-Plus 默认开启驼峰映射）。
- `join`：任务表 join 文件表拿 `file_name`，再 join 关联表按知识库过滤；筛选条件挂在 `df`（user_id）和 `kbd`（knowledge_base_id）上，顺带实现“只能看自己的”。
- `<if>`：MyBatis 动态 SQL，status 为空就整段不拼接，实现“可选筛选”。primer（part-03）里学过。
- `Page` 参数：分页插件（你 Part 3 已经在 `MybatisPlusConfig` 配了 `PaginationInnerInterceptor`）识别第一个 `Page` 参数，自动在 SQL 外面包 LIMIT，并生成 COUNT 查询。

### 5.3 Controller

在 `DocumentController` 加（路径前缀 `/api/v1/document` 是你自己的选择，这里示例用需求里的 `/knowledge-bases/{kbId}/tasks`，二选一即可，保持一致就行）：

```java
@GetMapping("/api/v1/knowledge-bases/{kbId}/tasks")
public ApiResponse<IPage<TaskWithDocument>> taskList(
        @PathVariable Long kbId,
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "1") long page,
        @RequestParam(defaultValue = "10") long size) {
    Long userId = currentUser.currentUserId();
    return ApiResponse.success(documentService.pageTasks(userId, kbId, status, page, size));
}
```

职责：取 userId → 调 service → 返回。别忘了 `@Operation` / `@ApiResponses`（类上已有 `@SecurityRequirement`）。

### 5.4 验证

- 正常：page=1&size=1，返回 total、记录数正确，SQL 日志能看到 LIMIT。
- 筛选：status=FAILED 只返回失败的。
- 隔离：换一个用户访问，返回空列表（不能看到别人的任务）。

## 6. 最终验收（Part 4）

> 测试按你的要求后置到 Part 7，下面全部是手动验证项。

- [ ] 上传 → 自动解析 → COMPLETED；管理台消息进队出队正常。
- [ ] 模拟重复投递：不重复处理（幂等），`document_task` 只有一行 SUCCESS。
- [ ] 模拟失败：重试 3 次 → 进 DLQ，retry_count=3，last_error 有内容。
- [ ] 重启应用：PENDING/RUNNING 任务自动补发。
- [ ] 失败任务能通过 retry 接口重跑。
- [ ] 任务列表接口：分页 + 状态筛选 + 只能看自己的。
- [ ] `docker compose stop rabbitmq` 时上传不炸；恢复后靠启动补偿补发成功。
- [ ] 更新 `LEARNHUB_PLAN.md`：勾选 Part 4，追加决策记录（重试策略、幂等方案、补偿机制）。
- [ ] 能回答 README 里的 6 道答辩题。

## 7. 复盘题

1. “有限重试 + 死信”和“无限重试”的差别？死信队列里躺着的消息通常意味着什么？
2. 为什么 `retry_count` 要记数据库而不是信 MQ 的投递次数？
3. 启动补偿为什么天然幂等？（提示：消费者侧的幂等检查）
4. “至少一次投递 + 幂等消费 + 补偿”三个机制分别解决什么问题？组合起来达到什么效果？
5. XML Mapper 和 `@Select` 你这次分别在什么时候用了？选择依据是什么？

完成 Part 4 验收后，进入 **Part 5：RAG 与流式问答**——真正的文档解析（PDF/Word/Markdown → 文本 → 向量）和 Spring AI。
