# Day 4：Spring MVC、参数校验与全局异常处理

## 今天的结果

完成一个临时示例接口，并让正常请求、参数错误和业务错误都得到稳定且正确的 HTTP 响应。

预计用时：4～5 小时。

## 一、原理课

### 1. 一次 MVC 请求经过什么

简化流程：

```text
HTTP 请求
 -> DispatcherServlet
 -> HandlerMapping 找到 Controller 方法
 -> 参数解析与 JSON 反序列化
 -> Validation
 -> Controller / Service
 -> 返回值序列化
 -> HTTP 响应
```

异常处理器也是 MVC 边界的一部分：它把 Java 异常转换成 HTTP 状态码和响应体。

### 2. DTO 为什么不直接用 Map

请求 DTO 提供：

- 编译期类型检查。
- 明确字段和接口契约。
- Validation 注解承载位置。
- OpenAPI 自动生成信息。
- 重构能力。

`Map<String, Object>` 会把很多错误推迟到运行时。

### 3. Validation 只处理输入合法性

示例：

- `name` 为空：参数校验错误。
- `name` 超过 50 个字符：参数校验错误。
- `name` 格式合法，但业务不允许使用：业务异常。

不要把数据库查询、权限判断等业务规则写进简单 Validation 注解。

### 4. Controller 的职责

Controller 应负责 HTTP 边界：接收参数、触发校验、调用应用服务、返回响应。业务判断放在 Service，不在 Controller 中堆积 `if/else`。

## 二、接口契约

实现临时接口：

```http
POST /api/v1/demo/greetings
Content-Type: application/json

{
  "name": "LearnHub"
}
```

成功响应示意：

```json
{
  "code": "COMMON_0000",
  "message": "success",
  "data": {
    "greeting": "Hello, LearnHub!"
  },
  "timestamp": "2026-07-20T12:00:00Z"
}
```

约定一个仅用于练习的业务规则，例如名字等于 `forbidden` 时抛出业务异常。这个示例模块会在真实功能出现后删除。

## 三、动手任务

### 任务 1：创建 DTO 与 Service

建议结构：

```text
learnhub-application/src/main/java/com/github/comui520/learnhub
├── demo
│   ├── DemoController.java
│   ├── DemoGreetingService.java
│   ├── DemoErrorCode.java
│   └── dto
│       ├── GreetingRequest.java
│       └── GreetingResponse.java
└── web/advice
    └── GlobalExceptionHandler.java
```

`GreetingRequest.name` 至少使用：

- `@NotBlank`
- `@Size(max = 50)`

注意 Spring Boot 3 使用 `jakarta.validation.*`，不要使用旧教程中的 `javax.validation.*`。

### 任务 2：创建 Controller

要求：

- 使用 `@RestController`。
- 使用 `@RequestMapping("/api/v1/demo")`。
- POST 方法使用 `@Valid @RequestBody`。
- 调用 Service。
- 返回 `ApiResponse<GreetingResponse>`。
- Controller 中不写 `try/catch (Exception)`。

### 任务 3：实现业务错误

`DemoErrorCode` 实现 Day 3 的 `ErrorCode` 接口。Service 遇到练习规则时抛出 `BusinessException`。

这验证了一个重要设计：`learnhub-common` 不需要提前知道所有业务模块的错误码。

### 任务 4：实现全局异常处理器

至少处理：

1. `BusinessException`：转换为合适的 4xx 状态和业务错误码。
2. `MethodArgumentNotValidException`：返回 HTTP 400 和字段错误。
3. `HttpMessageNotReadableException`：处理 JSON 缺失、格式错误等情况。
4. 最终兜底 `Exception`：返回 HTTP 500 和通用消息。

兜底处理器要求：

- 服务端日志记录完整异常。
- 客户端不返回堆栈、SQL、绝对路径等内部信息。
- 不把所有异常都谎报成参数错误。

参数错误可以返回简化后的字段列表，例如：

```json
{
  "field": "name",
  "message": "must not be blank"
}
```

### 任务 5：手工验证

成功：

```powershell
curl.exe -i -X POST http://localhost:8080/api/v1/demo/greetings -H "Content-Type: application/json" -d '{"name":"LearnHub"}'
```

参数错误：

```powershell
curl.exe -i -X POST http://localhost:8080/api/v1/demo/greetings -H "Content-Type: application/json" -d '{"name":""}'
```

业务错误：

```powershell
curl.exe -i -X POST http://localhost:8080/api/v1/demo/greetings -H "Content-Type: application/json" -d '{"name":"forbidden"}'
```

错误 JSON：

```powershell
curl.exe -i -X POST http://localhost:8080/api/v1/demo/greetings -H "Content-Type: application/json" -d '{bad json}'
```

PowerShell 的引号如果导致请求体异常，可把 JSON 保存到临时文件，再使用 `--data-binary @文件名`。

## 四、观察重点

对每个请求记录：

| 场景 | HTTP 状态 | 业务 code | 服务端是否记录堆栈 |
|---|---:|---|---|
| 成功 | 200 | 成功码 | 否 |
| 参数错误 | 400 | 参数错误码 | 通常否 |
| 业务拒绝 | 约定的 4xx | 业务错误码 | 视级别而定 |
| 未知异常 | 500 | 通用系统错误 | 是 |

## 五、常见错误

### 加了校验注解但没有生效

检查 Controller 参数前是否有 `@Valid`，并确认类路径中存在 validation starter。

### 异常处理器没有被扫描

确认它位于 `com.github.comui520.learnhub` 的子包，并使用 `@RestControllerAdvice`。

### 空字符串没有被 `@NotNull` 拦截

`@NotNull` 只检查 null；字符串非空白通常使用 `@NotBlank`。

### 所有异常都返回 500

检查更具体的异常处理方法是否存在，以及异常是否在 Service 中被错误地包装成了普通 `RuntimeException`。

## 六、验收

- [ ] 正常请求返回 HTTP 200 和统一响应。
- [ ] 空 name 返回 HTTP 400。
- [ ] 超长 name 返回 HTTP 400。
- [ ] 练习业务规则返回约定的业务错误。
- [ ] 错误 JSON 返回 HTTP 400。
- [ ] 未知异常不向客户端泄露堆栈。
- [ ] Controller 没有通用异常捕获代码。

## 七、口头复盘题

1. `@RequestBody`、`@Valid` 分别负责什么？
2. 为什么 Controller 不应该捕获所有异常？
3. 参数校验错误和业务错误有什么区别？
4. `@ControllerAdvice` 如何被 Spring 发现？
5. 为什么 HTTP 状态码不能全部返回 200？

## 八、今日提交建议

```text
feat: add validated demo endpoint and global error handling
```

完成后，把 Controller、Service、异常处理器和四次 curl 结果发给老师审查。

