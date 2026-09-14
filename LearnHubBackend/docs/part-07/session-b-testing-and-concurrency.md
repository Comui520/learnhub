# Session B：从单元测试到并发验收

> 目标：用测试证明项目最重要的承诺：不能越权、不能重复入账、余额不足不能扣、并发扣减不能超扣。
>
> 这一节不追求测试数量，追求测试能抓住真实 bug。每完成一组测试，都先单独运行它，再继续下一组。

## 0. 依赖和测试目录

### 0.1 现有依赖

`learnhub-user`、`learnhub-credit` 和 `learnhub-application` 已经有：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
```

它提供 JUnit 5、Mockito、AssertJ、Spring Test、MockMvc 的基础能力。

### 0.2 本节新增依赖

为了测试 Security 和 Testcontainers，在对应模块 POM 中增加：

```xml
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-test</artifactId>
    <scope>test</scope>
</dependency>

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

`spring-security-test` 提供 `.with(user("..."))`、`@WithMockUser` 等测试工具；它只用于测试，不会进入生产运行时。

### 0.3 文件放置

```text
learnhub-credit/src/test/java/
└── com/github/comui520/learnhub/credit/
    ├── service/CreditServiceTest.java
    ├── controller/CreditControllerTest.java
    └── integration/CreditRepositoryIntegrationTest.java

learnhub-application/src/test/java/
└── com/github/comui520/learnhub/
    └── security/SecurityIntegrationTest.java
```

Service 单测放在 credit；完整应用上下文测试放 application。不要把所有测试塞进一个类。

## 1. 第一组：测试 `CreditService.getBalance`

新建：

```text
learnhub-credit/src/test/java/com/github/comui520/learnhub/credit/service/CreditServiceTest.java
```

先写测试骨架：

```java
@ExtendWith(MockitoExtension.class)
class CreditServiceTest {
    @Mock
    CreditAccountMapper accountMapper;

    @Mock
    CreditTransactionMapper transactionMapper;

    @Mock
    OrderMapper orderMapper;

    @InjectMocks
    CreditService creditService;

    @Test
    void shouldReturnZeroWhenAccountDoesNotExist() {
        given(accountMapper.selectOne(any())).willReturn(null);

        BigDecimal result = creditService.getBalance(1L);

        assertThat(result).isEqualByComparingTo("0.00");
    }
}
```

运行：

```powershell
mvn -pl learnhub-credit -Dtest=CreditServiceTest#shouldReturnZeroWhenAccountDoesNotExist test
```

如果编译报 `CreditService` 的构造器参数无法注入，先检查 Service 当前构造器：它需要 `CreditAccountMapper`、`OrderMapper`、`CreditTransactionMapper` 三个参数。

## 2. 第二组：测试余额不足和幂等扣减

### 2.1 余额不足

`deductBalance` 返回影响行数 0，代表 SQL 的 `balance >= amount` 条件不满足：

```java
@Test
void shouldReturnFalseWhenBalanceIsInsufficient() {
    given(transactionMapper.selectCount(any())).willReturn(0L);
    given(accountMapper.deductBalance(1L, BigDecimal.ONE)).willReturn(0);

    boolean result = creditService.consume(1L, BigDecimal.ONE, "chat:test-1");

    assertThat(result).isFalse();
    verify(accountMapper).deductBalance(1L, BigDecimal.ONE);
    verify(accountMapper, never()).selectOne(any());
    verify(transactionMapper, never()).insert(any());
}
```

这个测试证明 Service 没有在余额不足时伪造流水。

### 2.2 相同 `bizNo` 重试

```java
@Test
void shouldTreatRepeatedBizNoAsSuccessfulWithoutDeductingAgain() {
    given(transactionMapper.selectCount(any())).willReturn(1L);

    boolean result = creditService.consume(
            1L, BigDecimal.ONE, "chat:already-consumed");

    assertThat(result).isTrue();
    verify(accountMapper, never()).deductBalance(anyLong(), any());
}
```

为什么“重复消费返回 true”？调用方可能因为网络超时重试；如果第一次已经成功，第二次应该返回“最终结果已达到”，而不是再扣一次或把它当成失败。

## 3. 第三组：测试成功扣减和流水

准备 Mapper 返回值：

```java
@Test
void shouldDeductBalanceAndRecordNegativeTransaction() {
    CreditAccount account = new CreditAccount();
    account.setUserId(1L);
    account.setBalance(new BigDecimal("9.00"));

    given(transactionMapper.selectCount(any())).willReturn(0L);
    given(accountMapper.deductBalance(1L, BigDecimal.ONE)).willReturn(1);
    given(accountMapper.selectOne(any())).willReturn(account);

    boolean result = creditService.consume(1L, BigDecimal.ONE, "chat:test-2");

    assertThat(result).isTrue();
    ArgumentCaptor<CreditTransaction> captor =
            ArgumentCaptor.forClass(CreditTransaction.class);
    verify(transactionMapper).insert(captor.capture());

    CreditTransaction transaction = captor.getValue();
    assertThat(transaction.getUserId()).isEqualTo(1L);
    assertThat(transaction.getChangeAmount()).isEqualByComparingTo("-1.00");
    assertThat(transaction.getBalanceAfter()).isEqualByComparingTo("9.00");
    assertThat(transaction.getType()).isEqualTo("CONSUME");
    assertThat(transaction.getBizNo()).isEqualTo("chat:test-2");
}
```

这里用 `ArgumentCaptor` 是为了检查传给 Mapper 的真实对象，而不是只检查“insert 被调用过”。

## 4. 第四组：测试订单回调幂等

### 4.1 金额错误

```java
@Test
void shouldRejectPaymentWhenAmountDoesNotMatch() {
    CreditOrder order = new CreditOrder();
    order.setOrderNo("ORDER-1");
    order.setUserId(1L);
    order.setAmount(new BigDecimal("10.00"));

    given(orderMapper.selectOne(any())).willReturn(order);

    assertThatThrownBy(() -> creditService.handlePaymentNotify(
            "ORDER-1", new BigDecimal("9.00"), "TRADE-1"))
            .isInstanceOf(BusinessException.class)
            .hasMessage(CreditErrorCode.PAY_AMOUNT_MISMATCH.message());

    verify(orderMapper, never()).updateStatusByOrderNo(any(), any(), any());
}
```

### 4.2 第一次回调入账，第二次不入账

第一次调用需要模拟：

```java
given(orderMapper.selectOne(any())).willReturn(order);
given(orderMapper.updateStatusByOrderNo("ORDER-1", "PAID", "CREATED"))
        .willReturn(1);
```

然后验证：

```java
creditService.handlePaymentNotify(
        "ORDER-1", new BigDecimal("10.00"), "TRADE-1");

verify(orderMapper).markPaid(eq("ORDER-1"), any(LocalDateTime.class));
verify(transactionMapper).insert(any(CreditTransaction.class));
```

第二次回调把 `updateStatusByOrderNo` 返回 0：

```java
given(orderMapper.updateStatusByOrderNo("ORDER-1", "PAID", "CREATED"))
        .willReturn(0);

creditService.handlePaymentNotify(
        "ORDER-1", new BigDecimal("10.00"), "TRADE-1");

verify(orderMapper, times(1))
        .markPaid(eq("ORDER-1"), any(LocalDateTime.class));
```

注意：当前 `CreditService.handlePaymentNotify` 内部调用自己的 `grant`，因此不能用 `verify(creditService)` 验证；要验证 `transactionMapper.insert` 的次数，或者验证真实数据库集成测试中的余额和流水。

## 5. 第五组：Controller 的 MockMvc 测试

新建：

```text
learnhub-credit/src/test/java/com/github/comui520/learnhub/credit/controller/CreditControllerTest.java
```

### 5.1 测试策略

先测试 Controller 契约，不让 Redis、MySQL 影响结果：

```java
@WebMvcTest(CreditController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class CreditControllerTest {
    @Autowired
    MockMvc mvc;

    @MockitoBean
    CreditService creditService;

    @MockitoBean
    CurrentUser currentUser;
}
```

`addFilters=false` 的含义是暂时不测试 Security Filter，只测试 Controller 的 JSON 和校验。401/403 单独写 Security 测试。

### 5.2 创建订单 200

```java
@Test
void shouldCreateOrder() throws Exception {
    given(currentUser.currentUserId()).willReturn(1L);
    given(creditService.createOrder(eq(1L), eq(new BigDecimal("10.00"))))
            .willReturn(new CreditOrderResponse(
                    "ORDER-1", 1L, new BigDecimal("10.00"),
                    "CREATED", null, LocalDateTime.now(), LocalDateTime.now()));

    mvc.perform(post("/api/v1/credit/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\":10.00}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("COMMON_0000"))
        .andExpect(jsonPath("$.data.orderNo").value("ORDER-1"))
        .andExpect(jsonPath("$.data.status").value("CREATED"));
}
```

### 5.3 金额非法 400

```java
@Test
void shouldRejectNonPositiveAmount() throws Exception {
    mvc.perform(post("/api/v1/credit/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\":0}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("COMMON_0400"));

    verifyNoInteractions(creditService);
}
```

`verifyNoInteractions` 能证明参数校验失败后没有进入业务层。

### 5.4 回调不需要 JWT

Controller 切片测试关闭过滤器后无法证明放行规则。这个场景要在完整 Security 测试中验证，见第 7 节。

## 6. 第六组：权限和所有权测试

### 6.1 普通用户访问管理员接口

沿用 Part 2 的 `UserControllerTest`：保留过滤器，mock `JwtTokenTool` 解析出用户 ID，但给出的 authorities 不包含目标权限。预期：

```text
HTTP 403
code = COMMON_0403
```

如果实际返回 500，优先检查 `RestAccessDeniedHandler` 是否是一个真正的 Bean，以及 `SecurityConfig.exceptionHandling` 是否配置了它。

### 6.2 用户 B 访问用户 A 的知识库

不要在 Controller 里 mock 成“查到对象”；让 `KnowledgeBaseService` 的所有权查询返回 null 或抛 `KNOWLEDGE_BASE_NOT_FOUND`，然后断言 404。这样能证明越权不是被 Controller 偶然挡住，而是业务层真正校验了 `userId`。

## 7. 第七组：Security 集成测试

新建：

```text
learnhub-application/src/test/java/com/github/comui520/learnhub/security/SecurityIntegrationTest.java
```

这个测试至少验证三件事：

1. 没有 JWT 访问 `/api/v1/credit/balance` 返回 401。
2. `/api/v1/credit/payments/notify` 没有 JWT 也能通过 Security，后续是否 400/404 由 Controller/Service 决定。
3. Swagger 和 health 仍然 permitAll。

如果只想测试 Security 规则，不要真的连 MySQL，可以使用 MockMvc + mock Service；如果使用完整上下文，就必须准备测试数据库。

## 8. 第八组：Testcontainers MySQL 集成测试

### 8.1 文件

```text
learnhub-credit/src/test/java/com/github/comui520/learnhub/credit/integration/CreditRepositoryIntegrationTest.java
```

### 8.2 容器和动态配置

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
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }
}
```

### 8.3 测试条件 UPDATE

准备一条余额为 1 的账户，然后：

```java
int first = accountMapper.deductBalance(1L, BigDecimal.ONE);
int second = accountMapper.deductBalance(1L, BigDecimal.ONE);

assertThat(first).isEqualTo(1);
assertThat(second).isEqualTo(0);
```

如果第二次仍然是 1，检查：

- XML 的 WHERE 是否写了 `balance >= #{amount}`。
- 测试是否真的连接到容器，而不是开发数据库。
- 测试是否在同一个事务里造成了未提交数据。

## 9. 第九组：并发扣减验收

目标：余额为 1，并发 10 次扣 1，成功数最多 1。

```java
@Test
void shouldAllowAtMostOneConcurrentConsumption() throws Exception {
    int taskCount = 10;
    ExecutorService pool = Executors.newFixedThreadPool(taskCount);
    CountDownLatch ready = new CountDownLatch(taskCount);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Boolean>> futures = new ArrayList<>();

    for (int i = 0; i < taskCount; i++) {
        int index = i;
        futures.add(pool.submit(() -> {
            ready.countDown();
            start.await();
            return creditService.consume(
                    1L, BigDecimal.ONE, "concurrent-" + index);
        }));
    }

    assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
    start.countDown();

    long successCount = 0;
    for (Future<Boolean> future : futures) {
        if (future.get(10, TimeUnit.SECONDS)) {
            successCount++;
        }
    }
    pool.shutdownNow();

    assertThat(successCount).isLessThanOrEqualTo(1);
}
```

这个测试最好放在真实 MySQL 集成测试中。Mockito 只能验证你有没有调用 `deductBalance`，不能证明数据库行锁在真实并发下有效。

如果测试偶发失败：

1. 先检查 `CreditService` 是否每个任务使用不同 `bizNo`。
2. 查数据库最终余额和流水数量。
3. 检查测试事务是否让所有线程共享了同一个事务上下文。
4. 不要先加 `Thread.sleep`，那只是隐藏竞态。

## 10. 第十组：重复支付回调集成验证

创建一个 CREATED 订单，使用 5 个线程同时调用同一个回调：

```text
order_no = ORDER-1
amount   = 10.00
trade_no = TRADE-1
```

验收 SQL：

```sql
SELECT status FROM credit_order WHERE order_no = 'ORDER-1';
SELECT COUNT(*) FROM credit_transaction WHERE biz_no = 'ORDER-1';
SELECT balance FROM credit_account WHERE user_id = 1;
```

预期：

```text
status = PAID
流水数量 = 1
余额只增加 10.00
```

如果出现重复流水，重点检查状态条件 UPDATE 和 `uk_biz_no`；如果回调返回 500，检查唯一键异常是否被当成未处理异常，而不是业务上正常的重复回调。

## 11. 运行顺序

```powershell
mvn -pl learnhub-credit -Dtest=CreditServiceTest test
mvn -pl learnhub-credit -Dtest=CreditControllerTest test
mvn -pl learnhub-credit test
mvn test
mvn verify
```

先跑单个测试，失败定位清楚后再跑全量。不要一上来只执行 `mvn test`，否则上下文启动失败会淹没真正的断言错误。

## 12. 完成标准

- [ ] CreditService 单测覆盖余额不足、成功扣减、重复 bizNo、金额不匹配。
- [ ] CreditController 覆盖 200 和 400。
- [ ] Security 覆盖 401、403、公开回调。
- [ ] Testcontainers 能启动 MySQL 并执行真实 Mapper SQL。
- [ ] 并发扣减成功数最多 1。
- [ ] 重复支付回调只产生一条流水。