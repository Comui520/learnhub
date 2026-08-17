# Session B：解析消费者 + 状态机 + 幂等

> 目标：写一个 `@RabbitListener` 消费者，把文档状态从 UPLOADED 推到 PARSING → COMPLETED/FAILED，并保证**重复消息不重复处理**。
>
> 档位：概念 🧑‍🏫 我带，代码 🤝 各写一半。预计 3～4 小时。

## 1. 消费者的三种确认模式（先搞懂）

`@RabbitListener` 处理完消息后，要向 RabbitMQ 回复“我处理好了”，三种模式：

| 模式 | 行为 | 适用 |
|---|---|---|
| AUTO（默认） | 方法正常返回 → ack；抛异常 → 消息**重回队列** | 简单场景 |
| MANUAL | 你自己调 `channel.basicAck/basicNack` | 需要精确控制 |
| NONE | 不确认，Broker 认为已投递 | 特殊场景 |

**AUTO + 抛异常 = 无限重试**：消息反复重回队列，卡死队头。Session C 我们会加“有限重试 + 死信”修复。今天先用 AUTO，重点学状态机和幂等。

## 2. 状态机回顾

```text
UPLOADED -> PARSING -> COMPLETED
                \-> FAILED
```

Part 4 的“解析”是占位实现（真实解析 Part 5 做）：收到消息 → 标记 PARSING → `Thread.sleep` 模拟解析 → 成功标 COMPLETED / 失败标 FAILED。

## 3. 幂等：为什么必须做

RabbitMQ 保证**至少一次投递**（at-least-once），不保证恰好一次：网络抖动、消费超时、Broker 重投，都可能让同一条消息被消费两次。所以消费者必须**幂等**——重复处理不产生重复效果。

我们的幂等方案：**任务状态是唯一真相**。消费者先查 `document_task`：

```text
状态已是 SUCCESS -> 直接 ack，什么都不做（重复消息）
状态是 PENDING/RUNNING/FAILED -> 正常处理
```

数据库再兜底：给 `document_task.document_id` 加唯一索引，防止并发下插入重复任务（如果 V6 还没应用，直接改 V6；已应用就新建迁移）。

## 4. 跟着做一遍（🤝 各写一半）

### Step 1：DocumentTask 实体和 Mapper

`DocumentTask` 实体字段：id、documentId、type、status、retryCount、lastError、createdAt、updatedAt（对应表）。Mapper 继承 `BaseMapper<DocumentTask>`，手写关键查询：

```java
@Mapper
public interface DocumentTaskMapper extends BaseMapper<DocumentTask> {

    @Select("SELECT * FROM `document_task` WHERE document_id = #{documentId}")
    DocumentTask findByDocumentId(@Param("documentId") Long documentId);
}
```

> 顺带练习：`document_task` 的**任务列表分页 + 状态筛选**用 XML Mapper + 分页插件实现（参考 [MyBatis-Plus 高级用法与 XML](../part-03/primer-mybatis-plus-and-xml.md)），放在 Session C 的列表接口里。

### Step 2：DocumentParseConsumer（骨架我来，三处 TODO 你补）

```java
package com.github.comui520.learnhub.knowledge.consumer;

import com.github.comui520.learnhub.knowledge.config.DocumentParseRabbitConfig;
import com.github.comui520.learnhub.knowledge.entity.Document;
import com.github.comui520.learnhub.knowledge.entity.DocumentTask;
import com.github.comui520.learnhub.knowledge.mapper.DocumentMapper;
import com.github.comui520.learnhub.knowledge.mapper.DocumentTaskMapper;
import com.github.comui520.learnhub.knowledge.mq.DocumentParseMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class DocumentParseConsumer {

    private static final Logger log = LoggerFactory.getLogger(DocumentParseConsumer.class);

    private final DocumentMapper documentMapper;
    private final DocumentTaskMapper documentTaskMapper;
    // 构造器注入

    @RabbitListener(queues = DocumentParseRabbitConfig.PARSE_QUEUE)
    public void onParse(DocumentParseMessage message) {
        Long documentId = message.documentId();

        // TODO 1：幂等检查——查 documentTask，如果 status 已经是 SUCCESS，直接 return（不重复处理）

        // TODO 2：状态 UPLOADED -> PARSING，更新 document 和 task（task 不存在就新建，retryCount 从 0 开始）

        try {
            parseContent();   // 占位：真实解析 Part 5 实现
            // TODO 3：解析成功——document.status = COMPLETED，task.status = SUCCESS
        } catch (Exception e) {
            // TODO 3：解析失败——document.status = FAILED，
            // task.status = FAILED，retryCount + 1，lastError 记录
            log.error("parse document failed: documentId={}", documentId, e);
        }
    }

    private void parseContent() throws Exception {
        // 占位实现：真实解析（PDF/Word/Markdown -> 文本）Part 5 做
        Thread.sleep(1000);
    }
}
```

思考题（做完回答）：

1. TODO 1 为什么要先查 task？如果两条相同消息同时进来（并发重复投递），只靠“先查再改”够吗？（提示：不够，要靠唯一索引兜底，或状态更新的 WHERE 条件）
2. FAILED 时 retryCount + 1 的意义是什么？Session C 怎么用这个数字决定“重试还是进死信”？

### Step 3：验证

1. 重启应用，上传一个文档 → 队列消息被消费 → 等 1 秒 → 查 MySQL：`document.status = COMPLETED`，`document_task.status = SUCCESS`。
2. **模拟重复投递**：在管理台把一条消息 Requeue，观察消费者再次收到，但任务不会被重复处理（幂等生效）。
3. **模拟失败**：把 `parseContent()` 改成 `throw new RuntimeException("boom")`，重跑，观察状态变 FAILED、retryCount=1；然后恢复。

---

## 5. 复盘题

1. AUTO / MANUAL / NONE 三种确认模式区别？AUTO + 抛异常的后果是什么？
2. “至少一次投递”为什么必然可能产生重复消息？幂等为什么必须靠业务状态而不是靠“消息只来一次”？
3. 消费到一半应用崩溃，消息会怎样？（提示：未 ack → 重新投递）
4. `document_task` 唯一索引在幂等里扮演什么角色？

完成后进入 [Session C](session-c-reliability-and-recovery.md)：有限重试、死信与恢复。
