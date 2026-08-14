# Part 1 回顾教学：自动化测试与 OpenAPI 文档

> 适用对象：Part 1 已经做完，但提到“测试”和“接口文档”心里没底，想真正搞懂自己在写什么的人。
> 
> 做法：不从头再学一遍，而是把你 Part 1 写过的代码拿出来逐行讲透，再让你亲手破坏、修复、补写。预计 3～5 小时，可以拆成两三次完成。

## 0. 学完这篇文档，你要能回答六件事

1. 单元测试、切片测试、集成测试的区别，以及什么时候选哪个。
2. JUnit 5 的常用注解和 AssertJ 断言的用法。
3. Mockito 什么时候该用，`given` 和 `verify` 分别干什么。
4. MockMvc + JSONPath 如何验证一个 Web 请求的返回内容。
5. 为什么“测试通过”不等于“测到了你想测的东西”（你会亲手踩一次）。
6. OpenAPI 是什么，springdoc 如何把注解变成文档，文档怎么防止过期。

---

## 1. 为什么需要自动化测试：先把直觉建立起来

假设没有测试，你验证接口的方式是手动 curl：

```powershell
curl.exe -X POST "http://localhost:8080/api/v1/demo/greetings" -H "Content-Type: application/json" -d '{"name":"LearnHub"}'
```

手动验证有三个问题：

- **记不全**：一个接口有成功、参数错误、业务错误、非法 JSON 等路径，人很容易只测最顺的那条。
- **忘了回归**：今天改了 `GreetingService`，可能把“名字为 forbidden 时抛异常”的逻辑弄坏，但你只测了正常路径，不会发现。
- **不可重复**：手动测试的结果只存在于你的记忆和聊天记录里，没法让另一台电脑复现。

自动化测试解决的是这三件事：**把“接口应该怎么表现”写进代码，让机器每次都替你检查一遍**。所以测试的本质是“可执行的契约文档”。

面试时一句话总结：*测试让我敢改代码——改坏了，一分钟内机器会告诉我，而不是上线后被用户发现。*

---

## 2. 测试金字塔：三种测试分别管什么

```text
         /\
        /  \   集成测试：真数据库、真组件。慢、数量少、成本高
       /----\
      /      \  切片测试：拉起一部分 Spring 上下文，只测某一层
     /--------\
    /          \ 单元测试：只测一个类，不启动 Spring。快、数量最多
```

对应到你的项目：

| 层级   | 测试文件                                                            | 测什么                                            |
| ---- | --------------------------------------------------------------- | ---------------------------------------------- |
| 单元测试 | `GreetingServiceTest`、`ApiResponseTest`、`BusinessExceptionTest` | 一个类自己的逻辑，不启动 Spring                            |
| 切片测试 | `DemoControllerTest`                                            | 只加载 Web 层，验证 HTTP 请求进来后 Controller 的映射、校验、异常处理 |
| 集成测试 | 目前还没有                                                           | Part 2 接入数据库后出现（比如真连 MySQL 验证注册流程）             |

选型口诀：**能单元测试就不要切片，能切片就不要集成**。因为越往下越快、越稳定、越容易定位问题。

---

## 3. 单元测试回顾：JUnit 5 + AssertJ

### 3.1 先看 `GreetingServiceTest`（learnhub-application/src/test/.../demo/GreetingServiceTest.java）

```java
public class GreetingServiceTest {

    private final GreetingService greetingService = new GreetingService();

    @Test
    void shouldReturnNormalizedGreetingWhenNameIsValid(){
        GreetingResponse response = greetingService.greet("     LearnHub     ");
        assertThat(response.greeting()).isEqualTo("Hello, LearnHub!");
    }

    @Test
    void shouldThrowBusinessExceptionWhenNameIsForbidden() {
        BusinessException exception = catchThrowableOfType(
                () -> greetingService.greet("FORBIDDEN"),
                BusinessException.class
        );

        assertThat(exception.getErrorCode()).isEqualTo(DemoErrorCode.NAME_FORBIDDEN);
        assertThat(exception.getMessage()).isEqualTo("Name is forbidden");
    }
}
```

值得注意的三个点：

1. **直接 `new GreetingService()`，没有启动 Spring**。因为 `GreetingService` 没有任何依赖，最便宜的测法就是直接 new。等它将来依赖了 Repository，才需要引入 Mock 或 Spring 上下文。
2. **`@Test` 来自 JUnit 5（`org.junit.jupiter.api.Test`）**。一个方法一个 `@Test`，每个测试方法互相独立。
3. **断言用的是 AssertJ**：`assertThat(actual).isEqualTo(expected)`。它读起来像英语句子，失败时错误信息比 JUnit 自带的 `assertEquals` 更清楚。

### 3.2 断言异常：`assertThatThrownBy` 和 `catchThrowableOfType`

先纠正名字：你看到的“普通那个”叫 `assertThatThrownBy`；“按类型捕获那个”叫 `catchThrowableOfType`（不是 typeofclass——是 “catch Throwable Of Type”，按类型捕获异常）。

**写法一：`assertThatThrownBy`（声明式，链式断言）**

```java
assertThatThrownBy(() -> new BusinessException(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("errorCode must not be null");
```

要点：

- 参数是一个“会抛异常的动作”lambda。
- 返回一个断言对象，可以一路 `.isInstanceOf(...)`、`.hasMessage(...)`、`.extracting(...)` 链下去。
- **如果动作没抛异常，测试直接失败**——这是它最关键的保证。
- 适合：只关心“抛没抛、什么类型、消息对不对”。

需要断言异常内部字段时，也可以链下去：

```java
assertThatThrownBy(() -> authService.register(request))
        .isInstanceOf(BusinessException.class)
        .extracting(BusinessException::getErrorCode)
        .isEqualTo(UserErrorCode.USERNAME_ALREADY_EXISTS);
```

**写法二：`catchThrowableOfType`（先捕获，再拿变量断言）**

```java
BusinessException exception = catchThrowableOfType(
        () -> greetingService.greet("FORBIDDEN"),
        BusinessException.class
);
assertThat(exception.getErrorCode()).isEqualTo(DemoErrorCode.NAME_FORBIDDEN);
assertThat(exception.getMessage()).isEqualTo("Name is forbidden");
```

要点：

- 它把异常“接住”并**返回给你**，类型就是你传的第二个参数。
- 适合：捕获之后要做多段断言，或把异常对象继续传给别的方法。
- 注意：如果动作没抛异常，它返回 **null**——后续 `exception.getErrorCode()` 会 NPE。这个写法假设“一定会抛”，少了 `assertThatThrownBy` 那种“没抛就失败”的保护。

**顺带认识第三种：JUnit 自带的 `assertThrows`**

```java
BusinessException exception = org.junit.jupiter.api.Assertions.assertThrows(
        BusinessException.class,
        () -> authService.register(request)
);
```

和 `catchThrowableOfType` 类似：返回异常对象，没抛时直接失败。网上老教程很常见。**我们项目统一用 AssertJ**（前两种），因为断言风格一致、链式更丰富；看到 `assertThrows` 知道它是干嘛的就行。

**对比表**

| 方法 | 风格 | 没抛异常时 | 捕获后能做什么 | 什么时候选 |
|---|---|---|---|---|
| `assertThatThrownBy` | 声明式链式 | 测试直接失败 | `.isInstanceOf` / `.hasMessage` / `.extracting` 链 | 大多数情况，一行写完 |
| `catchThrowableOfType` | 先捕获拿变量 | 返回 null（有 NPE 风险） | 对变量做任意多段断言 | 需要把异常对象存下来反复用 |
| `assertThrows`（JUnit） | 先捕获拿变量 | 测试直接失败 | 对变量断言 | 看别人代码时认识即可 |

**记忆口诀**：只需要“验证抛了 + 长什么样” → `assertThatThrownBy`；要把异常对象拿出来继续用 → `catchThrowableOfType`。

### 3.3 测试方法的命名

你的测试方法名已经用得很好：

```text
shouldReturnNormalizedGreetingWhenNameIsValid
shouldThrowBusinessExceptionWhenNameIsForbidden
```

格式是 `should + 期望结果 + When + 条件`。别人看测试名就知道被测行为是什么。

建议再给每个测试加 `@DisplayName`，写中文说明，让测试报告更易读：

```java
@Test
@DisplayName("名字合法时返回去除首尾空格后的问候语")
void shouldReturnNormalizedGreetingWhenNameIsValid() {
    ...
}
```

### 3.4 一个小瑕疵：common 的测试包名和主代码不一致

主代码在 `com.github.comui520.learnhub.common.api` / `...exception`，但测试文件却放在 `com.github.comui520.api` / `com.github.comui520.exception`。Maven 不会因此报错，但 IDE 跳转、将来重构时会很别扭。建议改成与主代码一致的包名。这是你现在的项目里最值得顺手修的一个小问题。

---

## 4. Mockito 与测试替身：Web 测试为什么要 mock

### 4.1 先看 `DemoControllerTest` 的头部

```java
@WebMvcTest(DemoController.class)
@Import(GlobalExceptionHandler.class)
public class DemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GreetingService greetingService;

    @Autowired
    private ObjectMapper objectMapper;
    ...
}
```

`@WebMvcTest(DemoController.class)` 的意思是：**只拉起 Web 相关的这一小块 Spring 上下文**（MVC 映射、JSON 序列化、参数校验等），并且只注册指定的 Controller。它不会加载 `GreetingService` 这个 `@Service`。

那 Controller 里的 `greetingService` 怎么办？用 `@MockitoBean` 造一个假的放进去：

- `@MockitoBean`：**把 Mock 对象注册进 Spring 容器**，替换掉真实的 Bean。适合切片测试。
- `@Mock`：只在测试代码里手动创建 Mock，不经过 Spring 容器。适合纯单元测试（将来 `GreetingService` 依赖 Repository 时就会用到）。

> 注意：Spring Boot 3.4 之后推荐 `org.springframework.test.context.bean.override.mockito.MockitoBean`。网上老教程里的 `@MockBean`（`org.springframework.boot.test.mock.mockito`）已标记废弃，看到旧写法要知道换成新的。

`@Import(GlobalExceptionHandler.class)` 是显式声明“这个切片测试依赖全局异常处理器”。即使不写也可能被自动扫描到，但显式导入让测试依赖的组件一目了然，是更稳的做法。

### 4.2 `given`（安排）和 `verify`（检查）是两回事

**`given` 是“安排假行为”**，写在请求发出之前：

```java
given(greetingService.greet("LearnHub"))
        .willReturn(new GreetingResponse("Hello, LearnHub!"));
```

意思是：如果 Service 收到 `"LearnHub"`，就返回这个结果。这样 Controller 的逻辑就能完整跑通，而不依赖真实 Service。

也可以安排抛异常：

```java
given(greetingService.greet("forbidden"))
        .willThrow(new BusinessException(DemoErrorCode.NAME_FORBIDDEN));
```

**`verify` 是“事后检查真的发生了”**：

```java
verify(greetingService).greet("LearnHub");
```

检查“这个 Service 确实被调用过一次，参数是 `"LearnHub"`”。

**`verifyNoInteractions` 是“检查完全没被碰过”**：

```java
verifyNoInteractions(greetingService);
```

在空名字的测试里它很有意义：请求在进入 Controller 方法体之前就被校验拦截了，所以 Service 一次都不该被调用。这从侧面证明“校验确实发生在 Controller 边界”，而不是 Service 里。

反模式提醒：

- 不要 mock 你正在测的那个对象本身。
- 不要为了 verify 而 verify——只有当“这次交互不该发生/必须发生”是业务行为的一部分时才用。

---

## 5. MockMvc 与 Web 切片测试：怎么验证一个 HTTP 请求

### 5.1 一次请求的完整链路

```java
mockMvc.perform(
        post("/api/v1/demo/greetings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request))
)
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.code").value("COMMON_0000"))
        .andExpect(jsonPath("$.data").value("Hello, LearnHub!"))
        .andExpect(jsonPath("$.timestamp").exists());
```

拆开看：

| 片段                                                     | 作用                                      |
| ------------------------------------------------------ | --------------------------------------- |
| `post("/api/v1/demo/greetings")`                       | 指定方法和路径                                 |
| `.contentType(...)`                                    | 声明请求体是 JSON                             |
| `.content(objectMapper.writeValueAsBytes(request))`    | 把 `GreetingRequest` 对象序列化成 JSON 字节塞进请求体 |
| `.andExpect(status().isOk())`                          | 断言 HTTP 状态码是 200                        |
| `.andExpect(content().contentTypeCompatibleWith(...))` | 断言响应 Content-Type 是 JSON                |
| `.andExpect(jsonPath("$.code").value("COMMON_0000"))`  | 断言响应体里 `code` 字段的值                      |
| `.andExpect(jsonPath("$.timestamp").exists())`         | 断言 `timestamp` 字段存在（不校验具体值）             |

### 5.2 重要概念：MockMvc 不经过真实网络

MockMvc 是在测试上下文里**直接调用 MVC 处理链**，没有真的监听端口、没有 TCP 连接。所以它比 curl 快得多，但也意味着它测不到“部署、端口、防火墙”这类问题。那部分由真正的启动验证（curl 8080）和集成测试负责。

### 5.3 JSONPath 速查

| 表达式               | 含义                        | 你的项目里的例子                                  |
| ----------------- | ------------------------- | ----------------------------------------- |
| `$.code`          | 取根对象下 `code` 字段           | `jsonPath("$.code").value("COMMON_0000")` |
| `$.data[0].field` | 取 `data` 数组第一个元素的 `field` | 校验失败时 `data` 是字段错误数组                      |
| `.value("xxx")`   | 精确匹配值                     | `.value("Hello, LearnHub!")`              |
| `.exists()`       | 字段存在（不关心值）                | `.exists()` 匹配 `timestamp`                |
| `.isEmpty()`      | 值为空（null/空数组/空字符串）        | 业务错误时 `data` 为 null，`.isEmpty()` 匹配       |

### 5.4 你的四条测试各在验证什么

| 测试                                                | 输入                     | 期望                                             | 验证的路径                         |
| ------------------------------------------------- | ---------------------- | ---------------------------------------------- | ----------------------------- |
| `shouldReturnGreetingWhenRequestIsValid`          | `{"name":"LearnHub"}`  | 200，`code=COMMON_0000`，`data=Hello, LearnHub!` | 成功路径                          |
| `shouldReturnBadRequestWhenNameIsBlank`           | `{"name":""}`          | 400，`data[0].field=name`                       | `@NotBlank` 校验失败路径            |
| `shouldReturnBusinessErrorWhenServiceRejectsName` | `{"name":"forbidden"}` | 422，`code=DEMO_ERROR_0422`                     | `BusinessException` → 全局异常处理器 |
| `shouldReturnBadRequestWhenJsonIsMalformed`       | `{"bad json":"fuck"}`  | 400，`code=COMMON_0400`                         | ⚠️ 见下一节，这里有问题                 |

---

## 6. 一个必须纠正的问题：你的 “malformed JSON” 测试名不副实

最后一条测试叫 `shouldReturnBadRequestWhenJsonIsMalformed`，请求体是：

```java
.content("{\"bad json\":\"fuck\"}")
```

但 **`{"bad json":"fuck"}` 是合法 JSON**。JSON 的 key 允许包含空格，Jackson 能正常解析它，只是发现 `bad json` 不是 `GreetingRequest` 的字段，于是忽略（Spring Boot 默认关闭 `FAIL_ON_UNKNOWN_PROPERTIES`）。结果是 `name` 为 null，触发了 `@NotBlank` 校验失败——**走的是校验路径，不是“JSON 无法解析”路径**。

也就是说：测试通过了，但它根本没有测到名字想测的东西。这是“测试通过 ≠ 测对了”的典型例子。

### 6.1 真正的 malformed JSON 长什么样

```java
.content("{\"name\": }")
```

`{"name": }` 语法不完整，Jackson 解析直接抛异常，Spring MVC 把它包装成 `HttpMessageNotReadableException`，由 `GlobalExceptionHandler` 里专门的方法处理（返回 400，`data` 为 null）。

### 6.2 两条 400 路径怎么区分

看 `GlobalExceptionHandler` 的两个方法：

- `handleValidationException`：`data` 是字段错误数组（有 `field`/`message` 明细）。
- `handleUnreadableMessage`：`data` 是 null。

所以修正后的测试可以这样写，顺便验证走的是哪条路：

```java
@Test
@DisplayName("请求体不是合法 JSON 时返回 400，且没有字段明细")
void shouldReturnBadRequestWhenJsonIsMalformed() throws Exception {
    mockMvc.perform(post("/api/v1/demo/greetings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\": }"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("COMMON_0400"))
            .andExpect(jsonPath("$.data").isEmpty());

    verifyNoInteractions(greetingService);
}
```

> 动手验证：把原来的 `{"bad json":"fuck"}` 请求体改成 `{"name": }`，测试一样会绿；再在测试里加一行 `jsonPath("$.data[0].field").value("name")`——原来那条会通过，真正 malformed 的这条会失败。亲眼看到这个差别，你就真正理解了。

---

## 7. 测试常见陷阱清单

1. **断言太松**：只查了状态码没查内容，改了字段名测试照样绿。
2. **测试名与行为不符**：见上面的 malformed JSON 例子，写测试时先问自己“我在验证哪条代码路径”。
3. **测试之间共享可变状态**：JUnit 每个测试方法都是新实例，但静态变量、Spring 容器里的 Bean 是共享的，别在里面残留数据。
4. **断言动态值**：`timestamp` 这类每次都变的字段用 `.exists()`，不要断言具体值。
5. **Mock 行为与真实行为不一致**：`willThrow` 的异常、message 要和真实代码一致，否则测的是“你编的假世界”。
6. **包名不一致**：测试类包名最好和被测代码一致（你 common 模块目前不一致，见 3.4）。

---

## 8. OpenAPI 回顾：注解如何变成接口文档

### 8.1 三个名词的关系

- **OpenAPI**：一种描述 HTTP 接口的规范（JSON/YAML），描述路径、参数、请求体、响应格式。
- **Swagger UI**：把 OpenAPI 文档渲染成可交互网页的工具（可以直接在页面上发请求）。
- **springdoc**：Spring Boot 项目里生成 OpenAPI 文档的库，它扫描你的注解和代码，自动产出 `/v3/api-docs` 的 JSON，并自带 Swagger UI。

一句话：**你在代码里写注解 → springdoc 生成 OpenAPI JSON → Swagger UI 把 JSON 渲染成页面**。

### 8.2 你项目里的接入点

依赖在 `learnhub-application/pom.xml`：

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
</dependency>
```

版本由父 POM 统一管理（2.8.17）。启动应用（dev Profile，端口 8080）后有三个地址：

| 地址                                            | 作用              |
| --------------------------------------------- | --------------- |
| `http://localhost:8080/swagger-ui/index.html` | 可视化页面，可交互调用     |
| `http://localhost:8080/v3/api-docs`           | OpenAPI 原始 JSON |
| `http://localhost:8080/actuator/health`       | 健康检查（顺手）        |

> 注意端口：`application.yml` 默认是 9090，`application-dev.yml` 是 8080。用 dev Profile 启动才是上面的地址，这是很常见的混淆点。

### 8.3 你已经用到的注解

对照 `DemoController`：

```java
@Tag(name = "Demo", description = "仅仅是测试接口")                    // 给整个 Controller 分组起名
@RestController
@RequestMapping("/api/v1/demo")
public class DemoController {

    @Operation(summary = "生成问候语", description = "用于验证 JSON、参数校验和统一异常响应")  // 描述单个接口
    @PostMapping("/greetings")
    public ApiResponse<String> greet(@Valid @RequestBody GreetingRequest request) { ... }
}
```

对照 `GreetingRequest`：

```java
public record GreetingRequest(
        @Schema(description = "需要问候的名称", example = "LearnHub")   // 描述字段含义和示例值
        @NotBlank(message = "Name should not be blank")
        @Size(max=50, message = "Name should not be more than 50 characters")
        String name
) { }
```

**`@Schema` 的 `example` 很重要**：Swagger 页面会用它预填请求示例，别人一看就知道该传什么。

### 8.4 你的 `GreetingResponse` 还没有注解，补上

```java
package com.github.comui520.learnhub.demo.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "问候响应")
public record GreetingResponse(
        @Schema(description = "生成的问候语", example = "Hello, LearnHub!")
        String greeting
) {
}
```

### 8.5 把错误响应也写进文档

现在的文档只写了“成功返回”，400/422 的错误语义是隐形的。可以给接口补上：

```java
@Operation(summary = "生成问候语", description = "用于验证 JSON、参数校验和统一异常响应")
@ApiResponses({
        @ApiResponse(responseCode = "200", description = "成功，返回问候语"),
        @ApiResponse(responseCode = "400", description = "参数错误（COMMON_0400）"),
        @ApiResponse(responseCode = "422", description = "业务错误（DEMO_ERROR_0422）")
})
@PostMapping("/greetings")
public ApiResponse<String> greet(@Valid @RequestBody GreetingRequest request) { ... }
```

> ⚠️ 这里有一个经典坑：springdoc 的注解叫 `ApiResponse`，你项目自己的统一响应类也叫 `ApiResponse`（`com.github.comui520.learnhub.common.api.ApiResponse`）。Java 不允许同一个文件 import 两个同名类型，所以**不能同时 import 这两个 `ApiResponse`**。如果 IDE 提示“@ApiResponse 应为注解类型”，说明 `@ApiResponse` 被解析成了项目自己的响应类。解决办法：保留自己的 `ApiResponse` import，注解写全限定名 `@io.swagger.v3.oas.annotations.responses.ApiResponse(...)`（上面代码就是这么写的）。`@ApiResponses` 没有同名冲突，可以正常 import。

### 8.6 文档怎么防止过期

接口文档最大的敌人是“改完代码忘了改文档”。三个实用手段：

1. **测试即契约**：你的 `jsonPath` 断言已经在锁定响应结构，改坏响应格式测试会红。这是防止文档过期的第一道防线。
2. **改接口时顺手改注解**：把“更新 Swagger 注解”写进你的功能完成清单。
3. **Part 2 预告**：接 JWT 后，用 `@SecurityScheme` 声明 Bearer Token，Swagger 页面就能直接输入 token 调受保护接口：

```java
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
```

---

## 9. 主动制造错误（每个做完都要恢复）

以下练习都建议做完后跑 `mvn test` 观察失败信息，再改回来确认全绿。

**练习 A：改路径**

把 `DemoController` 的 `@PostMapping("/greetings")` 改成 `@GetMapping("/greetings")`，运行 `DemoControllerTest`，观察失败信息里 404 的表述。改回来。

**练习 B：改断言**

把成功测试里的 `jsonPath("$.data").value("Hello, LearnHub!")` 改成 `value("Hello, Wrong!")`，观察 AssertJ/JsonPath 的失败信息，学会“读测试输出定位问题”。改回来。

**练习 C：去掉 `@NotBlank`**

临时删掉 `GreetingRequest` 上的 `@NotBlank`，运行空名字测试，观察它从 400 变成什么（提示：请求会进到 Service，`name.trim()` 后变成空字符串，返回 200）。这能让你亲眼看到“校验发生在哪个边界”。恢复并再跑一遍。

**练习 D：补一个真正 malformed JSON 的测试**

自己写（参考第 6 节），断言 400 且 `data` 为空。

---

## 10. 独立练习（先自己写，再对答案）

1. 给 `DemoControllerTest` 的四个测试都加上中文 `@DisplayName`。
2. 给 `GreetingResponse` 补 `@Schema` 注解（8.4 有答案）。
3. 写一个测试：`name` 超过 50 个字符时返回 400，且错误字段是 `name`。
4. 写一个测试：请求体是 `{"name": }` 时返回 400，且 `data` 为空（与第 3 题对比，理解两条不同路径）。
5. 启动应用，打开 Swagger UI，找到 greetings 接口，确认请求示例里预填了 `LearnHub`。
6. 思考题：为什么空名字的测试里可以放心使用 `verifyNoInteractions(greetingService)`？

---

## 11. 参考答案

**第 3 题**：

```java
@Test
@DisplayName("名字超过 50 字符时返回 400 并指出错误字段")
void shouldReturnBadRequestWhenNameIsTooLong() throws Exception {
    GreetingRequest request = new GreetingRequest("x".repeat(51));

    mockMvc.perform(post("/api/v1/demo/greetings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("COMMON_0400"))
            .andExpect(jsonPath("$.data[0].field").value("name"));
}
```

**第 4 题**：见第 6.2 节的修正版测试。

**第 6 题**：`@Valid` 触发的方法参数校验发生在 Spring MVC 调用 Controller 方法体**之前**。`name` 为空导致 `MethodArgumentNotValidException`，请求根本没有走到 `greet()` 里，所以 Service 一次都不会被调用。

---

## 12. 自查清单

能脱离代码回答以下问题，才算真正过关：

- [ ] 单元测试、切片测试、集成测试怎么选？你项目里各对应哪些文件？
- [ ] MockMvc 经过真实网络吗？它测不到什么？
- [ ] `@MockitoBean` 和 `@Mock` 的区别是什么？
- [ ] `given(...).willReturn(...)` 和 `verify(...)` 分别验证什么？
- [ ] `$.data[0].field`、`.exists()`、`.isEmpty()` 分别怎么用？
- [ ] 为什么“测试通过”不等于“测对了”？你项目里哪个测试是反例？
- [ ] OpenAPI、Swagger UI、springdoc 三者关系是什么？
- [ ] 你项目的文档地址和端口是什么？
- [ ] `@Tag`、`@Operation`、`@Schema`、`@ApiResponse` 各用在什么位置？
- [ ] 接口文档怎么防止过期？测试在其中扮演什么角色？
