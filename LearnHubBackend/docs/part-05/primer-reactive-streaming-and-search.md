# 前置教学：响应式流（Flux / WebFlux / SSE）与向量检索

> 阅读对象：做 Session C 时突然看不懂的你。
> 这篇把 Session C 里“忽然出现”的三样东西讲透：
> 1. **SSE** 是什么；
> 2. **Flux / WebFlux** 是什么、怎么用（只学够用的那点，不学全套响应式编程）；
> 3. **SearchRequest / FilterExpressionBuilder** 三件套是干嘛的。
>
> 建议：先读过 [RAG primer](primer-rag-and-spring-ai.md)（理解 RAG 流程），再读这篇（理解代码长什么样），Session C 就顺了。

## 1. 从“普通接口”到“流式接口”

### 1.1 你熟悉的普通接口

```java
@GetMapping("/hello")
public String hello() {
    return "hi";
}
```

方法返回一个 String，Spring 等它算完，**一次性**写进响应体。浏览器收到的是完整的一坨。

类比：快递柜里放好包裹，你一次性取走。

### 1.2 流式接口（SSE）解决的问题

大模型生成答案要 5~10 秒。如果像普通接口那样等 10 秒再一次性返回，用户盯着空白页干等，体验极差。

**SSE（Server-Sent Events，服务端发送事件）**：HTTP 连接保持打开，服务端**生成一点就推一点**。

类比：水龙头，拧开一直流，而不是先接满一桶再给你。用户 0.5 秒就看到第一个字，后面是打字机效果。

> 面试点：SSE 和 WebSocket 的区别——SSE 单向（服务端→客户端）、基于普通 HTTP、断线自动重连；WebSocket 双向。聊天问答只需要服务端推，所以用 SSE。

### 1.3 SSE 在网络层长什么样

普通响应：

```text
HTTP/1.1 200
Content-Type: application/json

{"answer":"AOP 是面向切面编程..."}
```

SSE 响应：

```text
HTTP/1.1 200
Content-Type: text/event-stream

event: references
data: [{"fileName":"笔记.md","chunkIndex":2}]

event: content
data: AOP 是

event: content
data: 面向切面编程...
```

规则就三条：

- `Content-Type` 是 `text/event-stream`；
- 每个事件 = `event: 事件名` + `data: 内容`，**事件之间空行**隔开；
- 前端按事件名分发：`references` 显示引用，`content` 一个字一个字拼答案。

用 `curl -N` 可以亲眼看到“一个字一个字蹦出来”（`-N` 表示不缓冲，来了就打印）。

## 2. Flux：异步的“流水线”

### 2.1 先用你会的类比

| 你熟悉的 | 对应的 Flux |
|---|---|
| `List<String>` | `Flux<String>` |
| 一次性返回全部元素 | 元素**一个接一个**到达 |
| `for` 循环挨个处理 | 订阅后，每来一个元素回调一次 |

Flux 是 Project Reactor 的核心类型。**把它想成“会随时间一个接一个产出元素的管道”**，就够了。不用管背压、调度器那些。

### 2.2 怎么造一个 Flux

```java
Flux.just("a", "b", "c");     // 立即产出 3 个元素
Flux.fromIterable(list);      // 从 List 变成流
```

### 2.3 关键：不订阅，不执行

```java
Flux<String> flux = Flux.just("a", "b");
// 到这里为止，什么都没有发生
```

要让管道里的元素真的“流”起来，必须有人**订阅**（subscribe）。在 Spring 里你不需要手动订阅——**Controller 方法返回 Flux，框架自动订阅，并逐个把元素写进响应**。

类比：水管接好了，但没人开水龙头，水不会流。返回 `Flux` 等于把水龙头交给框架，框架替你拧开。

### 2.4 本 Session 只用了三个操作

```java
map(...)    // 每个元素转换一下：Flux.just(1,2).map(n -> n*10) → 10, 20
concat(...) // 两条流首尾拼接：先发引用事件，再发内容流
Flux.just(x) // 造一个只含一个元素的流（发单个事件用）
```

Reactor 有几百个操作符，**只学这三个就够本项目用了**，用到别的再查，别一次学完。

## 3. WebFlux：让 Spring 认识“流”

Spring MVC 的 Controller 返回 String / 对象时，Spring 用“消息转换器”一次性写进响应——它**不认识 `Flux`**。

加了 `spring-boot-starter-webflux` 依赖后，Spring 多了一个能力：**方法返回 `Flux<ServerSentEvent<String>>` 时，自动订阅、把每个事件按 SSE 格式写进响应**。

所以 WebFlux 对你的意义就一句话：**一个让 Spring 能处理流的依赖**。你不需要学响应式编程全套，只需要：

```java
// 1. Controller 返回类型写成 Flux<ServerSentEvent<String>>
// 2. produces 声明 text/event-stream
@PostMapping(value = "/{id}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> chat(...) { ... }
```

在 Spring MVC + WebFlux 共存的 Spring Boot 应用里，普通接口照常写，只有流式接口用 Flux，互不干扰。

## 4. 检索三件套：SearchRequest / FilterExpressionBuilder / Filter.Expression

这三个是 Session C 里“忽然出现”的。其实它们就是给 `vectorStore.similaritySearch(...)` 准备参数的工具，**相当于 SQL 查询的各个部分**。

### 4.1 SearchRequest：检索的“查询对象”

```java
SearchRequest.builder()
        .query(question)          // 问题文本（内部自动向量化）
        .topK(5)                  // 返回最像的 5 条
        .filterExpression(filter) // 过滤条件（相当于 WHERE）
        .build();
```

类比成 SQL：

```sql
SELECT * FROM learnhub_docs
WHERE file_id IN (1,2,3)          -- filterExpression
ORDER BY 相似度 DESC              -- query 决定的
LIMIT 5;                          -- topK
```

### 4.2 FilterExpressionBuilder：写 WHERE 的语法糖

Qdrant 里过滤的是 payload（每个 chunk 的元数据）。`FilterExpressionBuilder` 把 Java 代码翻译成 Qdrant 能懂的过滤表达式：

```java
String[] fileIdStrings = fileIds.stream().map(String::valueOf).toArray(String[]::new);
FilterExpressionBuilder b = new FilterExpressionBuilder();
Filter.Expression filter = b.in("fileId", fileIdStrings).build();
// 相当于 SQL：WHERE file_id IN ('1','2','3')
```

常用的就两个：

```java
b.in("fileId", fileIdStrings)   // 字段值在字符串数组里（本项目用）
b.in("userId", "1")            // 等于（以后做别的过滤用）
```

`Filter.Expression` 就是构造器产出的“过滤表达式对象”，`SearchRequest` 要求这个类型，仅此而已。

> ⚠️ 为什么是字符串？Spring AI 写 Qdrant 时把 Long metadata 序列化成了字符串，payload 里是 `"fileId":"10"` 而不是数字，所以过滤也必须传字符串 `"10"`。

### 4.3 为什么用 fileId 过滤，而不是 knowledgeBaseId

Session A 讲过：一个文件可以绑定多个知识库，向量里**故意不存 kbId**。所以检索时：

1. 先从 MySQL 查出“这个库绑定了哪些文件”的 `fileId` 列表（`listBoundFileIds`）；
2. 把 fileId 列表转成 `String[]`，再用 `in("fileId", fileIdStrings)` 过滤。

这就是多对多模型下的数据隔离：问 A 库，只搜 A 库绑定的那些文件。

### 4.4 过滤不生效怎么排查

metadata 里的 key 必须和 FilterExpressionBuilder 里的字符串**完全一致**（大小写、类型）。比如 Session B 里存的是 `"fileId"`，过滤写 `"fileId"`，别写 `"file_id"`。排查方法：Qdrant 控制台点开一条 point 看 payload，对照着检查。

最常见的“过滤不生效”是**类型不匹配**：payload 里 `"fileId": "10"`（字符串），过滤器却传数字 `10`，一个都搜不到。所以检索前要 `map(String::valueOf)` 转成字符串数组。

### 4.5 最常见的坑：直接传 List 会报 “Unsupported value in IN value list”

`FilterExpressionBuilder` 有两个 `in` 重载：`in(String, List<Object>)` 和 `in(String, Object...)`。
直接写 `b.in("fileId", fileIds)` 时，因为 `List<Long>` 不能严格匹配 `List<Object>`，Java 会选中**可变参数版本**，
结果是“参数是一个元素，这个元素恰好是那个 List”——Qdrant 收到 `[[1,2,3]]`，第一个元素不是 String/Number，直接抛异常。

所以必须**把元素展开**再传：

```java
// ✅ 正确：展开成数组 + 转成字符串（Qdrant payload 里 fileId 是字符串）
String[] fileIds = fileIdList.stream().map(String::valueOf).toArray(String[]::new);
b.in("fileId", fileIds)

// ❌ 错误1：整个 List 被当成一个元素（重载陷阱）
b.in("fileId", fileIdList)

// ❌ 错误2：展开了但类型不匹配——payload 是 "10"，过滤器传 10，还是搜不到
b.in("fileId", fileIdList.toArray())
```

另外 Qdrant 要求 IN 列表**不能为空**（空列表同样报错）。查完绑定关系后先判空，空库直接返回空引用，不构造查询。

> 这个坑的排查信号：接口显示 401（SSE 接口的错误响应写不出去时会被安全链兜住），实际日志是
> `java.lang.RuntimeException: Unsupported value in IN value list. Only supports String or Number`。

### 4.6 读取 metadata 时注意反序列化类型

Qdrant 把数值 metadata 读回来时是 `Long`。比如 `hit.getMetadata().get("chunkIndex")` 的实际类型是 `Long`，强转 `Integer` 会抛 `ClassCastException`。写入和读取要统一：

- 写入（Session A 的 `DocumentParseService`）：`chunk.metadata("chunkIndex", (long) i)`
- 读取（Session C）：`Long chunkIndex = (Long) hit.getMetadata().get("chunkIndex")`

`fileName` 这类字符串 metadata 没有这个问题，直接 `(String)` 即可。

## 5. 把 Session C 的 streamChat 串一遍

现在回头读 `RagChatService.streamChat`，每一步对应这篇的概念：

```java
// ① 取该库绑定的 fileId（内部校验归属）—— WHERE 的数据来源
List<Long> fileIds = knowledgeBaseService.listBoundFileIds(userId, knowledgeBaseId);

// ② 构造“SELECT ... WHERE file_id IN ('1','2',...) LIMIT 5”
//    Qdrant payload 里 fileId 是字符串，所以先转成 String[]
String[] fileIdStrings = fileIds.stream().map(String::valueOf).toArray(String[]::new);
Filter.Expression filter = new FilterExpressionBuilder().in("fileId", fileIdStrings).build();
SearchRequest searchRequest = SearchRequest.builder()
        .query(question)
        .topK(5)
        .filterExpression(filter)
        .build();
List<Document> hits = vectorStore.similaritySearch(searchRequest);   // 执行检索

// ③ 没有命中 → 不调模型，发个空引用事件就结束（防幻觉 + 省钱）

// ④ 拼 Prompt：把命中片段按“【来源：xx 第 n 块】+ 正文”拼起来
//    注意：Qdrant 读回的 chunkIndex 是 Long，强转 Long，别用 Integer

// ⑤ 引用事件：Flux.just(一个事件) —— 先发
Flux<ServerSentEvent<String>> referencesEvent = Flux.just(...);

// ⑥ 内容流：chatClient...stream().content() 返回 Flux<String>，map 成 SSE 事件
Flux<ServerSentEvent<String>> contentStream = chatClient
        .prompt().system(system).user(userPrompt)
        .stream().content()
        .map(token -> ServerSentEvent.<String>builder()
                .event("content").data(token).build());

// ⑦ 首尾拼接：先引用，再内容，整条流交给框架订阅
return Flux.concat(referencesEvent, contentStream);
```

看到没有：**Flux 只在 ⑤⑥⑦ 出现**，前面全是普通 Java。响应式只出现在“要流式输出”的地方，其余代码照常写。

## 6. 学习路线建议

- 这篇的目标：能读懂 Session C 代码、能照抄改参数，**就够了**。
- 面试如果被问响应式：说清“Flux 是异步元素流，不订阅不执行，Spring 自动订阅；项目里只用于 SSE 流式输出”即可。
- 想深入（可选，不是本项目必需）：Reactor 的 `map/flatMap` 区别、背压、`onErrorResume` 错误处理、`SseEmitter`。

概念通了，回 [Session C](session-c-rag-chat-and-sse.md) 从头看，应该就顺了。
