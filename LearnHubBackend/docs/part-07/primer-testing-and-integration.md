# 前置教学：测试、集成测试与可观测性

> 这篇必须先读。Part 7 会第一次把 `@WebMvcTest`、MockMvc、`@SpringBootTest`、Testcontainers、并发测试、Actuator、Micrometer 和 k6 放在同一条工程流程里。
>
> 你不需要一次背下所有 API，但要知道每个工具解决什么问题、应该放在哪一层、失败时先看哪里。

## 1. 测试不是“把代码再运行一遍”

一次真实请求大致经过：

```text
HTTP
  -> Security Filter
  -> Controller 参数绑定/校验
  -> Service 业务规则
  -> Mapper SQL
  -> MySQL / Redis / RabbitMQ / MinIO / Qdrant / AI API
```

如果所有测试都启动整套系统，测试会慢、难定位；如果所有测试都 mock，SQL、事务和配置问题又永远发现不了。因此按风险拆层：

| 类型 | 启动范围 | 主要验证 | 失败时看什么 |
|---|---|---|---|
| 单元测试 | 普通 Java 对象 | Service 分支、异常、状态机、金额 | 被测方法和 mock 配置 |
| MVC 切片 | Spring MVC + 一个 Controller | JSON、校验、状态码、Security 入口 | 请求路径、body、Bean |
| 集成测试 | 完整 Spring 容器 + 真实依赖 | MyBatis XML、事务、配置、数据库约束 | 启动日志、SQL、容器 |
| 端到端测试 | 真实应用对外端口 | 用户流程 | 全链路日志和外部服务 |

本项目的优先级是：

1. 会造成钱或权限问题的规则，先写单元测试。
2. Controller 的 HTTP 契约，用 `@WebMvcTest`。
3. MyBatis XML、Flyway、事务、唯一索引，至少写一个集成测试。
4. 真正的 AI、MinIO、Qdrant 调用，不放进快速单元测试；用 fake 或 mock。

## 2. JUnit 5：一个测试由三段组成

```java
@Test
void shouldRejectPaymentWhenAmountDoesNotMatch() {
    // arrange：准备输入和依赖行为
    // act：调用被测方法
    // assert：断言返回值或异常
}
```

### 2.1 生命周期

```java
@BeforeEach
void setUp() {
    // 每个测试前重新创建，避免上一个测试修改了共享状态
}

@AfterEach
void tearDown() {
    // 关闭手动创建的资源；普通 mock 通常不需要处理
}
```

为什么现有 `LoginAttemptServiceTest` 里如果忘了初始化 `service` 会得到 `NullPointerException`？因为 JUnit 只负责调用测试方法，不会自动猜构造器。要么字段直接初始化：

```java
private final LoginAttemptService service = new LoginAttemptService();
```

要么在 `@BeforeEach` 里初始化：

```java
@BeforeEach
void setUp() {
    service = new LoginAttemptService(3, Duration.ofMillis(20));
}
```

第二种适合每个测试使用不同配置。

### 2.2 AssertJ 常用断言

```java
assertThat(result).isTrue();
assertThat(result).isEqualTo("CREATED");
assertThat(balance).isEqualByComparingTo("10.00");
assertThatThrownBy(() -> service.doSomething())
        .isInstanceOf(BusinessException.class)
        .hasMessage("...");
```

`BigDecimal.equals` 会比较 scale，`10.0` 和 `10.00` 可能不相等；金额使用 `isEqualByComparingTo`。

## 3. Mockito：只替换依赖

当前项目已有这种写法：

```java
@ExtendWith(MockitoExtension.class)
class CreditServiceTest {
    @Mock
    private CreditAccountMapper accountMapper;

    @Mock
    private CreditTransactionMapper transactionMapper;

    @Mock
    private OrderMapper orderMapper;

    @InjectMocks
    private CreditService creditService;
}
```

含义：

- `@Mock`：生成一个假的 Mapper，不连接 MySQL。
- `@InjectMocks`：创建真实的 `CreditService`，把上面的 mock 注入进去。
- `given(...).willReturn(...)`：定义依赖收到某个调用时返回什么。
- `verify(...)`：检查某个依赖有没有被调用。

示例：

```java
given(transactionMapper.selectCount(any())).willReturn(0L);
given(accountMapper.deductBalance(1L, BigDecimal.ONE)).willReturn(0);

boolean result = creditService.consume(1L, BigDecimal.ONE, "chat:test");

assertThat(result).isFalse();
verify(accountMapper).deductBalance(1L, BigDecimal.ONE);
```

不要 mock 被测对象自己的业务逻辑；否则测试会变成“我告诉 mock 返回什么，然后断言它返回什么”。

## 4. `@WebMvcTest` 与 MockMvc

`@WebMvcTest(CreditController.class)` 只加载 MVC 相关 Bean 和指定 Controller，不会把整个应用的 Service、Mapper、Redis、RabbitMQ 都启动起来。因此 Controller 依赖要用 `@MockitoBean` 放进 Spring 容器：

```java
@WebMvcTest(CreditController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class CreditControllerTest {
    @Autowired
    MockMvc mvc;

    @MockitoBean
    CreditService creditService;

    @MockitoBean
    CurrentUser currentUser;
}
```

### 4.1 发送 JSON

```java
given(currentUser.currentUserId()).willReturn(1L);
given(creditService.createOrder(eq(1L), eq(new BigDecimal("10.00"))))
        .willReturn(response);

mvc.perform(post("/api/v1/credit/orders")
        .with(user("1"))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"amount\":10.00}"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.data.status").value("CREATED"));
```

### 4.2 参数校验

```java
mvc.perform(post("/api/v1/credit/orders")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"amount\":0}"))
    .andExpect(status().isBadRequest())
    .andExpect(jsonPath("$.code").value("COMMON_0400"));
```

### 4.3 要不要关闭过滤器

- 测 Controller 本身的 200/400：可以 `@AutoConfigureMockMvc(addFilters = false)`，让 Security 不干扰。
- 测 401/403：不要关闭过滤器，导入 `SecurityConfig`、JWT filter 和相关依赖，或者使用 `@WithMockUser`/`user(...)` 构造认证身份。

不要在一个测试类里混淆两种目标；可以拆成“Controller 契约测试”和“Security 集成测试”。

## 5. `@SpringBootTest`：什么时候必须用

```java
@SpringBootTest
class CreditRepositoryIntegrationTest {
    @Autowired
    CreditAccountMapper accountMapper;
}
```

它会尝试加载完整应用上下文，所以能发现：

- Mapper XML namespace 或方法名写错。
- Flyway migration 没被扫描。
- Bean 循环依赖。
- `@ConfigurationProperties` 没绑定。
- 事务和数据库约束问题。

代价是启动慢、依赖多。不要用它测试一个简单的 `if` 分支。

## 6. Testcontainers：用真实 MySQL 测 SQL

### 6.1 为什么不用 H2

H2 和 MySQL 的 SQL 方言、类型、索引和约束行为可能不同。项目使用 MySQL 8.4，集成测试最好也用 MySQL 8.x。

### 6.2 依赖

在需要集成测试的模块 `pom.xml` 中加入测试作用域依赖：

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>mysql</artifactId>
    <scope>test</scope>
</dependency>
```

第一次执行前确认 Docker Desktop 已启动：

```powershell
docker version
docker ps
```

### 6.3 最小容器测试

```java
@Testcontainers
@SpringBootTest
class CreditRepositoryIntegrationTest {
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("learnhub_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }
}
```

`@Container static` 表示整个测试类共享一个容器；如果不写 `static`，每个测试方法都可能启动一个容器，速度会很慢。

### 6.4 这个测试能发现什么

它可以真实验证 `CreditAccountMapper.deductBalance` 的 SQL：余额足够时影响行数为 1，余额不足时影响行数为 0；也能验证 `uk_biz_no`、`uk_user_id` 这类数据库约束。

## 7. 并发测试的工具

| 工具 | 作用 |
|---|---|
| `ExecutorService` | 管理并发线程 |
| `CountDownLatch` | 让多个线程同时起跑 |
| `Future` | 获取异步任务结果 |
| `AtomicInteger` | 线程安全计数 |

不要用 `Thread.sleep` 让并发“看起来同时发生”；它不能保证时序。正确思路是：所有线程先等待闸门，主线程统一放行。

## 8. Actuator 和指标

Actuator 是 Spring Boot 的运维入口。当前项目已经有 Actuator 依赖，常用端点是：

```text
/actuator/health       应用和依赖健康状态
/actuator/info         应用基本信息
/actuator/metrics      Micrometer 指标
/actuator/prometheus   Prometheus 文本格式
```

开发配置示例：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
```

生产环境不要无脑暴露所有端点。健康检查可以公开，配置、环境和线程信息要谨慎。

Micrometer 是统一指标 API。例如：

```java
Counter counter = meterRegistry.counter(
        "learnhub.credit.consume", "result", "success");
counter.increment();
```

`result=success/failure` 是有限标签；不要把 `userId`、订单号放进 tag，否则每个用户都会产生一条新的时间序列。

## 9. 性能指标怎么看

只看平均耗时会掩盖慢请求。至少记录：

- 吞吐量：每秒完成多少请求。
- 错误率：失败请求 / 总请求。
- p50：一半请求比它快。
- p95：95% 请求比它快，剩余 5% 更慢。
- p99：最慢的 1% 请求边界。

排查顺序：先确认慢，再定位数据库/Redis/外部 API/线程池，最后决定加索引、缓存或异步化。

## 10. k6 只负责发请求，不负责证明业务正确

k6 是压测工具，适合观察吞吐、延迟和错误率。并发额度的“最多成功一次”仍要用数据库结果断言，不能只看 k6 返回码。

先压健康接口，再压普通数据库接口，最后小规模压 Chat。真实 AI 会产生费用和外部限流，不要一上来开几十个虚拟用户。

## 11. 测试失败排查顺序

1. 看第一个失败，不要先看最后的连锁异常。
2. 判断请求有没有到 Controller。
3. 看 DTO 校验和 JSON 是否正确。
4. 看 Service mock 是否配置了正确参数。
5. 看 SQL 日志和影响行数。
6. 再检查事务、容器和外部服务。

这套顺序比“先改配置、先加 sleep”可靠。