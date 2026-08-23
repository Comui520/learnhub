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

## 5. Step 5：控制器（🏃 你来做）

两个接口，Swagger 标注 200/400/401/404/409：

- `POST /api/v1/credit/orders`：body `(BigDecimal amount)` → 创建订单。
- `POST /api/v1/credit/payments/notify`：body `(String orderNo, BigDecimal amount, String tradeNo)` → 处理回调。

回调接口**不要带 `@SecurityRequirement`**（支付平台没有你的 JWT），其他接口照常鉴权。数据隔离：查订单/额度按 userId（老规矩）。

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
