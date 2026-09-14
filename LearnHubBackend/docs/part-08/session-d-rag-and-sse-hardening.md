# Session D：RAG 检索与 SSE 流的健壮性

## 1. 这一节优化什么

Part 5 已经实现了 RAG 和 SSE，但第一版常见的隐患有三个：

- Qdrant metadata 的数字类型不一定总是 Long；
- 手工拼接 JSON 很容易被文件名中的引号和反斜杠破坏；
- SSE 一旦开始输出，就不能再修改 HTTP 状态码。

这些问题不会每次都出现，所以要用边界思维检查，而不能只看一次成功响应。

## 2. metadata 的真实类型

JSON、Redis、Qdrant 和 Java 之间传递数据时，数字可能恢复成 Integer、Long、Double 或字符串。不要把 metadata 直接强转成 Long。

错误思路：

    Long index = (Long) metadata.get("chunkIndex");

如果 metadata 实际是 Integer，这段代码会抛 ClassCastException。

项目使用 Number 统一处理数字包装类型，再对字符串执行 Long.parseLong。并且同时兼容历史字段名 chunkIndex 和 chunk_index。

## 3. 为什么不用手工拼 JSON

文件名可能包含引号、反斜杠、换行或 Unicode。手工拼接会让响应变成非法 JSON。

项目现在把引用先转成一个小 record，再交给 Jackson 序列化。Jackson 会正确处理特殊字符，也让 DTO 结构更容易演进。

## 4. SSE 的两个阶段

### 4.1 流开始前

此时可以返回正常的 HTTP 错误状态：

- 没有 Token：401；
- 没有知识库权限：404；
- 额度不足：402；
- 触发限流：429。

如果 Accept 是 application/json，返回普通 ApiResponse；如果 Accept 包含 text/event-stream，返回对应 HTTP 状态并发送 event:error。

### 4.2 流已经开始

references 或 content 已经写入响应后，HTTP 200 不能再变成 500。此时只能发送 event:error，前端收到后停止拼接内容。

## 5. 正确的验收工具

Swagger UI 适合查看 OpenAPI 定义，不适合展示 Flux 持续输出。PowerShell 7 建议使用 curl.exe -N，并使用 127.0.0.1 访问本机服务。

验收时检查：

1. 是否先收到 references；
2. 是否收到 content；
3. 错误是否有正确 HTTP 状态码；
4. SSE 错误是否使用 error 事件；
5. 服务端日志是否记录模型异常。

## 6. 看到 curl 的 transfer closed 怎么办

如果已经看到完整的 references 和 content，最后出现 transfer closed 的提示，不要立即判断模型失败。先检查 HTTP 状态、事件内容和服务端日志。它可能只是客户端对 chunked 流结束方式的提示。

如果没有任何事件就断开，才需要重点检查模型 provider、网络、超时和服务端异常。

## 7. RAG 检索边界

检索前必须按知识库绑定的 fileId 过滤，不能只依赖用户输入的知识库 ID。检索为空时不调用模型，以节省额度并避免脱离资料编造。

similarityThreshold 和 topK 是需要用样本和日志调节的参数，不能在没有数据时声称某个值最优。

## 8. 优化后的链路

    归属校验
      -> 限流和额度检查
      -> Qdrant 过滤检索
      -> 安全组装引用
      -> references 事件
      -> content 事件流
      -> 流中异常转 error 事件

它仍然是简单的 MVC 加 Reactor 组合，没有把整个系统改成 WebFlux 微服务。
