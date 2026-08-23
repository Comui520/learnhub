# 前置教学：RAG 与 Spring AI 从零认识

> 阅读对象：完全没用过 AI 技术栈的你。
> 目标：学完这一篇，你能用自己的话解释 RAG 的完整流程，并且看得懂后面 Session 的每一行代码。
> 建议：这篇偏概念，读的时候不用记代码，重点是**把“检索增强生成”这条链路在脑子里跑一遍**。

## 1. 先回答一个灵魂问题：大模型为什么不知道你的资料

你上传的 PDF 是**私有资料**：大模型训练时根本没见过它。所以直接问大模型“我的 Java 笔记里怎么解释 AOP？”它只会：

1. 瞎编一个答案（幻觉，hallucination），或者
2. 回答一个泛泛的 AOP 概念，但不是你笔记里的说法。

要让模型“知道”你的资料，有两条路：

| 方案 | 做法 | 评价 |
|---|---|---|
| 微调（Fine-tuning） | 拿你的文档去继续训练模型 | 贵、慢、资料更新要重训；个人项目不值 |
| **RAG（检索增强生成）** | 提问时先把相关资料“搜出来”，拼进 Prompt，让模型基于资料回答 | 便宜、即时更新、能溯源 |

**本项目用 RAG**。面试被问“为什么不用微调”，就答这三条：成本、时效、可溯源。

## 2. RAG 全流程：一句话版

```text
建库（一次性）：
  文档 → 切分成小块(chunk) → 每块转成向量 → 存进向量库

问答（每次提问）：
  问题 → 转成向量 → 从向量库搜最相似的几块 → 把片段拼进 Prompt
       → 大模型基于片段生成答案 → 流式返回
```

“检索增强生成”这个名字拆开就是：**先检索，增强 Prompt，再生成**。

## 3. 逐个搞懂概念

### 3.1 Embedding（嵌入 / 向量化）

**Embedding 是把一段文字变成一个“数字列表”（向量）**。比如：

```text
"Java 的 GC 怎么调优"  →  [0.012, -0.034, 0.087, ...]  （几百到几千个数）
"Spring 事务怎么用"    →  [0.045, 0.021, -0.066, ...]
```

神奇的地方：**语义相近的文本，向量也相近**。“猫”和“喵”的向量距离很近；“猫”和“数据库”的向量距离很远。距离用**余弦相似度**衡量（0~1，越接近 1 越相似）。

实现 Embedding 的是一个专门的模型（比如 `text-embedding-3-small`），它和聊天的模型不是同一个。我们只管调它，不用懂内部。

### 3.2 为什么要切分（Chunking）

一个 100 页的 PDF 不可能整体塞进 Prompt（模型有上下文窗口限制，太长会截断、变贵、变慢）。所以先切成小块：

```text
原文：... [第1段] ... [第2段] ... [第3段] ...
切分后：chunk1 | chunk2 | chunk3 | ...
```

每块单独向量化、单独存。提问时只搜出**最相关的几块**，拼进 Prompt。这样：

- 成本可控（只把相关片段喂给模型）；
- 回答更准（片段聚焦，不是大海捞针）；
- 能溯源（每块知道来自哪个文件的哪一页）。

chunk 大小怎么选？**没有标准答案**，靠实验。一般 300~800 token，块与块之间留一点重叠（overlap），防止一句话被从中间切断。文档里给一个能用的默认值，你之后可以自己调。

### 3.3 向量数据库（Qdrant）

普通数据库按条件查（`WHERE title = 'x'`），**向量数据库按“语义相似度”查**：

```text
输入：问题向量
输出：库里和它最像的 N 个 chunk（带元数据：来自哪个文件、第几块）
```

Qdrant 就是干这个的。它存两样东西：

- **向量**（chunk 的数字表示）；
- **payload（元数据）**：`fileId`、`knowledgeBaseId`、`userId`、`chunkIndex`、`fileName`。

元数据是用来**过滤和溯源**的——比如只搜 `knowledgeBaseId = 3` 的向量，或者告诉用户答案来自 `xxx.pdf`。

### 3.4 检索（Similarity Search）

提问时：

1. 把问题向量化；
2. 在 Qdrant 里搜**最相似的前 K 个** chunk（TopK）；
3. 加上过滤条件（只搜这个知识库的）；
4. 可选相似度阈值（低于 0.5 的说明“不太相关”，可以当检索不到）。

### 3.5 Prompt 组装：让模型“只根据资料回答”

搜出来的片段不是直接丢给模型，而是组装成一个结构化 Prompt：

```text
System：你是一个知识库助手。只根据下面提供的资料回答；
        资料里没有的，明确说“资料中没有相关信息”，不要编造。

User：
【资料片段】
来源：Java笔记.pdf 第 3 页：
“AOP 是……”
来源：...

问题：请解释 AOP 和 IOC 的区别。
```

模型看到这个 Prompt，就只能“基于资料”回答了。**System Prompt 是防幻觉的第一道防线。**

### 3.6 引用溯源

因为每个 chunk 都带着 `fileName`/`chunkIndex`/页码，模型回答时可以顺便告诉用户“这个结论来自 xxx.pdf”。前端可以显示引用来源——这就是“可核对的 AI 回答”。

### 3.7 SSE：流式输出

普通接口：等模型把整段答案生成完，一次性返回（可能 10 秒）。

SSE（Server-Sent Events）：服务端**生成一个字就推一个字**，用户看到打字机效果，首字延迟不到 1 秒。聊天产品全是这么做的。

```text
浏览器 ←SSE← Spring Boot ←流← 大模型
```

SSE 和 WebSocket 的区别（面试高频）：SSE 是**单向**（服务端→客户端）、基于普通 HTTP、断线自动重连；WebSocket 是**双向**。聊天问答只需要服务端推，所以选 SSE。

## 4. Spring AI：Java 这边怎么对应

Spring AI 是 Spring 官方的大模型抽象层，类似“MyBatis 之于数据库”：你写代码不关心底层是 OpenAI 还是别的模型，只面向它的接口。

| RAG 概念 | Spring AI 里是谁 |
|---|---|
| 读 PDF/Word/Markdown | `TikaDocumentReader` / `PagePdfDocumentReader` / `MarkdownDocumentReader` → `List<Document>` |
| 切分 | `TokenTextSplitter`：`split(List<Document>)` |
| 向量化 | `EmbeddingModel`（starter 自动配置，注入即用） |
| 向量库 | `VectorStore` 接口（Qdrant 实现，starter 自动配置） |
| 相似检索 | `vectorStore.similaritySearch(SearchRequest)` |
| 聊天模型 | `ChatModel` / `ChatClient`（starter 自动配置） |
| 流式输出 | `chatClient.prompt(...).stream().content()` → `Flux<String>` |

`Document` 不是 Spring MVC 的 Document，是 **Spring AI 的文档对象**：`getText()` 拿正文，`getMetadata()` 拿元数据。

## 5. 依赖怎么配

### 5.1 模型（learnhub-ai 已预置）

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

自动配置出 `ChatModel`、`EmbeddingModel`，连接信息从 yml 读。

### 5.2 向量库（infrastructure 解开注释）

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-vector-store-qdrant</artifactId>
</dependency>
```

自动配置出 `VectorStore`，指向 Qdrant。

### 5.3 配置（application-dev.yml）

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

- `spring.ai.openai.*`：模型连接信息。`base-url` 改成你用的兼容服务地址即可（比如 DeepSeek、阿里百炼）。
- `spring.ai.vectorstore.qdrant.*`：Qdrant 连接（注意在 `ai` 下面）。**端口必须是 6334（gRPC），不是 6333（HTTP/dashboard）**——Spring AI 的 Qdrant 客户端走 gRPC，配 6333 会导致启动时 healthCheck 挂起。collection 是 Qdrant 里的“表”，启动时不存在会自动建。

> ⚠️ **base-url 别带 `/v1`**：Spring AI 的 OpenAI 路径自带 `/v1`（`/v1/chat/completions`、`/v1/embeddings`），所以 base-url 填**根地址**。官方 OpenAI 是 `https://api.openai.com`；硅基流动填 `https://api.siliconflow.cn`。写成 `https://api.siliconflow.cn/v1` 会拼出 `/v1/v1/embeddings` → 404。

## 6. 动手前最后确认

1. `docker compose up -d qdrant`，`http://localhost:6333/dashboard` 能打开。
2. 环境变量 `AI_API_KEY` 配好（不会配就参照 Part 2 的教训：系统环境变量最稳）。
3. 模型名确认：聊天模型（如 `gpt-4o-mini`）、Embedding 模型（如 `text-embedding-3-small`），用兼容服务的话改成它文档里的名字。

概念齐了，开始 [Session A](session-a-document-parsing-and-chunking.md)：把 Part 4 的假解析换成真的。
