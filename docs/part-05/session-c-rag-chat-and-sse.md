# Session C：RAG 问答 + SSE 流式输出

> 目标：做一个真正的问答接口：用户提问 → 从 Qdrant 检索相关片段 → 组装 Prompt → 大模型**流式**回答，并带上“答案来自哪个文件的哪一块”。
> 档位：概念 🧑‍🏫 我带，代码 🤝（检索 + 流式我给全，引用事件和调参你补）。预计 4～5 小时。
>
> 前置：Session A、B 完成（资料已向量化进 Qdrant），API Key 可用。
>
> ⚠️ **如果你对 SSE / Flux / WebFlux / SearchRequest 一脸懵，先读 [前置教学：响应式流与向量检索](primer-reactive-streaming-and-search.md)**，这篇把它们从零讲透，再回来跟 Session C。

## 0. 本 Session 完成时的样子

```text
POST /api/v1/knowledge-bases/3/chat  {"question":"AOP 是什么？"}
  → 校验知识库归属（只能问自己的）
  → 查该库绑定的 fileId 列表
  → 问题向量化 → Qdrant 按 fileId 过滤检索 Top5
  → 组装 Prompt（系统提示 + 片段 + 问题）
  → 大模型流式返回
  → 前端看到：先一个"引用"事件，然后文字一个字一个字出来
```

## 1. 概念速览（primer 的落地）

- **`SearchRequest`**：检索的“请求对象”。三要素：`query`（问题文本，内部自动向量化）、`topK`（返回几条）、`filterExpression`（过滤条件，这里按 fileId 列表过滤）。
- **`FilterExpressionBuilder`**：构造过滤条件的 DSL，`eq` / `in` / `and` 等。
- **`ChatClient`**：Spring AI 的聊天客户端。`prompt().system(...).user(...).stream().content()` 返回 `Flux<String>`——**流式**，模型吐一个 token 推一个。
- **`Flux`**（Project Reactor）：响应式流。`Flux.concat(事件A, 内容流)` 可以把“先发引用、再发内容”拼成一条流。
- **SSE 事件**：`ServerSentEvent<String>`，每个事件带 `event` 名和 `data`。前端按事件名区分“引用”和“内容”。

## 2. Step 1：给 learnhub-ai 加 webflux 依赖

`learnhub-ai/pom.xml` 加：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
```

为什么：`Flux<String>` 和 SSE 需要 WebFlux 在 classpath。**Spring MVC 和 WebFlux 可以共存**：MVC 管普通接口，WebFlux 负责处理流式返回值。

确认 `learnhub-ai` 依赖了 `learnhub-knowledge`（已经有了），这样能拿到 `KnowledgeBaseService`、实体、Qdrant 的 `VectorStore`（经 infrastructure 传递）。

## 3. Step 2：KnowledgeBaseService 加一个“取库内 fileId 列表”的方法

检索要按“这个库绑定了哪些文件”过滤。在 `KnowledgeBaseService` 加：

```java
/** 取知识库绑定的所有 fileId（已校验归属），供向量检索过滤用 */
public List<Long> listBoundFileIds(Long userId, Long knowledgeBaseId) {
    findOwned(userId, knowledgeBaseId);
    return knowledgeBaseDocumentMapper.selectList(
                    new LambdaQueryWrapper<KnowledgeBaseDocument>()
                            .eq(KnowledgeBaseDocument::getKnowledgeBaseId, knowledgeBaseId)
            ).stream()
            .map(KnowledgeBaseDocument::getFileId)
            .toList();
}
```

先 `findOwned` 校验归属（别人的库直接 404），再查关联表拿 fileId 列表。

## 4. Step 3：DTO

`learnhub-ai` 新建 `dto/ChatRequest.java`：

```java
package com.github.comui520.learnhub.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @NotBlank
        @Size(max = 500)
        String question
) {
}
```

## 5. Step 4：RagChatService（核心）

新建 `learnhub-ai/src/main/java/com/github/comui520/learnhub/ai/service/RagChatService.java`：

```java
package com.github.comui520.learnhub.ai.service;

import com.github.comui520.learnhub.knowledge.service.KnowledgeBaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

@Slf4j
@Service
public class RagChatService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final KnowledgeBaseService knowledgeBaseService;

    public RagChatService(
            ChatClient.Builder chatClientBuilder,
            VectorStore vectorStore,
            KnowledgeBaseService knowledgeBaseService
    ) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    /** 检索 + 组装 + 流式回答，SSE 事件流：先 references，再逐段 content */
    public Flux<ServerSentEvent<String>> streamChat(Long userId, Long knowledgeBaseId, String question) {
        // ① 取该库绑定的文件 id（内部已校验归属，别人的库直接 404）
        List<Long> fileIds = knowledgeBaseService.listBoundFileIds(userId, knowledgeBaseId);

        String[] fileIdStrings = fileIds.stream().map(String::valueOf).toArray(String[]::new);

        // ② 空库：没有可检索的资料，不调模型，直接返回空引用事件
        if (fileIds.isEmpty()) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("references")
                    .data("[]")
                    .build());
        }

        // ③ 检索：按 fileId 过滤，取最相似的 5 块
        // ⚠️ Qdrant payload 里 fileId 是字符串（"10"），必须传 String[]；
        //    直接传 List 会命中可变参数重载，把整个 List 当成一个元素，Qdrant 报 IN 类型错误。
        Filter.Expression filter = new FilterExpressionBuilder()
                .in("fileId", fileIdStrings)
                .build();
        SearchRequest searchRequest = SearchRequest.builder()
                .query(question)
                .topK(5)
                .filterExpression(filter)
                .build();
        List<Document> hits = vectorStore.similaritySearch(searchRequest);

        log.info("chat: kbId={}, userId={}, hits={}", knowledgeBaseId, userId, hits.size());

        // ④ 没有检索到任何资料：不调模型，直接告诉用户
        if (hits.isEmpty()) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("references")
                    .data("[]")
                    .build());
        }

        // ⑤ 组装 Prompt
        StringBuilder fragments = new StringBuilder();
        for (Document hit : hits) {
            String fileName = (String) hit.getMetadata().get("fileName");
            Long chunkIndex = (Long) hit.getMetadata().get("chunkIndex");
            fragments.append("【来源：")
                    .append(fileName)
                    .append(" 第")
                    .append(chunkIndex)
                    .append("块】\n")
                    .append(hit.getText())
                    .append("\n\n");
        }

        String system = "你是一个严谨的知识库助手。只根据用户提供的资料片段回答；"
                + "如果资料中没有相关信息，直接回答“资料中没有相关信息”，不要编造。";
        String userPrompt = fragments + "问题：" + question;

        // ⑥ 引用事件（先发）
        String referencesJson = buildReferencesJson(hits);
        Flux<ServerSentEvent<String>> referencesEvent = Flux.just(
                ServerSentEvent.<String>builder()
                        .event("references")
                        .data(referencesJson)
                        .build()
        );

        // ⑦ 内容流（逐 token 发）
        Flux<ServerSentEvent<String>> contentStream = chatClient
                .prompt()
                .system(system)
                .user(userPrompt)
                .stream()
                .content()
                .map(token -> ServerSentEvent.<String>builder()
                        .event("content")
                        .data(token)
                        .build());

        return Flux.concat(referencesEvent, contentStream);
    }

    private String buildReferencesJson(List<Document> hits) {
        // 把 fileName + chunkIndex 拼成 JSON 数组字符串，前端用来展示引用
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < hits.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            String fileName = String.valueOf(hits.get(i).getMetadata().get("fileName"));
            Long chunkIndex = (Long) hits.get(i).getMetadata().get("chunkIndex");
            sb.append("{\"fileName\":\"")
                    .append(fileName)
                    .append("\",\"chunkIndex\":")
                    .append(chunkIndex)
                    .append("}");
        }
        return sb.append("]").toString();
    }
}
```

逐段理解（这是 Part 5 的压轴，逐行看）：

- `ChatClient.Builder` 是 starter 自动配置的，`chatClientBuilder.build()` 得到一个可以聊天的客户端。
- **① fileIds**：先拿“这个库绑了哪些文件”。归属校验藏在 `listBoundFileIds` 里——用户 B 问 A 的库，这里直接 404。
- **② 空库**：先把 fileId 转成 `String[]`（Qdrant payload 里是字符串），再判空——没有绑定任何文件就不该检索（Qdrant 的 IN 也不能为空列表），直接返回空引用事件结束。
- **③ 检索**：
  - `new FilterExpressionBuilder().in("fileId", fileIdStrings).build()`：过滤条件 = “fileId 在这批文件里”。这就是 Session A 说不存 knowledgeBaseId 的原因——多对多场景下，按“库绑定的文件集合”过滤最准确。（⚠️ 两个坑：payload 里 fileId 是字符串 `"10"`，必须传 `String[]`；直接传 List 会命中可变参数重载把整个 List 当成一个元素。详见 primer 4.5）
  - `SearchRequest.builder().query(question).topK(5)`：问题会被自动向量化，搜最像的 5 块。`topK` 可以调（越大上下文越全越贵）。
  - `vectorStore.similaritySearch(searchRequest)`：返回 `List<Document>`，每个 Document 带 `getText()` 和 metadata。
- **④ 空结果**：检索不到就不该调模型（省 token、防幻觉）。发一个空引用事件，前端提示“资料中没有相关内容”。
- **⑤ Prompt**：把命中片段拼成“【来源：xxx 第 n 块】+ 正文”，再加上 system 提示。注意 `chunkIndex` 从 Qdrant 读回是 `Long`，写入端（`DocumentParseService`）写 `(long) i`、读取端强转 `Long`，别用 `Integer`（否则 ClassCastException，见 primer 4.6）。system 那句“没有就说没有，不要编造”是**对抗幻觉的关键**。
- **⑥ 引用事件**：先把来源发出去（事件名 `references`），前端可以立刻显示“回答参考了 3 个片段”。
- **⑦ 内容流**：`chatClient.prompt().system(...).user(...).stream().content()` 返回 `Flux<String>`，模型生成一个 token 推一个；`Flux.concat(referencesEvent, contentStream)` 保证“先引用、后内容”。
- `buildReferencesJson`：手工拼 JSON（字段少，够用；嫌丑可以用 Jackson 写一个引用 record，属于你自己的优化）。

> 面试点：为什么检索为空就不调模型？——省成本 + 不编造。这是 RAG 应用的常识级设计。

## 6. Step 5：ChatController

新建 `learnhub-ai/src/main/java/com/github/comui520/learnhub/ai/controller/ChatController.java`：

```java
package com.github.comui520.learnhub.ai.controller;

import com.github.comui520.learnhub.ai.dto.ChatRequest;
import com.github.comui520.learnhub.ai.service.RagChatService;
import com.github.comui520.learnhub.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@Tag(name = "Chat", description = "知识库问答")
@RestController
@RequestMapping("/api/v1/knowledge-bases")
@SecurityRequirement(name = "bearerAuth")
public class ChatController {

    private final RagChatService ragChatService;
    private final CurrentUser currentUser;

    public ChatController(RagChatService ragChatService, CurrentUser currentUser) {
        this.ragChatService = ragChatService;
        this.currentUser = currentUser;
    }

    @PostMapping(value = "/{id}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "知识库问答", description = "基于知识库内容流式回答")
    public Flux<ServerSentEvent<String>> chat(
            @PathVariable Long id,
            @Valid @RequestBody ChatRequest request
    ) {
        return ragChatService.streamChat(currentUser.currentUserId(), id, request.question());
    }
}
```

要点：

- `produces = TEXT_EVENT_STREAM_VALUE`：告诉浏览器/客户端这是 SSE 流。
- 返回 `Flux<ServerSentEvent<String>>`，每个事件有 `event`（references/content）和 `data`。
- `@Valid`：问题不能为空、不能超长。

## 7. Step 6：验证

### 7.1 正常问答（流式）

```powershell
curl.exe -N -X POST "http://localhost:8080/api/v1/knowledge-bases/3/chat" `
  -H "Content-Type: application/json" `
  -H "Authorization: Bearer <token>" `
  -d "{\"question\":\"AOP 是什么？\"}"
```

预期输出（顺序）：

```text
event: references
data: [{"fileName":"java笔记.md","chunkIndex":2},...]

event: content
data: AOP 是指...

event: content
data: 面向切面编程...
```

内容是一个字一个字蹦出来的（`curl -N` 不缓冲）。

### 7.2 资料外的问题（防幻觉）

问“今天北京天气怎么样？”：应该返回 `event: references` + `data: []`，**不会**调用模型瞎编。

### 7.3 越权验证

用户 B 的 token 访问用户 A 的知识库 id：404。

### 7.4 检查 Qdrant 过滤生效

看日志 `hits=N`。如果问 A 库的问题命中了 B 库的片段，说明过滤条件没生效——重点检查 `filterExpression` 和 metadata 里的字段名是否一致（`fileId` 大小写、类型）。

### 7.5 业务错误与 SSE 的 Accept

Chat 在真正开始输出模型内容前，会先完成知识库归属、限流和额度检查。此时还没有开始写 SSE，因此错误应保留正确的 HTTP 状态码。

| Accept | 状态 | 响应 |
|---|---:|---|
| application/json 或 */* | 402/429/404 | 普通 ApiResponse JSON |
| text/event-stream | 402/429/404 | event:error，data 是 ApiResponse JSON |

模型流已经开始后，HTTP 状态码不能再修改；此时只能继续发送 SSE error 事件。这是 HTTP 流式响应生命周期的限制。

PowerShell 7 建议使用 curl.exe -N 验收 SSE。Swagger UI 可查看定义，但不适合展示持续输出，看到 Undocumented / response status is 200 不等于接口失败。

## 8. 🏃 你来做：相似度阈值

`SearchRequest` 支持 `.similarityThreshold(0.5)`：低于阈值的片段不算命中。加一个合适的阈值，再验证“资料里完全没有的内容”会命中 0 条而不是硬凑。

> 思考：阈值设太高会漏答案，太低会进垃圾片段。这个值就是 RAG 调参的日常。

## 9. 复盘题

1. 检索的过滤条件为什么用 `fileId in (...)` 而不是 metadata 里的 knowledgeBaseId？
2. 检索为空时为什么不调模型？
3. SSE 的 `event` 字段在前后端是怎么配合的？
4. 为什么用 SSE 而不是轮询或 WebSocket？
5. `topK`、`similarityThreshold` 调大调小分别有什么影响？
6. 回答内容怎么保证“只来自资料”？（提示：System Prompt + 空结果拦截）

## 10. Part 5 收尾

- [ ] 更新 `LEARNHUB_PLAN.md`：勾选 Part 5，追加决策记录（chunk 大小、embedding 模型、检索过滤方案、SSE 选型）。
- [ ] 能回答 README 里的 7 道答辩题。
- [ ] `mvn compile` 全绿。

完成后进入 **Part 6：Redis、额度与订单并发**——给你的 AI 问答加“次数限制”和“扣额度”，顺便把 Redis 用起来。
