# Day 5：JUnit 5、MockMvc 与 OpenAPI

## 今天的结果

用自动化测试固定示例接口的三条主要行为，并通过 Swagger UI 查看生成的接口契约。

预计用时：4～5 小时。

## 一、原理课

### 1. 测试金字塔

本项目后续会有：

- 单元测试：单个类，速度快，不启动 Spring。
- Web 切片测试：只加载 MVC 相关 Bean。
- 集成测试：启动较完整上下文，必要时使用 Testcontainers。
- 端到端测试：从 HTTP 到真实外部依赖。

本日示例接口优先使用 Web 切片测试。

### 2. `@WebMvcTest` 与 `@SpringBootTest`

- `@WebMvcTest`：关注 Controller、序列化、校验、异常处理，启动快。
- `@SpringBootTest`：加载完整应用上下文，覆盖更广但更慢，也容易被暂时无关的外部配置影响。

不要因为不会配置切片测试就把所有测试都换成完整上下文。

### 3. Mock 的边界

Web 测试中 Mock Service，是为了只验证 HTTP 边界。Service 自己的业务规则应由独立单元测试验证。

Spring Boot 3.5 对应的新代码优先使用：

```java
import org.springframework.test.context.bean.override.mockito.MockitoBean;
```

也就是 `@MockitoBean`。很多旧教程使用的 `@MockBean` 已进入弃用路线。

### 4. 测试行为，不测试实现细节

好的 Web 测试验证：

- HTTP 状态码。
- Content-Type。
- 响应 JSON 的关键字段。
- 参数校验是否阻止 Service 调用。

不要依赖私有方法或日志文本。

## 二、动手任务

### 任务 1：编写 Web 切片测试

建议：

```text
learnhub-application/src/test/java/com/github/comui520/learnhub/demo/
└── DemoControllerTest.java
```

至少覆盖：

1. 合法请求：HTTP 200，code 为成功码，greeting 正确。
2. name 为空：HTTP 400，包含 name 字段错误，Service 不应被调用。
3. Service 抛出 `BusinessException`：得到对应 4xx 和业务错误码。
4. 非法 JSON：HTTP 400。

测试工具：

- `MockMvc`
- `ObjectMapper`
- Mockito
- JSONPath

测试方法名使用行为描述，例如：

```text
shouldReturnGreetingWhenRequestIsValid
shouldReturnBadRequestWhenNameIsBlank
shouldReturnBusinessErrorWhenServiceRejectsName
```

### 任务 2：确保全局异常处理器进入切片

如果 `@WebMvcTest` 没有自动加载你的 advice，显式导入：

```java
@Import(GlobalExceptionHandler.class)
```

先理解切片加载范围，不要把测试直接改成 `@SpringBootTest` 来绕开问题。

### 任务 3：编写 Service 单元测试

不启动 Spring，直接构造 Service：

1. 普通名字生成正确问候语。
2. `forbidden` 触发预期业务错误。

### 任务 4：运行测试

```powershell
mvn -pl learnhub-application -am test
```

然后故意把一个 JSONPath 期望值写错，阅读失败输出，再恢复测试。这能训练你从断言差异定位问题。

## 三、OpenAPI

### 1. 启动并访问

默认地址通常为：

```text
http://localhost:8080/swagger-ui/index.html
http://localhost:8080/v3/api-docs
```

确认示例接口的：

- HTTP 方法和路径。
- 请求体字段。
- 参数必填信息。
- 返回类型。

### 2. 适量添加接口说明

可以使用：

- `@Tag`
- `@Operation`
- `@Schema`

但不要给每个显而易见的 getter 或字段写重复注释。接口名称、约束和错误语义比装饰性文字重要。

### 3. OpenAPI 不是测试

Swagger UI 能调通只说明一次手工请求成功。自动化测试才能稳定防止回归，两者不能互相替代。

## 四、常见错误

### 测试返回 404

检查 `@WebMvcTest` 指定的 Controller、请求路径和 HTTP 方法。

### 测试返回 401/403

说明 Security 进入了测试类路径。第一周先检查 Day 1 的依赖边界，不要长期使用关闭过滤器掩盖问题。

### Mockito 说没有调用或参数不匹配

检查 Mock 设定的参数是否和反序列化后传给 Service 的参数一致。

### Swagger UI 404

确认 springdoc starter 在启动模块的实际依赖中，并确认应用启动日志没有上下文错误。

## 五、验收

- [ ] 至少四个 Controller 测试通过。
- [ ] 至少两个 Service 单元测试通过。
- [ ] 空 name 时验证 Service 没有被调用。
- [ ] 测试没有依赖真实数据库、Redis、MQ 或 AI 服务。
- [ ] Swagger UI 可以显示并调用示例接口。
- [ ] `/v3/api-docs` 能返回 OpenAPI JSON。

## 六、口头复盘题

1. 单元测试、Web 切片测试和集成测试有什么不同？
2. 为什么 Controller 测试中可以 Mock Service？
3. `@WebMvcTest` 为什么比完整上下文测试快？
4. 什么是测试实现细节，为什么应该避免？
5. Swagger UI 为什么不能替代自动化测试？

## 七、今日提交建议

```text
test: cover demo API success and error responses
```

完成后，把测试类、测试报告摘要和 Swagger 截图或访问结果发给老师。

