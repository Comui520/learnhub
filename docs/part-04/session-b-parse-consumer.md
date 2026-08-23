# Session B：写消费者：收消息 → 改状态 → 幂等

> 目标：写一个 `@RabbitListener` 消费者，收到解析消息后把文档状态从 UPLOADED 推到 PARSING → COMPLETED/FAILED，并保证重复消息不会重复处理。
> 档位：概念 🧑‍🏫 我带，代码 🤝 各写一半（骨架我给，3 个 TODO 你补）。预计 3～4 小时。

## 0. 本 Session 完成时的样子

上传 → 队列里出现消息 → 消费者立刻拿走 → 1 秒后（模拟解析）→ `document_file.status = COMPLETED`、`document_task.status = SUCCESS`。

如果解析失败：`document_file.status = FAILED`、`document_task.status = FAILED`、`retry_count = 1`（重试和死信是 Session C 的事，今天先记下来）。

## 1. 状态机回顾

Part 3 设计了状态枚举：

```text
UPLOADED -> PARSING -> COMPLETED
                \-> FAILED
```

Part 4 的“解析”是占位实现（`Thread.sleep` 模拟耗时），Part 5 换成真的读文件、切文本。所以今天核心不是解析本身，而是：

1. 消费者怎么收到消息。
2. 收到后怎么安全地推进状态。
3. 同一消息来两遍，不能把文档处理两遍。

## 2. @RabbitListener 是怎么工作的

- Spring Boot 启动时扫描 `@RabbitListener` 方法，为它创建一个“监听容器”（`SimpleMessageListenerContainer`）。
- 容器维护一组消费者线程，从队列取消息，调用你的方法。
- 方法正常返回 → 自动 ack（回执）。
- 方法抛异常 → 按确认模式和重试配置处理（默认 AUTO + 无重试拦截器 = 消息重新放回队列，无限重试）。

所以你的方法只管“业务逻辑”，连接、取消息、回执都交给框架。这和写 Controller 很像：框架负责收 HTTP 请求，你只写方法体。

## 3. 幂等：为什么必须做

RabbitMQ 只保证至少一次投递。重复的可能场景：

1. 处理成功但 ack 丢了 → 重投。
2. 处理到一半崩溃 → 没 ack → 重投。
3. 网络抖动。
4. 以后 Session C 的启动补偿还会主动重发。

所以消费者收到消息，第一件事不是处理，而是问数据库：“这个任务我处理过了吗？”

判断依据用 `document_task` 表（数据库是真相，消息只是通知）：

```text
task.status == SUCCESS → 已处理过，直接返回（什么都不做）
task.status != SUCCESS → 正常处理
```

## 4. Step 1：确认 DocumentTask 实体 + 唯一索引

> 提醒：Part 3 重构后（[Session D](../part-03/session-d-model-refactor-to-many-to-many.md)），解析任务关联的是 `document_file`，所以实体字段叫 `fileId`、数据库列叫 `file_id`，**不是** documentId/document_id。先确认你的 `DocumentTask` 是下面这个状态。

### 确认 1：字段

```java
@Data
@TableName("document_task")
public class DocumentTask {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long fileId;        // 指向 document_file.id

    private String type;        // "PARSE"

    private String status;      // PENDING/RUNNING/SUCCESS/FAILED

    private Integer retryCount;

    private String lastError;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
```

### 确认 2：唯一索引（幂等的数据库兜底）

`document_task.file_id` 现在是普通索引（V7 拆分时只改了列名）。逻辑上一个文件同一类型只有一条任务，应该建唯一索引：并发下两条相同消息同时进来，两个消费者都先查“没有任务”，然后都 insert——唯一索引让第二个 insert 报错，这就是数据库层兜底。

V7 已经应用到你数据库了，所以新建 **V8**（版本号继续从全局最大值 +1，放 `learnhub-knowledge/src/main/resources/db/migration/`）：

```sql
-- V8__add_document_task_unique_index.sql
ALTER TABLE `document_task`
    DROP INDEX `idx_document_id`,
    ADD UNIQUE KEY `uk_file_id` (`file_id`);
```

> 注意：V6 建表时索引名叫 `idx_document_id`，V7 把列改名为 `file_id` 但索引名没变，所以这里 `DROP INDEX idx_document_id` 删的还是那个旧索引。

重启应用让 Flyway 执行 V8，然后 `SELECT * FROM flyway_schema_history` 确认 success = 1。

## 5. Step 2：DocumentTaskMapper

新建 `learnhub-knowledge/src/main/java/com/github/comui520/learnhub/knowledge/mapper/DocumentTaskMapper.java`：

```java
package com.github.comui520.learnhub.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.github.comui520.learnhub.knowledge.entity.DocumentTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DocumentTaskMapper extends BaseMapper<DocumentTask> {

    @Select("SELECT * FROM `document_task` WHERE file_id = #{fileId} LIMIT 1")
    DocumentTask findByFileId(@Param("fileId") Long fileId);
}
```

和 Part 3 你手写的 Mapper 一个套路：继承 `BaseMapper` 白拿增删改查，特殊查询自己写 `@Select`。

## 6. Step 3：消费者骨架（我写骨架，你补 3 个 TODO）

新建 `learnhub-knowledge/src/main/java/com/github/comui520/learnhub/knowledge/consumer/DocumentParseConsumer.java`：

```java
package com.github.comui520.learnhub.knowledge.consumer;

import com.github.comui520.learnhub.knowledge.DocumentStatus;
import com.github.comui520.learnhub.knowledge.config.DocumentParseRabbitConfig;
import com.github.comui520.learnhub.knowledge.entity.DocumentFile;
import com.github.comui520.learnhub.knowledge.entity.DocumentTask;
import com.github.comui520.learnhub.knowledge.mapper.DocumentFileMapper;
import com.github.comui520.learnhub.knowledge.mapper.DocumentTaskMapper;
import com.github.comui520.learnhub.knowledge.mq.DocumentParseMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
public class DocumentParseConsumer {

    private final DocumentFileMapper documentFileMapper;
    private final DocumentTaskMapper documentTaskMapper;

    public DocumentParseConsumer(DocumentFileMapper documentFileMapper, DocumentTaskMapper documentTaskMapper) {
        this.documentFileMapper = documentFileMapper;
        this.documentTaskMapper = documentTaskMapper;
    }

    @RabbitListener(queues = DocumentParseRabbitConfig.PARSE_QUEUE)
    public void onParse(DocumentParseMessage message) {
        Long fileId = message.fileId();

        // TODO 1：幂等检查
        // documentTaskMapper.findByFileId(fileId)
        // 如果存在且 status 是 SUCCESS，直接 return（重复消息，什么都不做）
        // 如果不存在，先插入一条 PENDING 任务（retryCount = 0），再继续

        // TODO 2：把状态推到 PARSING
        // file.status = PARSING，更新 document_file 表
        // task.status = RUNNING，更新 task 表

        try {
            parseContent();
            // TODO 3a：解析成功
            // file.status = COMPLETED
            // task.status = SUCCESS
        } catch (Exception e) {
            // TODO 3b：解析失败
            // file.status = FAILED
            // task.status = FAILED, retryCount + 1, lastError = e.getMessage()
            log.error("parse document failed: fileId={}", fileId, e);
        }
    }

    private void parseContent() throws Exception {
        // 占位实现：模拟解析 1 秒。Part 5 换成真解析。
        Thread.sleep(1000);
    }
}
```

### 每个 TODO 你该怎么补

**TODO 1 幂等检查**：先查 task。

- 查到且 status 是 `SUCCESS` → return（方法正常返回 = ack，消息被消费掉，什么也不做）。
- 没查到 → `new DocumentTask()`，设 `fileId`、`type = "PARSE"`、`status = PENDING`、`retryCount = 0`、`createdAt/updatedAt = LocalDateTime.now()`，`documentTaskMapper.insert(task)`。
- 查到但 status 是 PENDING / RUNNING / FAILED → 不动它，继续往下走（这就是“重试 / 补偿重发”的场景）。

**TODO 2 推状态**：

- 按 fileId 查文件：`DocumentFile file = documentFileMapper.selectById(fileId);`。查不到 → 打日志 return（文件被删了，消息作废）。
- `file.setStatus(DocumentStatus.PARSING.name()); documentFileMapper.updateById(file);`
- `task.setStatus("RUNNING"); documentTaskMapper.updateById(task);`

**TODO 3 结果**：

- 成功：`file.status = COMPLETED`；`task.status = SUCCESS`。
- 失败：`file.status = FAILED`；`task.status = FAILED`；`task.retryCount = (task.retryCount == null ? 1 : task.retryCount + 1)`；`task.lastError = e.getMessage()`（列是 VARCHAR(500)，超长要截断）。

> 思考（Session C 会用到）：今天失败分支只是记录，**不抛异常**，所以消息会被 ack 掉，不会重试。Session C 会改成“先更新数据库，再抛异常”，让重试拦截器接手。为什么顺序必须是“先更新数据库再抛”？因为数据库记录是真相，重试次数以数据库为准，不依赖 MQ 的投递次数。

## 7. Step 4：验证

### 7.1 正常流程

1. 重启应用（V8 迁移执行）。
2. Swagger 上传一个文档。
3. 管理台：消息被消费，Ready 归 0。
4. 查库：

```sql
SELECT id, status FROM document_file;
SELECT file_id, status, retry_count FROM document_task;
```

`document_file.status = COMPLETED`，`document_task.status = SUCCESS`。

### 7.2 模拟重复投递

管理台 → 业务队列 → Get Message → 选中一条 → **Requeue**（把消息放回队头）。消费者会再收到一次。因为 task 已是 SUCCESS，TODO 1 直接 return——`document_file` 不会被再次置为 PARSING/COMPLETED，任务表也只有一行。这就是幂等生效。

### 7.3 模拟失败

把 `parseContent()` 改成 `throw new RuntimeException("boom");`，重新上传一个文档。观察：

- `document_file.status = FAILED`
- `document_task.status = FAILED`、`retry_count = 1`、`last_error = "boom"`
- 队列消息被 ack 掉了（因为今天失败不抛异常，方法正常返回）

改回 `Thread.sleep(1000);`。

## 8. 主动制造错误

**错误 A：删掉 TODO 1 的幂等检查再重复投递**——`document_task` 里会出现第二条任务（没有唯一索引时）或 insert 报错（有唯一索引时）。亲手感受“唯一索引兜底”的意义。

**错误 B：不设 createdAt / updatedAt 就 insert**——查库看到 NULL（如果列允许）或插入报错。体会“实体字段和数据库列要对齐”。

## 9. 复盘题

1. AUTO / MANUAL / NONE 三种确认模式区别？AUTO + 抛异常默认会怎样？
2. “至少一次投递”为什么必然可能产生重复消息？幂等为什么必须靠业务状态而不是“消息只来一次”？
3. 消费到一半应用崩溃，消息会怎样？（提示：未 ack → 重新投递）
4. `document_task` 唯一索引在幂等里扮演什么角色？
5. 为什么“先查再改”在并发下不绝对安全？唯一索引怎么兜底？

完成后进入 [Session C](session-c-reliability-and-recovery.md)：有限重试、死信与恢复。
