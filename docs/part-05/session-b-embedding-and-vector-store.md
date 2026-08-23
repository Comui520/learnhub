# Session B：Embedding + 写入 Qdrant（向量入库）

> 目标：把 Session A 切好的 chunk **向量化并写进 Qdrant**，让资料真正变得“可检索”。做完这一步，Qdrant 里能看到向量数据。
> 档位：概念 🧑‍🏫 我带，代码 🤝（写入我带，删除清理你做）。预计 3～4 小时。
>
> 前置：**从这篇开始需要 API Key**。先按 primer 第 5.3 节配好 `AI_API_KEY` 环境变量再动手。

## 0. 本 Session 完成时的样子

```text
解析出 chunks
  → VectorIndexService.addChunks(chunks)
  → 每个 chunk 被 EmbeddingModel 变成向量
  → 向量 + 元数据写入 Qdrant 的 learnhub_docs collection
  → Qdrant 控制台能看到点数 = chunk 数
```

## 1. 两个新概念

- **`EmbeddingModel`**：把文本变成向量的模型。Spring AI 的 starter 自动配置好，你只管注入调用，底层是 OpenAI（或兼容服务）的 embedding API。
- **`VectorStore`**：向量库的统一接口。对 Qdrant 来说，每个 chunk 存成一条 **point**（点）：向量 + payload（元数据）。`vectorStore.add(chunks)` 一行完成“向量化 + 写入”。

**为什么能一行完成**：`VectorStore.add(List<Document>)` 内部会自动调 `EmbeddingModel` 给每个 Document 的文本算向量，再把向量和 metadata 一起存进 Qdrant。你不必自己手动调 embedding。

## 2. Step 1：解开 Qdrant 依赖

`learnhub-infrastructure/pom.xml` 里注释掉的这段解开：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-vector-store-qdrant</artifactId>
</dependency>
```

这个 starter 自动配置出一个 `VectorStore` Bean（底层连 Qdrant），knowledge 模块依赖 infrastructure，传递拿到。

## 3. Step 2：yml 配置

`application-dev.yml` 加（primer 里见过，这里正式落地）：

```yaml
spring:
  ai:
    openai:
      api-key: ${AI_API_KEY:}
      base-url: ${AI_BASE_URL:https://api.openai.com}
      chat:
        options:
          model: ${AI_CHAT_MODEL:gpt-4o-mini}
      embedding:
        options:
          model: ${AI_EMBEDDING_MODEL:text-embedding-3-small}
    vectorstore:
      qdrant:
        host: ${QDRANT_HOST:localhost}
        port: ${QDRANT_GRPC_PORT:6334}
        collection-name: learnhub_docs
```

检查：

- `AI_API_KEY` 在系统环境变量里配好（别学 Part 2 的坑，IDEA 里配环境变量不生效就放系统变量）。
- `AI_BASE_URL`：用 OpenAI 官方就保持默认；用兼容服务（DeepSeek/百炼等）改成它的地址。
- `AI_CHAT_MODEL` / `AI_EMBEDDING_MODEL`：改成你服务商提供的模型名。
- Qdrant 端口用 **6334（gRPC）**，不是 6333（HTTP/dashboard）——Spring AI 的 Qdrant starter 走 gRPC，配 6333 会在启动时卡在 healthCheck 挂起。`docker compose up -d qdrant` 先确认在跑。
- **base-url 不要带 `/v1`**：Spring AI 路径自带 `/v1`，填 `https://api.siliconflow.cn` 就行，写成 `.../v1` 会拼成 `/v1/v1/embeddings` → 404。
- collection 不存在时 Spring AI 会自动创建（启动日志能看到）；万一报错，去 `http://localhost:6333/dashboard` 手动建一个同名 collection。

> 如果启动时报“找不到 VectorStore Bean”或 Qdrant 连接失败，先看日志里的配置是否正确，再 `docker compose ps` 确认 qdrant healthy。

## 4. Step 3：新建 VectorIndexService

新建 `learnhub-knowledge/src/main/java/com/github/comui520/learnhub/knowledge/service/VectorIndexService.java`：

```java
package com.github.comui520.learnhub.knowledge.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class VectorIndexService {

    private final VectorStore vectorStore;

    public VectorIndexService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /** 把切好的 chunks 向量化并写入向量库 */
    public void addChunks(List<Document> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        vectorStore.add(chunks);
        log.info("indexed {} chunks into vector store", chunks.size());
    }

    /** 删除文件时清理它的所有向量（chunk id 规则：fileId+序号 生成的稳定 UUID） */
    public void deleteByFileId(Long fileId, int chunkCount) {
        if (chunkCount <= 0) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < chunkCount; i++) {
            ids.add(UUID.nameUUIDFromBytes((fileId + "-" + i).getBytes()).toString());
        }
        vectorStore.delete(ids);
        log.info("deleted {} vectors for fileId={}", ids.size(), fileId);
    }
}
```

逐段理解：

- `VectorStore` 是接口，Qdrant starter 自动提供了实现，构造器注入即可（又是 IOC 的老套路）。
- `vectorStore.add(chunks)`：核心一行。内部做“embedding + 写入”。
- `vectorStore.delete(ids)`：按**显式 id** 删向量。所以 Session A 里必须给每个 chunk 设稳定 id，不然这里没法精准删。

## 5. Step 4：给 chunk 设稳定 id（改 Session A 的 parseAndChunk）

打开 `DocumentParseService`，在“⑤ 给每块加序号”那一步，顺手把 id 也设了：

```java
for (int i = 0; i < chunks.size(); i++) {
    Document chunk = chunks.get(i);
    chunks.set(i, chunk.mutate()            // Document 是不可变类，没有 setId，用 mutate() 重建
            .id(UUID.nameUUIDFromBytes((fileId + "-" + i).getBytes()).toString())  // 稳定 UUID
            .metadata("chunkIndex", i)      // 顺手把序号也放进 metadata
            .build());
}
```

> ⚠️ 注意：Spring AI 的 `Document` 是不可变类（`id`/`text`/`metadata` 都是 final，**没有 `setId`**）。改 id 必须用 `chunk.mutate().id(...).build()` 重建，或直接构造 `new Document(id, text, metadata)`。但 `getMetadata()` 返回的 **Map 本身是可变的**，所以 `doc.getMetadata().put("fileName", ...)`（Session A 里的写法）是合法的。
>
> ⚠️ **Qdrant 的 point id 只接受整数或 UUID**，直接写 `fileId-序号` 会报 `Invalid UUID string`。用 `UUID.nameUUIDFromBytes(...)` 生成**稳定 UUID**（同一输入永远得到同一 UUID），写入和删除用同一规则，就能按文件精准清理。

这样每个向量的 id 是“fileId+序号”生成的稳定 UUID，删除时按同一规则重建 UUID 列表即可。**id 的生成规则就是你和 VectorIndexService 之间的约定**，两处都用 `UUID.nameUUIDFromBytes` 保持一致。

## 6. Step 5：消费者接上向量化

`DocumentParseConsumer` 注入 `VectorIndexService`，在解析之后、标 COMPLETED 之前调用：

```java
List<Document> chunks = documentParseService.parseAndChunk(
        file.getId(),
        file.getUserId(),
        file.getFileName(),
        inputStream
);
vectorIndexService.addChunks(chunks);
```

如果向量化失败（比如 Key 过期、网络问题），异常会抛给消费者 → 走 Part 4 的重试 → 死信。这是**故意**的：解析一半的脏数据不能标 COMPLETED。

## 7. Step 6：验证（Qdrant 里真的有点）

1. 重启应用，确认启动日志没有 Qdrant / OpenAI 报错。
2. 上传一个 Markdown 绑定，等解析完成。
3. 打开 `http://localhost:6333/dashboard` → Collections → `learnhub_docs` → 看点数（应为 chunk 数）。
4. 或者用 REST 查：

```powershell
curl.exe http://localhost:6333/collections/learnhub_docs/points/count -X POST -H "Content-Type: application/json" -d "{\"exact\":true}"
```

返回 `{"result":{"count":N}}`。

5. 点进一条 point，看 payload 里有没有 `fileId`、`userId`、`fileName`、`chunkIndex`——**Session C 的过滤和溯源全靠它**。

## 8. 🏃 你来做：删除文件时清理向量（一致性练习）

现在有个一致性缺口：`DocumentService.deleteById` 删了数据库和 MinIO，但 Qdrant 里的向量没人管，成了孤儿。修法：

1. 给 `document_file` 加一列 `chunk_count`（V9 迁移，`ALTER TABLE ... ADD COLUMN chunk_count INT NOT NULL DEFAULT 0`），解析成功后在 `parseAndChunk` 里 `file.setChunkCount(chunks.size())` 更新回去。
2. `DocumentService.deleteById` 里，删 MinIO 之前调 `vectorIndexService.deleteByFileId(fileId, chunkCount)`。
3. 验证：上传 → 删除 → 去 Qdrant 看点数归零。

提示：`DocumentFile` 实体加字段、`DocumentFileMapper` 不用改（BaseMapper 的 updateById 自动带上非空字段）。

> 面试点：数据库、MinIO、向量库三处一致性，删除顺序怎么排？答案和 Part 3 一样——先删业务数据，外部存储尽力而为 + 日志补偿。向量删失败不该让删除接口 500。

## 9. 复盘题

1. `vectorStore.add()` 内部做了什么？（提示：embedding + 写入，两步）
2. 为什么 chunk 要设稳定 id？删除向量靠什么约定？
3. metadata 里为什么没有 knowledgeBaseId？（Session A 答过，再答一遍）
4. 向量化失败时消费者抛异常的意义是什么？
5. 一个文件删了，Qdrant 里还留着向量，会有什么后果？（提示：检索会搜到已删内容）

完成后进入 [Session C](session-c-rag-chat-and-sse.md)：检索 + Prompt + SSE 流式问答。
