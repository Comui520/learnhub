# Day 3：Java 泛型、枚举、异常与统一响应

## 今天的结果

在 `learnhub-common` 中完成统一响应、错误码契约和业务异常，并用纯 JUnit 单元测试验证它们。

预计用时：3～4 小时。

## 一、原理课

### 1. 为什么响应对象需要泛型

不同接口返回的数据类型不同：用户、知识库、分页列表，但响应外壳一致。

```text
ApiResponse<UserResponse>
ApiResponse<KnowledgeBaseResponse>
ApiResponse<List<DocumentResponse>>
```

泛型让编译器保留 `data` 的具体类型，避免把它声明成 `Object` 后到处强制转换。

### 2. `record` 适合什么

Java 21 的 `record` 适合不可变的数据载体，例如 API 响应和 DTO：

```java
public record ApiResponse<T>(
        String code,
        String message,
        T data,
        Instant timestamp
) {}
```

`record` 不适合需要复杂可变状态和继承层次的领域实体。

### 3. 错误码与 HTTP 状态码不是同一个概念

- HTTP 状态码描述协议层结果，例如 200、400、404、409、500。
- 业务错误码描述应用内的具体失败，例如 `COMMON_INVALID_PARAMETER`。

不要让所有错误都返回 HTTP 200；这会破坏网关、监控、客户端和缓存对协议语义的判断。

### 4. 受检异常和非受检异常

业务异常通常继承 `RuntimeException`，因为它需要沿调用栈传播到统一异常处理器，而不是强迫每一层机械捕获再抛出。

但“非受检”不等于“不处理”。处理位置从每个 Controller 移到了统一边界。

## 二、设计任务

在 `learnhub-common` 中设计：

```text
com.github.comui520.learnhub.common
├── api
│   └── ApiResponse.java
└── exception
    ├── ErrorCode.java
    ├── CommonErrorCode.java
    └── BusinessException.java
```

### 1. `ErrorCode` 接口

最小契约：

```java
public interface ErrorCode {
    String code();
    String message();
}
```

思考：为什么使用接口，而不是让 `BusinessException` 只接受某一个公共枚举？提示：未来每个业务模块都应拥有自己的错误码枚举。

### 2. `CommonErrorCode` 枚举

本周至少需要：

```text
SUCCESS
INVALID_PARAMETER
INTERNAL_ERROR
```

错误码应稳定、可搜索、可区分模块。可以采用：

```text
COMMON_0000
COMMON_0400
COMMON_0500
```

不要把详细异常堆栈或数据库信息放进用户可见 message。

### 3. `BusinessException`

要求：

- 继承 `RuntimeException`。
- 保存 `ErrorCode`。
- `getMessage()` 对开发者仍然有意义。
- 不要在异常类中决定 HTTP 响应或打印日志。

日志应在边界统一记录，否则多层重复记录会产生多份相同堆栈。

### 4. `ApiResponse<T>`

要求：

- 使用泛型。
- 包含 code、message、data、timestamp。
- 提供成功工厂方法。
- 提供失败工厂方法。
- timestamp 由服务端生成。
- 不使用可变的 `Date`。

先自己决定使用普通类还是 `record`，并写下理由。

## 三、测试任务

在 `learnhub-common/src/test/java` 中至少测试：

1. `success(data)` 保留正确的数据类型和值。
2. `failure(errorCode)` 不返回成功码，且 `data` 为空。
3. `BusinessException` 保留传入的错误码和消息。
4. timestamp 不为空。

运行：

```powershell
mvn -pl learnhub-common test
```

测试名应表达行为，例如：

```text
shouldCreateSuccessResponseWithData
shouldPreserveErrorCodeWhenBusinessExceptionCreated
```

## 四、Java 恢复练习

不要放进生产源码，可写成测试或独立笔记：

1. 创建 `List<ApiResponse<String>>`，使用 Stream 过滤出成功响应。
2. 把一组错误码转换成 code 列表。
3. 比较 `Instant.now()` 和 `LocalDateTime.now()`：哪一个更适合跨时区 API 时间戳？
4. 尝试让一个业务模块自己的 enum 实现 `ErrorCode`。

## 五、设计边界

`learnhub-common` 可以放：

- 稳定的响应协议。
- 错误码接口和公共错误。
- 通用业务异常。

暂时不要放：

- 用户注册规则。
- 文档状态。
- AI 模型配置。
- “可能以后有用”的万能工具类。

## 六、验收

- [ ] `ApiResponse<T>` 没有使用 `Object data`。
- [ ] HTTP 概念没有强行耦合进纯错误码接口。
- [ ] `BusinessException` 不打印日志、不吞掉错误码。
- [ ] 至少四个纯单元测试通过。
- [ ] `mvn -pl learnhub-common test` 成功。
- [ ] 能解释为什么选择普通类或 `record`。

## 七、口头复盘题

1. 泛型相比 `Object` 解决了什么问题？
2. 为什么不建议让所有模块共用一个巨大错误码枚举？
3. HTTP 400 与 `COMMON_0400` 分别服务于谁？
4. 为什么业务异常通常继承 `RuntimeException`？
5. 为什么异常不应该在每一层都记录一次日志？

## 八、今日提交建议

```text
feat(common): add API response and business error model
```

完成后，把三个核心类型和测试发给老师做第一次代码审查。

