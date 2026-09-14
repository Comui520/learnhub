# Day 3：把 Java 基础变成项目代码——泛型、错误码、异常与统一响应

## 0. 今天到底要学会什么

今天开始写真正会被其他模块复用的代码。我们会先不用数据库，也不用 Spring，只用 Java 21 把公共契约写清楚。

完成后，你应该能够：

1. 说清楚为什么 `ApiResponse<T>` 使用泛型。
2. 看懂 Java `record` 的声明、构造和访问方式。
3. 使用接口表示“错误码必须提供哪些信息”。
4. 使用枚举实现一组稳定的公共错误。
5. 理解 `RuntimeException` 为什么可以跨层传播。
6. 使用 `Instant` 表示 API 时间戳。
7. 写不启动 Spring 的纯单元测试。

预计时间：5～6 小时。

---

## 1. 为什么接口返回不能五花八门

假设没有统一规范，接口可能分别返回：

```json
{"name":"Alice"}
```

```json
{"success":false,"error":"参数不对"}
```

```json
{"code":500,"data":null}
```

前端、日志系统和后续网关就需要为每个接口写不同判断。我们希望所有接口外层结构一致：

```json
{
  "code": "COMMON_0000",
  "message": "success",
  "data": {"name": "Alice"},
  "timestamp": "2026-07-20T12:00:00Z"
}
```

`data` 的内容会变化，但 `code`、`message`、`timestamp` 的位置和含义稳定。这就是统一响应对象的意义。

---

## 2. 泛型：给“外壳”保留具体数据类型

### 2.1 不使用泛型会怎样

你可能会写：

```java
class BadResponse {
    private Object data;
}
```

调用方拿到 `Object`，必须自己强制转换：

```java
User user = (User) response.getData();
```

如果转换错了，代码可能编译成功，运行时才抛 `ClassCastException`。

### 2.2 使用泛型

```java
ApiResponse<String> textResponse;
ApiResponse<List<String>> listResponse;
ApiResponse<UserResponse> userResponse;
```

`T` 是一个类型占位符。创建对象时把 T 替换成具体类型，编译器就能检查 data 的类型。

```java
public class Box<T> {
    private final T value;

    public Box(T value) {
        this.value = value;
    }

    public T value() {
        return value;
    }
}
```

使用：

```java
Box<String> box = new Box<>("hello");
String value = box.value();
```

`value()` 返回 String，不需要强制转换。

### 2.3 `static <T>` 为什么有两个 T

方法：

```java
public static <T> ApiResponse<T> success(T data)
```

从左到右读：

1. `static`：不需要对象即可调用。
2. `<T>`：声明这个方法自己的泛型参数。
3. `ApiResponse<T>`：返回值类型使用这个泛型。
4. `(T data)`：参数类型也使用这个泛型。

调用：

```java
ApiResponse<String> result = ApiResponse.success("hello");
```

编译器根据参数推断 T 是 String。

---

## 3. `record`：适合不可变数据

传统 Java 数据类需要很多样板代码：字段、构造方法、getter、`equals`、`hashCode`、`toString`。Java 16+ 提供 `record`：

```java
public record Person(String name, int age) {}
```

它自动提供：

- `private final` 组件。
- 全参数构造器。
- `name()`、`age()` 访问方法。
- `equals`、`hashCode`、`toString`。

注意 record 的访问器叫 `name()`，不是 JavaBean 风格的 `getName()`。

record 适合：

- API 请求/响应 DTO。
- 不可变的值对象。
- 简单的配置或结果对象。

不适合：

- 需要频繁修改字段的实体。
- 需要继承其他类的对象。
- 有复杂生命周期和大量行为的领域对象。

---

## 4. 错误码：让错误可以被机器识别

### 4.1 只返回中文 message 的问题

如果前端只判断：

```text
message == "余额不足"
```

以后改个字、换语言或调整文案，前端逻辑就坏了。

所以响应同时包含：

- `code`：稳定、给程序判断。
- `message`：给人阅读，可以调整。

### 4.2 为什么用接口

公共模块不应该提前知道所有业务错误。未来用户模块、知识库模块、额度模块都会有自己的错误码。

它们只要遵守同一个接口：

```java
public interface ErrorCode {
    String code();
    String message();
    int httpStatus();
}
```

这个接口就是契约：任何错误码都必须提供三项信息。

这里暂时使用 `int httpStatus()`，而不是直接返回 Spring 的 `HttpStatus`，让 common 保持纯 Java，不依赖 Web 框架。Day 4 的 Web 层再把数字转换成 HTTP 状态类型。

### 4.3 为什么用 enum

公共错误码是有限且固定的一组值，很适合枚举：

```java
public enum CommonErrorCode implements ErrorCode {
    SUCCESS("COMMON_0000", "success", 200),
    INVALID_PARAMETER("COMMON_0400", "请求参数错误", 400),
    INTERNAL_ERROR("COMMON_0500", "系统内部错误", 500);
}
```

枚举值不能被调用方随意 new，能避免 code 拼写散落在项目各处。

---

## 5. 业务异常：把“可预期失败”带回统一边界

### 5.1 业务失败不是系统崩溃

例子：

- 用户输入空名称：参数错误。
- 用户没有额度：业务拒绝，是可以预期的。
- 数据库连接池崩溃：系统故障，不是正常业务分支。

业务代码需要一个清晰方式表达第二种情况：

```java
throw new BusinessException(DemoErrorCode.NAME_FORBIDDEN);
```

它不会在 Service 中直接拼 HTTP JSON，而是向上抛到 Day 4 的全局异常处理器。

### 5.2 为什么继承 RuntimeException

如果继承 checked exception，每层方法都必须声明 `throws` 或捕获，Controller、Service、Repository 之间会产生大量机械代码。

业务异常属于“可以在统一边界处理的运行时失败”，使用 `RuntimeException` 让它自然沿调用栈传播。

注意：RuntimeException 不是“无需处理”。我们会在全局处理器中集中处理。

---

## 6. 现在创建文件

### 6.1 文件目录

在 `learnhub-common` 中创建：

```text
learnhub-common/src/main/java/com/github/comui520/learnhub/common/
├── api/
│   └── ApiResponse.java
└── exception/
    ├── ErrorCode.java
    ├── CommonErrorCode.java
    └── BusinessException.java
```

如果目录不存在，在 IDEA 的 `src/main/java` 上右键，选择 New → Package，输入完整 package；不要在 Windows 资源管理器中创建一个名字包含点的目录。

### 6.2 `ErrorCode.java`

```java
package com.github.comui520.learnhub.common.exception;

/**
 * 所有可返回给调用方的错误码都必须实现这个契约。
 */
public interface ErrorCode {

    /** 稳定的机器可读编码，例如 COMMON_0400。 */
    String code();

    /** 给用户或日志看的默认说明。 */
    String message();

    /** 对应的 HTTP 状态数字，由 Web 层负责真正转换。 */
    int httpStatus();
}
```

逐段看：

- `package` 必须与目录和其他引用一致。
- `public interface` 表示这是一个公开契约，不能直接 new。
- 接口方法默认是 public abstract，不需要重复写。
- 注释中的 `COMMON_0400` 只是示例，不会执行。

### 6.3 `CommonErrorCode.java`

```java
package com.github.comui520.learnhub.common.exception;

public enum CommonErrorCode implements ErrorCode {

    SUCCESS("COMMON_0000", "success", 200),
    INVALID_PARAMETER("COMMON_0400", "请求参数错误", 400),
    INTERNAL_ERROR("COMMON_0500", "系统内部错误", 500);

    private final String code;
    private final String message;
    private final int httpStatus;

    CommonErrorCode(String code, String message, int httpStatus) {
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

逐段看：

- `implements ErrorCode`：枚举承诺实现接口的三个方法。
- 枚举值后面的括号调用私有构造器，保存三项信息。
- 字段是 `final`，创建后不能更换错误码。
- `@Override` 表示方法来自接口；如果方法名写错，编译器会提醒。

### 6.4 `BusinessException.java`

```java
package com.github.comui520.learnhub.common.exception;

import java.util.Objects;

public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(Objects.requireNonNull(errorCode, "errorCode must not be null").message());
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
```

关键点：

- `extends RuntimeException`：这是一个运行时异常。
- `errorCode` 保存完整错误契约，而不是只保存字符串。
- `super(...)` 调用父类异常的构造器，让 `getMessage()` 有值。
- `Objects.requireNonNull` 如果传入 null，会立即失败；比后面处理一个没有错误码的异常更容易排错。
- 对于普通 class，我们使用 JavaBean 风格的 `getErrorCode()`；record 才使用 `errorCode()`。

### 6.5 `ApiResponse.java`

```java
package com.github.comui520.learnhub.common.api;

import com.github.comui520.learnhub.common.exception.CommonErrorCode;
import com.github.comui520.learnhub.common.exception.ErrorCode;

import java.time.Instant;
import java.util.Objects;

public record ApiResponse<T>(
        String code,
        String message,
        T data,
        Instant timestamp
) {

    public ApiResponse {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(
                CommonErrorCode.SUCCESS.code(),
                CommonErrorCode.SUCCESS.message(),
                data,
                Instant.now()
        );
    }

    public static <T> ApiResponse<T> failure(ErrorCode errorCode) {
        return failure(errorCode, null);
    }

    public static <T> ApiResponse<T> failure(ErrorCode errorCode, T data) {
        Objects.requireNonNull(errorCode, "errorCode must not be null");
        return new ApiResponse<>(
                errorCode.code(),
                errorCode.message(),
                data,
                Instant.now()
        );
    }
}
```

逐段看：

- `record ApiResponse<T>`：这是不可变泛型响应。
- `public ApiResponse { ... }`：这是 record 的紧凑构造器，用于检查参数。
- `data` 没有被强制要求非空，因为错误响应可能没有 data。
- `Instant` 是 UTC 时间点，适合跨时区系统；不要用本机格式化字符串保存时间。
- `success` 和 `failure` 是静态工厂方法，用于隐藏重复的构造参数。
- `failure(ErrorCode)` 调用重载方法，把 data 设为 null。
- `<>` 是菱形语法，让编译器推断泛型参数。

### 6.6 编译 common

```powershell
cd D:\LearnHub\LearnHubBackend
mvn -pl learnhub-common compile
```

如果出现 `package ... does not exist`：

1. 检查文件是否放在 `src/main/java` 而不是 `src/test/java`。
2. 检查 package 拼写。
3. 检查 import 是否完整。
4. 确认文件名与 public class/record 名相同。

---

## 7. 用 JUnit 验证公共类

### 7.1 测试目录和测试类

创建：

```text
learnhub-common/src/test/java/com/github/comui520/learnhub/common/
├── api/ApiResponseTest.java
└── exception/BusinessExceptionTest.java
```

### 7.2 `ApiResponseTest.java`

```java
package com.github.comui520.learnhub.common.api;

import com.github.comui520.learnhub.common.exception.CommonErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @Test
    void shouldCreateSuccessResponseAndKeepGenericData() {
        ApiResponse<List<String>> response = ApiResponse.success(List.of("Java", "Spring"));

        assertThat(response.code()).isEqualTo(CommonErrorCode.SUCCESS.code());
        assertThat(response.message()).isEqualTo("success");
        assertThat(response.data()).containsExactly("Java", "Spring");
        assertThat(response.timestamp()).isNotNull();
    }

    @Test
    void shouldCreateFailureResponseWithoutData() {
        ApiResponse<Void> response = ApiResponse.failure(CommonErrorCode.INVALID_PARAMETER);

        assertThat(response.code()).isEqualTo("COMMON_0400");
        assertThat(response.message()).isEqualTo("请求参数错误");
        assertThat(response.data()).isNull();
        assertThat(response.timestamp()).isNotNull();
    }

    @Test
    void shouldCreateFailureResponseWithDetails() {
        ApiResponse<String> response = ApiResponse.failure(
                CommonErrorCode.INVALID_PARAMETER,
                "name 不能为空"
        );

        assertThat(response.data()).isEqualTo("name 不能为空");
    }
}
```

测试逐段理解：

- `@Test` 告诉 JUnit 这是一个测试方法。
- 测试方法不需要 `public`，JUnit 5 可以发现包级可见方法。
- `List.of` 创建不可变列表。
- `assertThat(...).isEqualTo(...)` 是 AssertJ 断言，失败时信息比普通 `assert` 更清楚。
- 测试的是公开行为，不测试 record 的私有实现。

### 7.3 `BusinessExceptionTest.java`

```java
package com.github.comui520.learnhub.common.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessExceptionTest {

    @Test
    void shouldKeepErrorCodeAndMessage() {
        BusinessException exception = new BusinessException(CommonErrorCode.INVALID_PARAMETER);

        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_PARAMETER);
        assertThat(exception.getMessage()).isEqualTo("请求参数错误");
    }

    @Test
    void shouldRejectNullErrorCode() {
        assertThatThrownBy(() -> new BusinessException(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("errorCode must not be null");
    }
}
```

`assertThatThrownBy` 用来验证一段代码应该抛出什么异常。好的测试不仅验证“正常能工作”，也验证“错误输入不会悄悄产生坏对象”。

### 7.4 运行测试

```powershell
mvn -pl learnhub-common test
```

成功输出末尾应有：

```text
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

测试数量如果和这里不同不一定有问题，只要你的测试全部通过。

---

## 8. Java 基础小练习（带答案）

先把下面问题写在笔记里，再查看答案。

### 练习 A：泛型推断

```java
ApiResponse<String> response = ApiResponse.success("hello");
```

问：这个调用中 T 是什么？

答案：T 是 String，所以 response.data() 的静态类型是 String，不需要强制转换。

### 练习 B：Stream

把下面列表中的成功响应筛选出来，并得到 code 列表：

```java
List<ApiResponse<String>> responses = List.of(
        ApiResponse.success("ok"),
        ApiResponse.failure(CommonErrorCode.INVALID_PARAMETER),
        ApiResponse.success("also ok")
);
```

参考实现：

```java
List<String> successCodes = responses.stream()
        .filter(response -> response.code().equals(CommonErrorCode.SUCCESS.code()))
        .map(ApiResponse::code)
        .toList();
```

解释：

- `stream()`：开始声明式处理。
- `filter`：只保留满足条件的元素。
- `map`：把每个响应转换成 code。
- `toList`：收集成列表。

不要为了一个简单循环强行使用 Stream；能读懂比追求“函数式”更重要。

### 练习 C：Instant

问：为什么 API 统一使用 `Instant`，而不是 `LocalDateTime.now()`？

答案：`Instant` 表示 UTC 时间线上的明确时间点，不携带服务器本地时区歧义；`LocalDateTime` 只有日期和钟表时间，跨服务器或跨时区时可能无法判断具体时刻。

---

## 9. 常见错误

### `invalid source release` 或 record 语法报错

检查：

```powershell
java -version
mvn -version
```

Maven 实际使用的 Java 必须是 21。父 POM 也应有 `<java.version>21</java.version>`。

### `cannot find symbol class ApiResponse`

检查：

- 类文件是否在 `learnhub-common/src/main/java`。
- package 是否为 `com.github.comui520.learnhub.common.api`。
- 测试文件是否导入 `ApiResponse`，或位于同包。
- 是否只执行了 IDEA 的部分构建而没有 Reload Maven。

### 看到 `getCode()` 无法解析

`ApiResponse` 是 record，访问器是 `response.code()`，不是 `response.getCode()`。以后如果使用普通 JavaBean，才可能是 `getCode()`。

### 业务错误码写成字符串散落在 Service

例如到处写 `"USER_NOT_FOUND"` 会有拼写和维护问题。应该集中在 enum，并让 IDE 帮你重构。

---

## 10. 今日验收清单

- [ ] 我能解释 T 为什么存在。
- [ ] 我知道 record 的访问器为什么是 `code()`。
- [ ] `ErrorCode` 是接口，`CommonErrorCode` 是枚举实现。
- [ ] `BusinessException` 保存了错误码，而不是只保存字符串。
- [ ] `ApiResponse.success` 和 `failure` 都能正确推断泛型。
- [ ] common 没有依赖 Spring Web。
- [ ] 至少五个 JUnit 测试通过。
- [ ] 我能解释 `Instant` 比本地时间字符串更适合 API。
- [ ] 我亲手制造并修复过一个 package/import 错误。

---

## 11. 复盘题与参考答案

1. **泛型相比 `Object` 解决什么问题？** 让编译器保留并检查具体 data 类型，减少运行时强制转换错误。
2. **为什么 ErrorCode 用接口？** 让不同业务模块定义自己的错误码，同时遵守统一的 code/message/status 契约。
3. **为什么 CommonErrorCode 用枚举？** 公共错误是有限固定值，枚举集中、不可随意 new、可被 IDE 引用。
4. **为什么错误码和 message 都要有？** code 给机器稳定判断，message 给人阅读且可以变化或国际化。
5. **为什么 BusinessException 继承 RuntimeException？** 让可预期业务失败自然传播到统一边界，避免每层写机械的 checked exception 处理。
6. **为什么 common 不直接依赖 Spring 的 HttpStatus？** 让公共契约保持纯 Java，Web 层再把数字状态转换成 HTTP 类型。
7. **为什么错误响应的 data 可以为空？** 很多错误只需要 code/message，强迫每个错误构造无意义 data 会让协议变复杂。
8. **为什么 timestamp 使用 Instant？** 它是明确的 UTC 时间点，跨时区更可靠。

完成后交给老师：四个公共 Java 文件、两个测试文件、`mvn -pl learnhub-common test` 输出，以及八道题的自己的答案。

