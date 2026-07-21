# Day 4：完成第一条 API——HTTP、MVC、DTO、Validation 与全局异常

## 0. 今天到底要学会什么

前三天我们让应用启动，并准备了统一响应与错误模型。今天要把它们串成完整请求：客户端发送 JSON，Spring 把它变成 Java 对象，校验参数，调用 Service，再把结果变回 JSON。

完成后，你应该能够：

1. 理解 URL、HTTP 方法、状态码、Header 和 JSON Body。
2. 知道 Controller、DTO、Service 各自负责什么。
3. 理解 Spring MVC 的请求处理主流程。
4. 使用 `@RequestBody` 把 JSON 转成 Java record。
5. 使用 `@Valid`、`@NotBlank`、`@Size` 校验输入。
6. 区分参数错误、业务错误、系统错误。
7. 使用 `@RestControllerAdvice` 集中转换异常。
8. 使用 curl 亲手验证四条请求路径。

预计时间：6～7 小时。今天内容较多，可以拆成两次完成。

---

## 1. HTTP 是客户端与服务器之间的约定

### 1.1 URL

我们今天的地址：

```text
http://localhost:8080/api/v1/demo/greetings
```

拆开看：

- `http`：通信协议。
- `localhost`：服务器主机，表示当前电脑。
- `8080`：服务器监听端口。
- `/api/v1/demo/greetings`：资源路径。

`v1` 是 API 版本。以后产生不兼容改动，可以新增 v2，而不是悄悄破坏现有客户端。

### 1.2 HTTP 方法

今天使用 POST：

```http
POST /api/v1/demo/greetings
```

常见方法：

- GET：读取资源。
- POST：提交数据、创建资源或触发处理。
- PUT：整体更新。
- PATCH：部分更新。
- DELETE：删除。

问候接口主要用于练习请求 Body，所以选择 POST。

### 1.3 Header 与 Body

```http
Content-Type: application/json

{"name":"LearnHub"}
```

`Content-Type` 告诉服务器 Body 是 JSON。Spring 根据它选择 JSON 消息转换器。

### 1.4 状态码

本日使用：

- 200 OK：请求成功。
- 400 Bad Request：JSON 或参数格式有问题。
- 422 Unprocessable Entity：语法合法，但业务规则拒绝。
- 500 Internal Server Error：未预期系统错误。

HTTP 状态码负责协议层；响应 JSON 里的 `code` 负责更具体的应用错误。

---

## 2. Spring MVC 怎样处理请求

简化流程：

```text
curl / 浏览器
    ↓ HTTP
内嵌 Tomcat 接收连接
    ↓
DispatcherServlet（Spring MVC 总入口）
    ↓ 根据路径和方法查找
DemoController.greet(...)
    ↓ 调用
DemoGreetingService.greet(...)
    ↓ 返回 Java 对象
Jackson 把对象序列化为 JSON
    ↓
HTTP 响应
```

在进入 Controller 方法前，Spring 还会：

1. 读取 JSON Body。
2. 使用 Jackson 创建 `GreetingRequest`。
3. 因为参数有 `@Valid`，执行 Bean Validation。
4. 如果校验失败，直接抛异常，Controller 方法不会执行。

这解释了为什么参数错误可以统一处理，而不用每个 Controller 手写空值判断。

---

## 3. Controller、DTO、Service 如何分工

### 3.1 DTO

DTO 是 Data Transfer Object，即跨边界传输数据的对象。

今天有：

- `GreetingRequest`：描述客户端允许提交什么。
- `GreetingResponse`：描述服务器成功返回的 data 内容。

不要直接用 `Map<String, Object>`，因为 Map 没有明确字段类型、校验注解和重构保障。

### 3.2 Controller

Controller 负责 Web 边界：

- 把 URL 和 Java 方法对应起来。
- 接收已经反序列化的 DTO。
- 触发校验。
- 调用 Service。
- 包装响应。

Controller 不应该承载大量业务 `if/else`，也不应该捕获所有异常。

### 3.3 Service

Service 负责用例和业务处理。今天它：

- 规范化 name。
- 判断练习用业务规则。
- 生成问候结果。

真实项目中，注册、创建知识库、扣额度等流程都会在应用服务层组织。

### 3.4 Advice

`@RestControllerAdvice` 是所有 Controller 的统一异常边界。它把 Java 异常转换成 HTTP 状态和 `ApiResponse`。

如果没有它，每个 Controller 都要重复写 try/catch，而且不同开发者很容易返回不同格式。

---

## 4. 创建文件结构

在启动模块创建：

```text
learnhub-application/src/main/java/com/github/comui520/learnhub/
├── demo/
│   ├── DemoController.java
│   ├── DemoGreetingService.java
│   ├── DemoErrorCode.java
│   └── dto/
│       ├── GreetingRequest.java
│       └── GreetingResponse.java
└── web/advice/
    ├── FieldValidationError.java
    └── GlobalExceptionHandler.java
```

为什么临时 demo 放 application？它只用于验证 Web 骨架，第二周真实用户接口出现后会删除。不要把真实用户或知识库规则长期放启动模块。

---

## 5. 编写请求与响应 DTO

### 5.1 `GreetingRequest.java`

```java
package com.github.comui520.learnhub.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GreetingRequest(
        @NotBlank(message = "name 不能为空")
        @Size(max = 50, message = "name 最多 50 个字符")
        String name
) {
}
```

逐段解释：

- `jakarta.validation.*`：Spring Boot 3 使用 Jakarta 命名空间。旧教程中的 `javax.validation.*` 不适用于本项目。
- `@NotBlank`：拒绝 null、空字符串 `""` 和只有空白的字符串 `"   "`。
- `@Size(max = 50)`：限制字符串长度。
- `message`：校验失败时的默认说明。
- 注解写在 record 组件上，Spring Validation 可以读取。

三个相似注解：

| 注解 | null | 空字符串 | 只有空格 | 常见用途 |
|---|---|---|---|---|
| `@NotNull` | 拒绝 | 允许 | 允许 | 任何对象非 null |
| `@NotEmpty` | 拒绝 | 拒绝 | 允许 | 集合/字符串非空 |
| `@NotBlank` | 拒绝 | 拒绝 | 拒绝 | 用户输入文本 |

所以 name 使用 `@NotBlank`。

### 5.2 `GreetingResponse.java`

```java
package com.github.comui520.learnhub.demo.dto;

public record GreetingResponse(String greeting) {
}
```

请求和响应分成两个类型，即使现在都只有一个字段。原因是它们代表不同方向的契约，未来演进不一定同步。

---

## 6. 创建业务错误码

`DemoErrorCode.java`：

```java
package com.github.comui520.learnhub.demo;

import com.github.comui520.learnhub.common.exception.ErrorCode;

public enum DemoErrorCode implements ErrorCode {

    NAME_FORBIDDEN("DEMO_0422", "该名称不允许用于问候", 422);

    private final String code;
    private final String message;
    private final int httpStatus;

    DemoErrorCode(String code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }
}
```

重要设计点：common 并不知道 `DemoErrorCode`，但它实现了 `ErrorCode`，所以 `BusinessException` 和 `ApiResponse.failure` 都能接收它。这就是面向接口编程的实际用途。

---

## 7. 编写 Service

`DemoGreetingService.java`：

```java
package com.github.comui520.learnhub.demo;

import com.github.comui520.learnhub.common.exception.BusinessException;
import com.github.comui520.learnhub.demo.dto.GreetingResponse;
import org.springframework.stereotype.Service;

@Service
public class DemoGreetingService {

    public GreetingResponse greet(String name) {
        String normalizedName = name.trim();

        if ("forbidden".equalsIgnoreCase(normalizedName)) {
            throw new BusinessException(DemoErrorCode.NAME_FORBIDDEN);
        }

        return new GreetingResponse("Hello, " + normalizedName + "!");
    }
}
```

逐段解释：

- `@Service` 告诉组件扫描：请把这个类创建成 Spring Bean。
- `trim()` 去除首尾空格；校验保证 name 不为空，但合法输入仍可能有首尾空格。
- 常量写在左边调用 equals，可以避免变量为 null 时的空指针；这里 Validation 已挡住 null，但这个习惯仍清晰。
- `equalsIgnoreCase` 让 `forbidden` 大小写均触发规则。
- 抛出业务异常后，方法立即终止，不会继续返回成功。

为什么 Service 不返回 `ApiResponse`？因为 `ApiResponse` 是 Web 响应外壳；Service 应返回业务结果或抛业务异常，Controller 再处理 Web 表达。

---

## 8. 编写 Controller

`DemoController.java`：

```java
package com.github.comui520.learnhub.demo;

import com.github.comui520.learnhub.common.api.ApiResponse;
import com.github.comui520.learnhub.demo.dto.GreetingRequest;
import com.github.comui520.learnhub.demo.dto.GreetingResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/demo")
public class DemoController {

    private final DemoGreetingService greetingService;

    public DemoController(DemoGreetingService greetingService) {
        this.greetingService = greetingService;
    }

    @PostMapping("/greetings")
    public ApiResponse<GreetingResponse> greet(
            @Valid @RequestBody GreetingRequest request
    ) {
        GreetingResponse response = greetingService.greet(request.name());
        return ApiResponse.success(response);
    }
}
```

逐段解释：

- `@RestController`：这个类处理 HTTP，请求方法返回值直接序列化为响应 Body。
- `@RequestMapping("/api/v1/demo")`：类级公共路径。
- `@PostMapping("/greetings")`：方法级路径和 POST 方法；合起来是完整 URL。
- `@RequestBody`：从请求 Body 读取 JSON，并交给 Jackson 转成 `GreetingRequest`。
- `@Valid`：对象创建后执行 Validation 注解。
- `private final` + 构造器：构造器注入。Spring 发现唯一构造器，会提供 `DemoGreetingService` Bean。
- 不使用字段上的 `@Autowired`，因为构造器注入更利于不可变和测试。
- Controller 没有 try/catch；异常交给全局 Advice。

如果删除 `@Service`，Spring 找不到可以注入的 `DemoGreetingService` Bean，应用会在启动阶段报错，而不是请求到来后才报。

---

## 9. 设计参数错误明细

`FieldValidationError.java`：

```java
package com.github.comui520.learnhub.web.advice;

public record FieldValidationError(
        String field,
        String message
) {
}
```

为什么不把 Spring 的 `FieldError` 对象直接返回？它包含很多框架内部字段，响应会臃肿且把内部结构暴露给客户端。我们只选择 API 真正需要的 field 和 message。

---

## 10. 编写全局异常处理器

`GlobalExceptionHandler.java`：

```java
package com.github.comui520.learnhub.web.advice;

import com.github.comui520.learnhub.common.api.ApiResponse;
import com.github.comui520.learnhub.common.exception.BusinessException;
import com.github.comui520.learnhub.common.exception.CommonErrorCode;
import com.github.comui520.learnhub.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(
            BusinessException exception
    ) {
        ErrorCode errorCode = exception.getErrorCode();
        ApiResponse<Void> body = ApiResponse.failure(errorCode);

        log.warn("Business request rejected: code={}", errorCode.code());

        return ResponseEntity
                .status(HttpStatusCode.valueOf(errorCode.httpStatus()))
                .body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<List<FieldValidationError>>> handleValidationException(
            MethodArgumentNotValidException exception
    ) {
        List<FieldValidationError> errors = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> new FieldValidationError(
                        error.getField(),
                        error.getDefaultMessage()
                ))
                .toList();

        ApiResponse<List<FieldValidationError>> body = ApiResponse.failure(
                CommonErrorCode.INVALID_PARAMETER,
                errors
        );

        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableMessage(
            HttpMessageNotReadableException exception
    ) {
        ApiResponse<Void> body = ApiResponse.failure(CommonErrorCode.INVALID_PARAMETER);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(
            Exception exception
    ) {
        log.error("Unhandled server exception", exception);

        ApiResponse<Void> body = ApiResponse.failure(CommonErrorCode.INTERNAL_ERROR);
        return ResponseEntity.internalServerError().body(body);
    }
}
```

### 10.1 `@RestControllerAdvice`

它让该类对所有 Controller 生效，并默认把返回对象写入 JSON Body。

因为该类在 `com.github.comui520.learnhub` 子包中，启动类的组件扫描会发现它。

### 10.2 `@ExceptionHandler`

```java
@ExceptionHandler(BusinessException.class)
```

表示某 Controller 调用链抛出 `BusinessException` 时，使用这个方法处理。Spring 会优先寻找类型更具体的处理器；最后的 `Exception.class` 是兜底。

### 10.3 `ResponseEntity`

普通 `ApiResponse` 只控制 JSON Body；`ResponseEntity` 同时控制 HTTP 状态、Header 和 Body。

```java
ResponseEntity.badRequest().body(body)
```

表达 HTTP 400 + 指定 JSON Body。

### 10.4 Validation 错误 Stream

```java
exception.getBindingResult().getFieldErrors()
```

得到 Spring 的字段错误列表。随后：

- `stream()`：开始处理。
- `map(...)`：每个 Spring FieldError 转为我们自己的 `FieldValidationError`。
- `toList()`：收集成新列表。

### 10.5 为什么日志级别不同

- 业务拒绝是预期情况，用 warn 并不打印完整堆栈。
- 未知异常表示开发者没有预料到，使用 error 并记录完整 exception。
- 参数错误很常见，这里不记录堆栈，避免日志噪音。

### 10.6 为什么客户端看不到 exception message

未知异常可能包含 SQL、文件路径、类名或秘密。客户端只收到 `INTERNAL_ERROR` 的通用说明；完整信息保留在服务端日志。

---

## 11. 编译和启动

```powershell
cd D:\LearnHub\LearnHubBackend
mvn -pl learnhub-application -am clean package
java -jar .\learnhub-application\target\learnhub-application-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
```

如果启动失败说找不到 `DemoGreetingService`：

- 检查 `@Service` 是否存在。
- 检查 package 是否在根包下。
- 检查启动类仍在根包。

---

## 12. 手工验证四条路径

保持应用运行，另开 PowerShell。

### 12.1 成功请求

```powershell
curl.exe -i -X POST "http://localhost:8080/api/v1/demo/greetings" -H "Content-Type: application/json" -d '{"name":"LearnHub"}'
```

应看到 HTTP 200，JSON 中：

```json
{
  "code": "COMMON_0000",
  "message": "success",
  "data": {
    "greeting": "Hello, LearnHub!"
  }
}
```

timestamp 会是当前时间，不要求和示例相同。

### 12.2 参数错误

```powershell
curl.exe -i -X POST "http://localhost:8080/api/v1/demo/greetings" -H "Content-Type: application/json" -d '{"name":""}'
```

应看到 HTTP 400、`COMMON_0400`，data 中包含 name 的错误。

再测试只有空格：

```powershell
curl.exe -i -X POST "http://localhost:8080/api/v1/demo/greetings" -H "Content-Type: application/json" -d '{"name":"   "}'
```

它也应失败，证明 `@NotBlank` 与 `@NotEmpty` 不同。

### 12.3 业务错误

```powershell
curl.exe -i -X POST "http://localhost:8080/api/v1/demo/greetings" -H "Content-Type: application/json" -d '{"name":"forbidden"}'
```

应看到 HTTP 422 和：

```json
{
  "code": "DEMO_0422",
  "message": "该名称不允许用于问候",
  "data": null
}
```

服务端日志应有一条 warn，但无需完整堆栈。

### 12.4 非法 JSON

```powershell
curl.exe -i -X POST "http://localhost:8080/api/v1/demo/greetings" -H "Content-Type: application/json" -d '{bad json}'
```

应看到 HTTP 400。因为 Jackson 在进入 Controller 之前就无法创建 DTO，所以由 `HttpMessageNotReadableException` 处理器接住。

### PowerShell 引号问题

如果终端没有按预期传递 JSON，创建临时 `request.json`：

```json
{"name":"LearnHub"}
```

再执行：

```powershell
curl.exe -i -X POST "http://localhost:8080/api/v1/demo/greetings" -H "Content-Type: application/json" --data-binary "@request.json"
```

用完删除临时文件，不要提交。

---

## 13. 请求路径追踪练习

对成功请求，按执行顺序写出：

```text
JSON Body
 -> GreetingRequest
 -> Validation
 -> DemoController.greet
 -> DemoGreetingService.greet
 -> GreetingResponse
 -> ApiResponse<GreetingResponse>
 -> JSON Body
```

对空 name 请求：

```text
JSON Body
 -> GreetingRequest
 -> Validation 失败
 -> MethodArgumentNotValidException
 -> GlobalExceptionHandler
 -> HTTP 400 + ApiResponse
```

注意 Controller 和 Service 都没有执行。

对 forbidden 请求：Validation 通过，Controller 和 Service 都执行，Service 抛 BusinessException，然后 Advice 转换。

---

## 14. 常见错误

### 校验注解完全不生效

检查：

1. 启动模块有 `spring-boot-starter-validation`。
2. Controller 参数有 `@Valid`。
3. import 是 `jakarta.validation.Valid` 和 `jakarta.validation.constraints.*`。

### 返回 404

检查：

- 请求是否为 POST，不是浏览器地址栏默认 GET。
- 类级路径和方法级路径拼接是否正确。
- Controller 是否有 `@RestController`。
- Controller package 是否在根包下。

### 返回 415 Unsupported Media Type

通常没有发送：

```text
Content-Type: application/json
```

Spring 不知道按 JSON 读取 Body。

### Advice 没生效

检查 `@RestControllerAdvice`、package 扫描范围和 `@ExceptionHandler` 参数类型。

### 中文响应乱码

确认源码和 POM 使用 UTF-8，终端显示编码也可能影响视觉结果。先查看浏览器或测试中的 JSON，再判断是否是服务端编码问题。

---

## 15. 今日验收清单

- [ ] 我能解释 HTTP 请求的 URL、方法、Header、Body。
- [ ] 我能说清 DTO、Controller、Service、Advice 的职责。
- [ ] Controller 使用构造器注入。
- [ ] 请求使用 `@Valid @RequestBody`。
- [ ] name 的 null、空串、空白和超长输入会被拒绝。
- [ ] 成功返回 HTTP 200 和统一响应。
- [ ] 参数错误返回 HTTP 400。
- [ ] 业务拒绝返回 HTTP 422 和 DEMO 错误码。
- [ ] 非法 JSON 返回 HTTP 400。
- [ ] 未知错误不会把内部异常内容返回给客户端。
- [ ] Controller 中没有通用 try/catch。

---

## 16. 复盘题与参考答案

1. **`@RequestBody` 做什么？** 告诉 MVC 从 HTTP Body 读取数据，并用消息转换器（这里是 Jackson）反序列化为 Java 对象。
2. **`@Valid` 做什么？** 在 DTO 创建后触发 Bean Validation，违反约束时在 Controller 执行前抛校验异常。
3. **为什么 DTO 不用 Map？** DTO 有明确类型、字段、校验和文档契约，编译器与 IDE 能帮助发现错误。
4. **Controller 和 Service 如何分工？** Controller 处理 HTTP 边界；Service 组织用例和业务规则。
5. **为什么 Service 不返回 ApiResponse？** ApiResponse 属于 Web 表达，Service 应保持可被其他入口复用，只返回业务结果或抛业务异常。
6. **为什么 Controller 不捕获所有异常？** 会造成重复代码、格式不一致，甚至误把系统错误当成功；统一 Advice 是更合适的边界。
7. **参数错误与业务错误区别？** 参数错误是输入结构或基本约束不合法；业务错误是输入合法但违反当前业务规则。
8. **非法 JSON 为什么没进入 Controller？** 反序列化发生在方法调用前，Jackson 无法创建参数对象时 MVC 已经抛异常。
9. **ResponseEntity 与 ApiResponse 分别控制什么？** ResponseEntity 控制 HTTP 状态/Header/Body；ApiResponse 是 Body 的统一 JSON 结构。
10. **为什么未知异常要记录堆栈但不返回堆栈？** 开发者排错需要完整上下文，客户端不应看到内部实现和敏感信息。

完成后交给老师：七个新增 Java 文件、四次 curl 的完整状态码和响应、服务端业务错误日志，以及十道题自己的答案。

