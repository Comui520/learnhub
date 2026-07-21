# Day 5：让代码自己证明行为——JUnit、Mock、MockMvc 与 OpenAPI

## 0. 今天到底要学会什么

昨天用 curl 手工验证接口，但手工操作不可重复、容易漏场景。今天我们把预期写成自动化测试。以后任何人修改代码，Maven 都能告诉我们是否破坏了接口行为。

完成后，你应该能够：

1. 理解测试中的准备、执行、断言三个阶段。
2. 分清单元测试、Web 切片测试和完整集成测试。
3. 知道 Mock 是可控制的替身，不是真实 Service。
4. 使用 `@WebMvcTest`、MockMvc 和 JSONPath 验证 HTTP 行为。
5. 使用 Mockito 规定 Service 行为，并验证是否被调用。
6. 为 Service 写不启动 Spring 的单元测试。
7. 通过 Swagger UI 和 `/v3/api-docs` 查看接口文档。
8. 阅读失败测试，而不是看到红色就删除断言。

预计时间：6～7 小时。

---

## 1. 为什么需要自动化测试

假设你修改全局异常处理器后，依次手工测试：

- 正常输入。
- 空输入。
- 超长输入。
- 业务拒绝。
- 非法 JSON。

每次修改都重新点一遍，很快就会偷懒或漏掉。自动化测试把预期变成代码：

```text
输入合法 name
当调用 POST /api/v1/demo/greetings
那么状态应为 200，响应 code 应为 COMMON_0000
```

测试的价值不只是发现 Bug，还包括：

- 记录接口应该怎样工作。
- 支持安全重构。
- 在 CI 中自动检查提交。
- 迫使代码依赖更清晰。

---

## 2. 三种测试层级

### 2.1 单元测试

只测试一个普通 Java 类，不启动 Spring：

```java
DemoGreetingService service = new DemoGreetingService();
```

特点：快、定位清楚。适合 Service 业务规则、工具和值对象。

### 2.2 Web 切片测试

使用：

```java
@WebMvcTest(DemoController.class)
```

只加载 MVC 相关部分：Controller、JSON、Validation、MockMvc 等。Service 用 Mock 替代。

它验证：

- 路径和 HTTP 方法。
- JSON 反序列化/序列化。
- Validation。
- HTTP 状态。
- 全局异常转换。

### 2.3 完整集成测试

通常使用：

```java
@SpringBootTest
```

它会加载更完整的 Spring Context，未来可以配合 Testcontainers 验证数据库。覆盖广，但启动慢，而且容易受无关外部配置影响。

本日 Controller 适合 Web 切片，Service 适合单元测试。不要把所有测试都写成 `@SpringBootTest`。

---

## 3. 测试的 Arrange、Act、Assert

一个清晰测试通常分三段：

```java
// Arrange：准备输入和依赖行为

// Act：执行被测动作

// Assert：验证输出和交互
```

例如：

```java
// Arrange
DemoGreetingService service = new DemoGreetingService();

// Act
GreetingResponse result = service.greet("LearnHub");

// Assert
assertThat(result.greeting()).isEqualTo("Hello, LearnHub!");
```

方法名也应该描述行为：

```text
shouldReturnGreetingWhenNameIsValid
```

不要写只有 `test1`、`testMethod` 这种看不出意图的名字。

---

## 4. Mock 是什么

Controller 依赖真实 Service：

```text
DemoController -> DemoGreetingService
```

测试 Controller 时，我们暂时换成一个可控制替身：

```text
DemoController -> Mock DemoGreetingService
```

可以规定：

```java
given(greetingService.greet("LearnHub"))
        .willReturn(new GreetingResponse("Hello, LearnHub!"));
```

也可以规定它抛业务异常：

```java
given(greetingService.greet("forbidden"))
        .willThrow(new BusinessException(DemoErrorCode.NAME_FORBIDDEN));
```

这样 Controller 测试只关注 HTTP 边界，不重复测试 Service 内部实现。

Mock 不是万能的。Mock 太多会让测试只验证“自己编排的假世界”，所以后续仍需要少量真实集成测试。

---

## 5. 创建 Controller 测试

文件路径：

```text
learnhub-application/src/test/java/com/github/comui520/learnhub/demo/DemoControllerTest.java
```

完整代码：

```java
package com.github.comui520.learnhub.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.comui520.learnhub.common.exception.BusinessException;
import com.github.comui520.learnhub.demo.dto.GreetingRequest;
import com.github.comui520.learnhub.demo.dto.GreetingResponse;
import com.github.comui520.learnhub.web.advice.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DemoController.class)
@Import(GlobalExceptionHandler.class)
class DemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DemoGreetingService greetingService;

    @Test
    void shouldReturnGreetingWhenRequestIsValid() throws Exception {
        GreetingRequest request = new GreetingRequest("LearnHub");
        given(greetingService.greet("LearnHub"))
                .willReturn(new GreetingResponse("Hello, LearnHub!"));

        mockMvc.perform(post("/api/v1/demo/greetings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("COMMON_0000"))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.greeting").value("Hello, LearnHub!"))
                .andExpect(jsonPath("$.timestamp").exists());

        verify(greetingService).greet("LearnHub");
    }

    @Test
    void shouldReturnBadRequestWhenNameIsBlank() throws Exception {
        GreetingRequest request = new GreetingRequest("");

        mockMvc.perform(post("/api/v1/demo/greetings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_0400"))
                .andExpect(jsonPath("$.data[0].field").value("name"))
                .andExpect(jsonPath("$.data[0].message").value("name 不能为空"));

        verifyNoInteractions(greetingService);
    }

    @Test
    void shouldReturnBusinessErrorWhenServiceRejectsName() throws Exception {
        GreetingRequest request = new GreetingRequest("forbidden");
        given(greetingService.greet("forbidden"))
                .willThrow(new BusinessException(DemoErrorCode.NAME_FORBIDDEN));

        mockMvc.perform(post("/api/v1/demo/greetings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEMO_0422"))
                .andExpect(jsonPath("$.message").value("该名称不允许用于问候"))
                .andExpect(jsonPath("$.data").isEmpty());

        verify(greetingService).greet("forbidden");
    }

    @Test
    void shouldReturnBadRequestWhenJsonIsMalformed() throws Exception {
        mockMvc.perform(post("/api/v1/demo/greetings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{bad json}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_0400"));

        verifyNoInteractions(greetingService);
    }
}
```

---

## 6. 逐段读懂 Controller 测试

### 6.1 `@WebMvcTest`

```java
@WebMvcTest(DemoController.class)
```

告诉 Spring Boot：只为 `DemoController` 创建 Web 测试环境，不启动真实服务器端口。MockMvc 在内存里模拟请求进入 MVC。

### 6.2 `@Import`

```java
@Import(GlobalExceptionHandler.class)
```

显式把全局异常处理器加入这个测试上下文。这样测试能验证真实的错误响应转换。

### 6.3 `@MockitoBean`

```java
@MockitoBean
private DemoGreetingService greetingService;
```

创建 Mockito Mock，并将它注册为 Spring Bean，满足 Controller 构造器注入。

Boot 3.5 对应的新测试代码优先使用 `@MockitoBean`。很多旧教程的 `@MockBean` 已进入弃用路线，不要因为教程旧就照搬。

### 6.4 MockMvc

```java
mockMvc.perform(post("/api/v1/demo/greetings") ...)
```

构造一条模拟 POST 请求。它不经过真实网络，但经过 MVC 的映射、JSON、Validation、Controller 和 Advice。

### 6.5 ObjectMapper

```java
objectMapper.writeValueAsBytes(request)
```

把 Java `GreetingRequest` 序列化成 JSON。比手写字符串更不容易漏引号或字段名。

### 6.6 JSONPath

```java
jsonPath("$.data.greeting")
```

JSONPath 用路径定位 JSON：

- `$`：根对象。
- `$.code`：根的 code。
- `$.data.greeting`：data 内的 greeting。
- `$.data[0].field`：data 数组第一个元素的 field。

### 6.7 `verifyNoInteractions`

空 name 应在进入 Controller 业务调用前被 Validation 拦住，所以 Service 不应被调用。这个断言证明请求流程边界正确，而不只是响应碰巧为 400。

### 6.8 为什么测试方法写 `throws Exception`

MockMvc 的执行 API 可能抛受检异常。测试方法直接声明，JUnit 会在异常意外发生时把测试标为失败。这里不应该用空 catch 吞掉异常。

---

## 7. 创建 Service 单元测试

文件：

```text
learnhub-application/src/test/java/com/github/comui520/learnhub/demo/DemoGreetingServiceTest.java
```

完整代码：

```java
package com.github.comui520.learnhub.demo;

import com.github.comui520.learnhub.common.exception.BusinessException;
import com.github.comui520.learnhub.demo.dto.GreetingResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class DemoGreetingServiceTest {

    private final DemoGreetingService service = new DemoGreetingService();

    @Test
    void shouldReturnNormalizedGreetingWhenNameIsValid() {
        GreetingResponse response = service.greet("  LearnHub  ");

        assertThat(response.greeting()).isEqualTo("Hello, LearnHub!");
    }

    @Test
    void shouldThrowBusinessExceptionWhenNameIsForbidden() {
        BusinessException exception = catchThrowableOfType(
                () -> service.greet("FORBIDDEN"),
                BusinessException.class
        );

        assertThat(exception.getErrorCode()).isEqualTo(DemoErrorCode.NAME_FORBIDDEN);
        assertThat(exception.getMessage()).isEqualTo("该名称不允许用于问候");
    }
}
```

这里没有 `@SpringBootTest`、`@Autowired` 或 Mock。Service 没有外部依赖，可以直接 new，这就是纯单元测试。

第一个测试同时验证 trim；第二个验证大小写不敏感和具体业务错误码。

---

## 8. 运行测试

从根目录：

```powershell
cd D:\LearnHub\LearnHubBackend
mvn -pl learnhub-application -am test
```

`-am` 会先构建 common，因为 application 测试引用 common 类型。

成功时应看到所有测试：

```text
Failures: 0, Errors: 0
BUILD SUCCESS
```

测试报告位于：

```text
learnhub-common/target/surefire-reports/
learnhub-application/target/surefire-reports/
```

Surefire 是 Maven 默认运行单元测试的插件。

---

## 9. 故意让测试失败一次

把成功测试中的：

```java
.andExpect(jsonPath("$.data.greeting").value("Hello, LearnHub!"))
```

临时改成错误值：

```java
.andExpect(jsonPath("$.data.greeting").value("Wrong"))
```

运行单个测试类：

```powershell
mvn -pl learnhub-application -am test "-Dtest=DemoControllerTest"
```

阅读失败输出中的：

```text
Expected: Wrong
Actual: Hello, LearnHub!
```

然后恢复正确值并重新运行测试。不要把故意失败的代码提交。

这个练习让你明白红色测试报告不是“框架坏了”，而是在显示预期与实际的差异。

---

## 10. OpenAPI 与 Swagger UI

### 10.1 两者是什么

- OpenAPI：机器可读的 HTTP API 规范，通常是 JSON/YAML。
- Swagger UI：读取 OpenAPI 规范并生成可交互网页。
- springdoc：扫描 Spring MVC 代码并生成 OpenAPI。

它们解决“接口如何让前端、测试和其他开发者理解”的问题。

### 10.2 添加接口说明

在 `DemoController` 类上添加 import：

```java
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
```

类上：

```java
@Tag(name = "Demo", description = "第一周工程骨架验证接口")
```

`greet` 方法上：

```java
@Operation(summary = "生成问候语", description = "用于验证 JSON、参数校验和统一异常响应")
```

注解放置示意：

```java
@RestController
@RequestMapping("/api/v1/demo")
@Tag(name = "Demo", description = "第一周工程骨架验证接口")
public class DemoController {

    @PostMapping("/greetings")
    @Operation(summary = "生成问候语", description = "用于验证 JSON、参数校验和统一异常响应")
    public ApiResponse<GreetingResponse> greet(...) {
        ...
    }
}
```

### 10.3 为 DTO 添加字段说明

`GreetingRequest` 添加：

```java
import io.swagger.v3.oas.annotations.media.Schema;
```

并把组件写为：

```java
@Schema(description = "需要问候的名称", example = "LearnHub")
String name
```

完整片段：

```java
public record GreetingRequest(
        @NotBlank(message = "name 不能为空")
        @Size(max = 50, message = "name 最多 50 个字符")
        @Schema(description = "需要问候的名称", example = "LearnHub")
        String name
) {
}
```

### 10.4 启动并访问

```powershell
mvn -pl learnhub-application -am package
java -jar .\learnhub-application\target\learnhub-application-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
```

浏览器打开：

```text
http://localhost:8080/swagger-ui/index.html
```

机器可读 JSON：

```text
http://localhost:8080/v3/api-docs
```

在 Swagger UI 展开 Demo，点击 Try it out，输入：

```json
{"name":"LearnHub"}
```

执行后确认响应与 curl 一致。

### 10.5 文档不等于测试

Swagger 成功一次只能证明你这次手工请求成功；自动化测试能在每次构建中重复运行。OpenAPI 描述契约，测试证明行为，二者互补。

---

## 11. 常见错误

### `No qualifying bean of type DemoGreetingService`

Web 切片不会加载普通 Service。确认测试中有：

```java
@MockitoBean
private DemoGreetingService greetingService;
```

### 测试响应是 404

检查 Controller 是否由 `@WebMvcTest(DemoController.class)` 加载，路径和 POST 方法是否正确。

### 测试响应是 401/403

说明 Security 仍进入启动模块或测试类路径。回到 Day 1 检查依赖，不要为了通过测试长期写 `addFilters = false`。

### 参数错误测试的数组顺序不稳定

如果一个字段同时违反多个约束，错误列表顺序可能不应成为重要契约。可以用更灵活的 JSONPath，或只断言包含目标字段。本日空字符串通常只需关注至少存在 name 错误。

### `MockitoBean` 无法 import

确认使用 Spring Boot 3.5.16 管理的测试依赖并 Reload Maven。不要自行降级到旧版 Spring 测试包。

### Swagger UI 404

执行：

```powershell
mvn -pl learnhub-application dependency:tree "-Dincludes=org.springdoc"
```

确认 springdoc 在 application 的实际依赖中，并检查应用是否完整启动。

---

## 12. 今日验收清单

- [ ] 我能解释单元测试、Web 切片测试、集成测试。
- [ ] Controller 测试使用 Mock Service。
- [ ] 成功、空 name、业务错误、非法 JSON 四个 Web 测试通过。
- [ ] 空 name 时断言 Service 没有被调用。
- [ ] Service 的正常和业务拒绝两个单元测试通过。
- [ ] 我亲手制造、阅读并修复过一个失败断言。
- [ ] Swagger UI 能展示 Demo 接口。
- [ ] `/v3/api-docs` 返回 JSON。
- [ ] 测试不需要真实数据库、Redis、RabbitMQ 或 AI 服务。

---

## 13. 复盘题与参考答案

1. **为什么要写自动化测试？** 固定预期行为、重复发现回归、支持重构并作为可执行文档。
2. **单元测试和 Web 切片测试区别？** 单元测试直接测试普通类；Web 切片加载 MVC 基础设施，验证 HTTP 映射、JSON、校验和 Advice。
3. **为什么 Controller 测试 Mock Service？** 隔离 HTTP 边界，让业务行为由单独 Service 测试负责，失败时定位更清楚。
4. **Mock 的风险是什么？** 过多 Mock 可能只验证人工编排的假世界，与真实组件集成行为不同，因此后续还需集成测试。
5. **MockMvc 是否真的监听端口？** 不监听，它在测试进程内模拟请求进入 Spring MVC。
6. **JSONPath 的 `$` 是什么？** 表示 JSON 根节点，后续点和数组下标定位字段。
7. **为什么空 name 时验证 Service 无交互？** 证明 Validation 在业务调用前终止请求，而不只是最终响应恰好为 400。
8. **`@WebMvcTest` 为什么比 `@SpringBootTest` 快？** 它只加载 Web 切片，不创建完整应用和无关外部配置。
9. **OpenAPI、Swagger UI、springdoc 分别是什么？** OpenAPI 是规范；Swagger UI 是展示和交互页面；springdoc 从 Spring 代码生成规范。
10. **为什么 Swagger 不能代替测试？** 它主要描述和手工调用接口，不会在每次构建中自动验证所有分支。

完成后交给老师：两个测试类、Maven 测试摘要、一次故意失败的关键输出、Swagger UI 中的接口结果，以及十道题自己的答案。

