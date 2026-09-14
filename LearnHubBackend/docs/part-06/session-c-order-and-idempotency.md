# Session C：模拟订单 + 支付回调幂等 + 定时关单

> 目标：让额度能“充钱进来”——用户下订单 → 模拟支付 → 回调入账；回调重复调用只入账一次；超时未支付自动关闭。
> 档位：概念 🧑🏫 我带，代码 🤝（建表、下单、回调我给全；关单和控制器你补）。预计 4～5 小时。

## 0. 本 Session 完成时的样子

```text
POST /api/v1/credit/orders               → 创建 CREATED 订单
POST /api/v1/credit/payments/notify      → 模拟支付回调（重复调 N 次也只会入账一次）
订单 PAID → 用户额度 +amount，流水一条（bizNo=orderNo）
超时未支付 → 定时任务自动 CLOSED
```

## 1. Step 1：订单表（V11 迁移）

`learnhub-credit` 模块建 `V11__init_credit_order.sql`：

```sql
CREATE TABLE `credit_order`
(
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_no   VARCHAR(64)     NOT NULL COMMENT '订单号（对外唯一）',
    user_id    BIGINT UNSIGNED NOT NULL COMMENT '用户 ID',
    amount     DECIMAL(12, 2)  NOT NULL COMMENT '金额',
    status     VARCHAR(20)     NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/PAID/CLOSED',
    paid_at    DATETIME        NULL COMMENT '支付时间',
    expire_at  DATETIME        NOT NULL COMMENT '过期时间',
    created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_user_id (user_id),
    KEY idx_status_expire (status, expire_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='额度订单表';
```

注意 `uk_order_no`（订单号唯一）和 `idx_status_expire`（关单扫描用）。

## 2. Step 2：创建订单（简单，给全）

```java
@Transactional
public CreditOrderResponse createOrder(Long userId, BigDecimal amount) {
    CreditOrder order = new CreditOrder();
    order.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
    order.setUserId(userId);
    order.setAmount(amount);
    order.setStatus("CREATED");
    order.setExpireAt(LocalDateTime.now().plusMinutes(15));   // 15 分钟未支付关闭
    orderMapper.insert(order);
    return toResponse(order);
}
```

金额 `BigDecimal`（controller 入参用 `@DecimalMin("0.01")` 校验），订单号服务端生成，**不信任客户端**。

## 3. Step 3：支付回调——本 Session 的核心（幂等）

模拟支付平台回调：`POST /api/v1/credit/payments/notify`，body 带 `orderNo`、`amount`、`tradeNo`。

```java
@Transactional
public void handlePaymentNotify(String orderNo, BigDecimal amount, String tradeNo) {
    CreditOrder order = orderMapper.selectOne(
            new LambdaQueryWrapper<CreditOrder>()
                    .eq(CreditOrder::getOrderNo, orderNo));
    if (order == null) {
        throw new BusinessException(CreditErrorCode.ORDER_NOT_FOUND);
    }
    if (order.getAmount().compareTo(amount) != 0) {
        throw new BusinessException(CreditErrorCode.PAY_AMOUNT_MISMATCH);  // 金额不符拒绝
    }

    // ★ 幂等关键：状态机条件更新，只有 CREATED → PAID 的第一次能成功
    int rows = orderMapper.updateStatusByOrderNo(orderNo, "PAID", "CREATED");
    if (rows == 0) {
        // 已经 PAID 或 CLOSED——重复回调，直接当作成功返回，不入账
        log.info("duplicate notify ignored: orderNo={}", orderNo);
        return;
    }

    orderMapper.markPaid(orderNo, LocalDateTime.now());

    // 只有第一次走到这里：给用户加额度（bizNo = orderNo，流水唯一索引兜底）
    creditService.grant(order.getUserId(), order.getAmount(), order.getOrderNo());
}
```

`CreditOrderMapper` 两个方法：

```java
@Update("UPDATE credit_order SET status = #{to} WHERE order_no = #{orderNo} AND status = #{from}")
int updateStatusByOrderNo(@Param("orderNo") String orderNo,
                          @Param("to") String to,
                          @Param("from") String from);

@Update("UPDATE credit_order SET paid_at = #{paidAt} WHERE order_no = #{orderNo}")
int markPaid(@Param("orderNo") String orderNo, @Param("paidAt") LocalDateTime paidAt);
```

**为什么重复回调只入账一次**——三重保险：

1. **状态机**：`UPDATE ... WHERE status='CREATED'`，第二次回调影响行数为 0，直接 return；
2. **流水唯一索引**：`grant` 里 `bizNo=orderNo`，就算有 bug 漏到第二次，`uk_biz_no` 也会拒绝；
3. **订单号唯一**：`uk_order_no` 保证订单本身只存在一次。

> 面试点：幂等的三层防线从业务到数据库层层兜底，任何一层漏了，下一层接住。

## 4. Step 4：定时关单（🏃 你来做）

需求：每 30 秒扫一次，把 `status='CREATED' AND expire_at < now` 的订单改成 `CLOSED`。

提示：

- 用 `@Scheduled(fixedRate = 30000)`（Spring Boot 自带，主类或配置类加 `@EnableScheduling`）。
- Mapper 加一个方法：`UPDATE credit_order SET status='CLOSED' WHERE status='CREATED' AND expire_at < now()`。
- 只更新，不删订单——历史订单要留着对账。
- 日志打印“关闭了 N 个超时订单”。

> 面试点：定时扫表 vs 消息延迟队列（RabbitMQ 延迟消息 / Redisson DelayedQueue）——数据量小定时扫表够用；量大了用延迟消息，避免频繁扫全表。

## 5. Step 5：Controller（已完成，逐段理解）

Controller 只做三件事：接收 HTTP 参数、拿当前用户 ID、调用 Service 并包装成 `ApiResponse`。订单状态变化和幂等判断全部留在 `CreditService`，不要把业务规则搬到 Controller。

### 5.1 两个请求 DTO

创建订单和支付回调的请求结构不同，所以分别建两个 `record`：

```java
public record CreateCreditOrderRequest(
        @NotNull
        @DecimalMin(value = "0.01")
        BigDecimal amount
) {}

public record PaymentNotifyRequest(
        @NotBlank String orderNo,
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
        @NotBlank String tradeNo
) {}
```

- `@NotNull` 防止 JSON 没有金额时进入 Service。
- `@DecimalMin("0.01")` 防止 0 元或负数订单。
- `@NotBlank` 防止订单号、第三方交易号为空。
- `@Valid` 写在 Controller 的 `@RequestBody` 上，校验失败后交给全局异常处理器返回 `COMMON_0400`。

### 5.2 Controller 的三个接口

当前实现文件：`learnhub-credit/src/main/java/.../credit/controller/CreditController.java`。

```java
@RestController
@RequestMapping("/api/v1/credit")
public class CreditController {

    @GetMapping("/balance")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<BigDecimal> getBalance() {
        return ApiResponse.success(
                creditService.getBalance(currentUser.currentUserId())
        );
    }

    @PostMapping("/orders")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<CreditOrderResponse> createOrder(
            @Valid @RequestBody CreateCreditOrderRequest request
    ) {
        return ApiResponse.success(
                creditService.createOrder(
                        currentUser.currentUserId(), request.amount()
                )
        );
    }

    @PostMapping("/payments/notify")
    public ApiResponse<Void> paymentNotify(
            @Valid @RequestBody PaymentNotifyRequest request
    ) {
        creditService.handlePaymentNotify(
                request.orderNo(), request.amount(), request.tradeNo()
        );
        return ApiResponse.success();
    }
}
```

### 5.3 为什么回调接口不需要 JWT

订单创建是用户主动操作，必须知道“哪个登录用户创建订单”，所以需要 JWT。

支付回调是支付平台调用，支付平台不会携带 LearnHub 用户的 JWT，因此不能把这个接口放在默认的 `authenticated()` 规则下。

`SecurityConfig` 中要放行：

```java
.requestMatchers(
        "/api/v1/auth/**",
        "/api/v1/credit/payments/notify",
        ...
).permitAll()
```

当前只是模拟回调，所以没有真正的支付签名。生产系统不能只依赖“接口公开”：应该校验支付平台签名、商户号、订单号、金额、时间戳和交易号。这个项目把“回调幂等”作为本 Part 的重点，签名验签放到可选升级题。

### 5.4 Swagger 里的状态码

- 创建订单：`200`、`400`、`401`。
- 查询余额：`200`、`401`。
- 支付回调：`200`、`400`、`404`；重复回调也返回 `200`，因为它已经达到最终结果，不应该被客户端当成失败重试。

### 5.5 现有 Service/Mapper/Schedule 检查结论

当前代码可以编译，主流程也符合本 Session 的教学目标，但下面几个点要记到 Part 7 测试和升级清单里：

1. `CreditService.grant` 是“查余额 → Java 加法 → update”，多个赠送请求并发时存在丢更新风险。当前支付回调通过订单状态条件更新挡住了大部分重复场景，但通用的 `grant` 仍建议改成条件更新或加锁。
2. `consume` 的“先查流水再扣减”在同一个 `bizNo` 并发时可能有两个请求同时通过检查，最终依赖 `uk_biz_no` 兜底；更稳的做法是让业务唯一键和事务异常路径有明确测试。
3. `handlePaymentNotify` 当前没有使用 `tradeNo`。模拟支付可以接受；真实支付应该保存交易号并校验回调签名，且交易号也应具备幂等约束。
4. `OrderMapper.markPaid` 最好显式加 `@Param("orderNo")`、`@Param("paidAt")`，不要依赖编译参数保留方法名。返回值也可以从 `void` 改成 `int`，方便判断影响行数。
5. `CreditSchedule` 的 `fixedRate = 30000` 适合当前单体和小数据量。未来多实例部署时，多个实例会同时扫描，但条件更新仍应保证同一订单只从 `CREATED` 变成 `CLOSED` 一次；更严格的方案是分布式调度或延迟队列。

所以：Controller 已经完成；Part 6 还需要做接口验收、重复回调验证和额度并发验证，之后就可以正式进入 Part 7。

## 6. Step 6：验证

### 6.1 正常支付

1. 创建订单 → 状态 CREATED。
2. 调回调 → 订单 PAID，`credit_account.balance` 增加，流水多一条（type=GRANT，bizNo=订单号）。

### 6.2 幂等验证（验收项）

**同一个回调连发 5 遍**：

```powershell
for ($i=0; $i -lt 5; $i++) { curl.exe -X POST http://localhost:8080/api/v1/credit/payments/notify -H "Content-Type: application/json" -d '{"orderNo":"<订单号>","amount":10,"tradeNo":"T001"}' }
```

预期：5 次都返回成功，但 `credit_transaction` 里只有 **1 条**流水，余额只加了一次。

### 6.3 关单验证

把一条订单的 `expire_at` 改成过去（`UPDATE credit_order SET expire_at = NOW() - INTERVAL 1 MINUTE WHERE order_no='...'`），等 30 秒，看状态变 CLOSED，再回调它 → 被状态机挡下，不入账。

## 7. 复盘题

1. 回调幂等靠哪三层？各自挡什么场景？
2. 为什么订单状态用 `UPDATE ... WHERE status='CREATED'` 而不是先查再判断？
3. 定时扫表关单有什么缺点？什么场景该换延迟消息？
4. `bizNo` 在赠送和回调里各是什么？为什么必须唯一？
5. 回调接口为什么不能走 JWT 鉴权？那怎么保证是“真的支付平台”？（提示：签名验签，本项目模拟所以先不做，但要能说出来）

## 8. Part 6 收尾

- [ ] 更新 `LEARNHUB_PLAN.md`：勾选 Part 6，追加决策记录（缓存策略、扣减方案、幂等方案、关单方案）。
- [ ] 能回答 README 里的 7 道答辩题。
- [ ] `mvn compile` 全绿。

完成后进入 **Part 7：测试、性能与部署**——把前面欠的测试补上，压测额度并发，用 Docker 把整套环境部署起来。
