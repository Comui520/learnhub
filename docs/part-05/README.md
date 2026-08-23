# Part 5 课程：RAG 与流式问答

> 前置要求：
> - Part 4 完成（上传 → 绑定 → RabbitMQ 解析链路都通，`mvn compile` 全绿）。
> - Docker Compose 里 Qdrant 正常运行（`docker compose ps` 看到 learnhub-qdrant-1）。
> - **准备好一个大模型 API Key**（OpenAI 或任何 OpenAI 兼容服务），这是本 Part 唯一的“外部花钱”依赖。
>
> 本 Part 是**第一次接触 AI 技术栈**，请务必先读 [前置教学：RAG 与 Spring AI 从零认识](primer-rag-and-spring-ai.md)，再开始 Session A。

## 0. 本 Part 到底在做什么（一句话）

把 Part 4 的“假解析”（sleep 1 秒）换成真本事：**读文件 → 切分 → 向量化 → 存进 Qdrant**，然后给用户一个聊天接口：提问 → 从你的资料里检索相关片段 → 组装 Prompt → 大模型**流式**回答，并告诉用户“答案来自哪个文件的哪一段”。

这就是一个完整的 **RAG（检索增强生成）** 闭环，也是你简历上“AI 知识库”的核心卖点。

## 1. 本 Part 新增的技术（第一次见面，先认识）

| 技术 | 是干嘛的 | 依赖怎么写 |
|---|---|---|
| Spring AI | 统一封装“大模型、Embedding、向量库”的抽象层 | `spring-ai-starter-model-openai`（learnhub-ai 已预置） |
| OpenAI（或兼容）API | 提供聊天模型（生成回答）和 Embedding 模型（算向量） | 同上 |
| Qdrant | 向量数据库：存“文本向量 + 元数据”，按相似度检索 | `spring-ai-starter-vector-store-qdrant`（infrastructure 里已注释，解开即可） |
| SSE | 服务端事件流，让回答像打字机一样一个字一个字蹦出来 | `spring-boot-starter-webflux`（learnhub-ai 加） |
| Tika / PDF / Markdown Reader | 把 PDF、Word、Markdown 读成纯文本 | knowledge 模块已预置三个 reader 依赖 |
| TokenTextSplitter | 把长文档切成小块（chunk） | Spring AI 核心自带，不用加依赖 |

**依赖放哪**：

- 模型客户端（OpenAI）、向量库客户端（Qdrant）属于“外部系统适配”→ 放 `learnhub-infrastructure`。
- 真实解析、切分、向量写入 → 放 `learnhub-knowledge`（Part 4 的消费者在这里，顺势接上）。
- RAG 问答（检索 + 聊天 + SSE）→ 放 `learnhub-ai`（这个模块终于用上了；它依赖 knowledge，单向，不循环）。

## 2. 学习路径

| 文档 | 主题 | 档位 |
|---|---|---|
| [primer](primer-rag-and-spring-ai.md) | RAG 概念 + Embedding/向量库 + Spring AI 对应关系（零基础） | 🧑‍🏫 阅读 |
| [primer：响应式与检索](primer-reactive-streaming-and-search.md) | SSE / Flux / WebFlux / SearchRequest 三件套（做 Session C 前必读） | 🧑‍🏫 阅读 |
| [Session A](session-a-document-parsing-and-chunking.md) | 真实解析 PDF/Word/Markdown + 切分 | 🧑‍🏫 我带 |
| [Session B](session-b-embedding-and-vector-store.md) | Embedding + 写入 Qdrant（向量入库） | 🧑‍🏫 概念 + 🤝 代码 |
| [Session C](session-c-rag-chat-and-sse.md) | 检索 + Prompt + SSE 流式问答 + 引用 | 🧑‍🏫 概念 + 🤝 代码 |

## 3. 固定约定

- **Qdrant 里的向量和 MySQL 的 `document_file` 一一对应**：每个 chunk 的 metadata 带 `fileId`、`knowledgeBaseId`、`userId`、`chunkIndex`。删除文件/解绑时，向量要跟着清理（Session B 讲，先做到删除文件清理）。
- **数据隔离在检索层做**：`SearchRequest` 用 `filterExpression` 按 `knowledgeBaseId` 过滤，问 A 库的问题绝不检索 B 库的片段。
- **引用溯源**：回答里要能指出“来自哪个文件”，所以检索片段必须带文件名/页码元数据。
- **答案只来自你的资料**：System Prompt 明确“没有依据就说不知道”，这是对抗幻觉的底线。

## 4. 需要你提前准备的

1. 一个 OpenAI 兼容的 API Key（OpenAI 官方，或阿里百炼、DeepSeek、Moonshot 等提供 OpenAI 兼容端点的服务都行）。
2. 决定用哪个服务后，把 base-url、model 名记下来。文档里的配置默认 OpenAI，但所有代码都对“OpenAI 兼容”服务通用。

> 没 Key 也能学：Session A（解析+切分）完全不需要 Key；Session B 起才需要 Embedding 模型。你可以先把 A 做完再申请。

## 5. 最终验收清单

- [ ] 上传 PDF / Word / Markdown 并绑定后，日志出现“解析成功，共 N 个 chunk”，`document_file.status = COMPLETED`。
- [ ] Qdrant 里能看到向量数据（collection 点数 = chunk 数）。
- [ ] 同一文件传两个知识库，两个知识库都能各自检索到自己的片段。
- [ ] 提问后回答**流式**输出（不是等半天一次性返回）。
- [ ] 问资料里有的内容 → 回答正确且带引用（文件名/片段）。
- [ ] 问资料里没有的内容 → 模型明确说“资料中没有相关信息”，而不是瞎编。
- [ ] 用户 B 不能检索/问答用户 A 的知识库。
- [ ] 删除文件后，Qdrant 里对应向量被清理。
- [ ] `mvn compile` 全绿。

## 6. 答辩题预告

1. 什么是 RAG？为什么不用“把文档直接喂给大模型”或“微调”？
2. Embedding 是什么？为什么语义相近的文本向量也相近？
3. 为什么要切分文档？chunk 大小怎么选？
4. 向量检索和关键词检索（比如 MySQL LIKE）有什么区别？
5. 怎么保证“只回答自己资料里的内容”？检索不到时怎么办？
6. SSE 和轮询、WebSocket 有什么区别？这里为什么选 SSE？
7. 用户提问时，怎么确保只检索他**自己知识库**的向量？

从 [前置教学](primer-rag-and-spring-ai.md) 开始。
