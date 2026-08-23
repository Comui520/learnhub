# Session B：限流 + 额度表 + 原子扣减

> 目标：给 AI 问答加两道闸——① 限流（1 秒最多 N 次）② 扣额度（每次提问扣 1，并发下不超扣）。这是 Part 6 的**硬核部分**。
> 档位：概念 🧑🏫 我带，代码 🤝（限流、建表、查询赠送我给全；扣减核心我给你骨架和完整 Lua，你拼进 Service）。预计 4～5 小时。

## 0. 本 Session 完成时的样子

```text
提问 → 限流检查（超了 429）→ 额度扣减（并发安全）→ RAG 回答
```

## 1. Step 1：限流（Redisson RRateLimiter）

给 `ChatController` 加限流。Redisson 的限流器本质是“令牌桶”：每秒补充 N 个令牌，取不到就拒绝。

```java
private final RedissonClient redisson;

@PostMapping(value = "/{id}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> chat(
        @PathVariable Long id,
        @Valid @RequestBody ChatRequest request
) {
    Long userId = currentUser.currentUserId();

    // 限流：每用户每秒最多 5 次
    RRateLimiter limiter = redisson.getRateLimiter("rate:chat:" + userId);
    limiter.trySetRate(RateType.PER_CLIENT, 5, 1, RateIntervalUnit.SECONDS);
    if (!limiter.tryAcquire()) {
        throw new BusinessException(CreditErrorCode.RATE_LIMITED);
    }

    return ragChatService.streamChat(userId, id, request.question());
}
```

理解：

- `getRateLimiter("rate:chat:" + userId)`：每个用户一把独立的“令牌桶”，key 带上 userId 就是维度。
- `trySetRate(PER_CLIENT, 5, 1, SECONDS)`：每秒补 5 个令牌。重复调用不会重置（幂等）。
- `tryAcquire()`：拿到令牌返回 true，否则 false → 抛一个 429 业务错误（错误码自己加，`RATE_LIMITED`，httpStatus 429）。
- 限流失败**绝不能继续**——这是并发防护的第一道闸。

> 面试点：为什么限流器 key 要带 userId？——不带就变成“全站共享 5 次/秒”，一个用户就能打满。

## 2. Step 2：额度表（V10 迁移）

`learnhub-credit` 模块建 `resources/db/migration/V10__init_credit_tables.sql`：

```sql
-- 额度账户：一个用户一行
CREATE TABLE `credit_account`
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id     BIGINT UNSIGNED NOT NULL COMMENT '用户 ID',
    balance     DECIMAL(12, 2)  NOT NULL DEFAULT 0.00 COMMENT '余额',
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_id (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='额度账户表';

-- 额度流水：只增不改，可还原历史
CREATE TABLE `credit_transaction`
(
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id       BIGINT UNSIGNED NOT NULL COMMENT '用户 ID',
    change_amount DECIMAL(12, 2)  NOT NULL COMMENT '变动金额（正=增加，负=扣减）',
    balance_after DECIMAL(12, 2)  NOT NULL COMMENT '变动后余额',
    type          VARCHAR(20)     NOT NULL COMMENT 'GRANT/CONSUME/REFUND',
    biz_no        VARCHAR(64)     NOT NULL COMMENT '业务单号（防重）',
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_no (biz_no),
    KEY idx_user_id (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='额度流水表';
```

三个设计点（面试要能讲）：

- **`balance DECIMAL`**：金额，不是 double（primer 第 9 节）。
- **流水 `balance_after`**：每次变动记录“变动后余额”，能还原历史、对账。
- **`biz_no` 唯一索引**：同一个业务单号只能有一条流水——防重复入账的数据库兜底（Session C 用）。

实体照旧套路（`@TableName` + `@Data` + `@TableId(AUTO)`），不重复贴了。

## 3. Step 3：额度查询 / 赠送（🧑🏫 给全，套路你熟）

`CreditService`（直接 `@Service` class，风格统一）：

```java
@Slf4j
@Service
public class CreditService {

    private final CreditAccountMapper accountMapper;
    private final CreditTransactionMapper transactionMapper;
    // 构造器注入

    /** 查询余额：没有账户按 0 处理 */
    public BigDecimal getBalance(Long userId) {
        CreditAccount account = accountMapper.selectOne(
                new LambdaQueryWrapper<CreditAccount>()
                        .eq(CreditAccount::getUserId, userId));
        return account == null ? BigDecimal.ZERO : account.getBalance();
    }

    /** 赠送额度（注册送 10 之类） */
    @Transactional
    public void grant(Long userId, BigDecimal amount, String bizNo) {
        // 幂等：同一个 bizNo 只送一次（uk_biz_no 兜底）
        if (transactionMapper.selectCount(
                new LambdaQueryWrapper<CreditTransaction>()
                        .eq(CreditTransaction::getBizNo, bizNo)) > 0) {
            return;
        }
        CreditAccount account = ensureAccount(userId);
        account.setBalance(account.getBalance().add(amount));
        accountMapper.updateById(account);
        recordTransaction(userId, amount, account.getBalance(), "GRANT", bizNo);
    }

    private CreditAccount ensureAccount(Long userId) {
        CreditAccount account = accountMapper.selectOne(
                new LambdaQueryWrapper<CreditAccount>()
                        .eq(CreditAccount::getUserId, userId));
        if (account == null) {
            account = new CreditAccount();
            account.setUserId(userId);
            account.setBalance(BigDecimal.ZERO);
            accountMapper.insert(account);
        }
        return account;
    }
}
```

`recordTransaction` 就是插一行流水（含 `balance_after`），自己补。

## 4. Step 4：并发扣减——本 Session 的核心

### 4.1 错误写法（先查再减，必超扣）

```java
BigDecimal balance = getBalance(userId);        // ① 查
if (balance.compareTo(amount) >= 0) {            // ② 判断
    account.setBalance(balance.subtract(amount)); // ③ 减
    accountMapper.updateById(account);             // ④ 写
}
```

两个请求并发时都读到 balance=1，都通过判断，都写 0——**扣了两次**。primer 6.1 的竞态现场。

### 4.2 正确写法 A：MySQL 原子 UPDATE（线上主方案）

把“判断余额够不够 + 扣减”合并成**一条 SQL**，数据库行锁保证原子：

```java
@Transactional
public boolean consume(Long userId, BigDecimal amount, String bizNo) {
    // 幂等：同 bizNo 已扣过 → 直接返回成功（回调/重试场景）
    if (transactionMapper.selectCount(
            new LambdaQueryWrapper<CreditTransaction>()
                    .eq(CreditTransaction::getBizNo, bizNo)) > 0) {
        return true;
    }

    // 原子扣减：余额够才减，返回影响行数
    int rows = accountMapper.deductBalance(userId, amount);
    if (rows == 0) {
        return false;   // 余额不足
    }

    CreditAccount account = accountMapper.selectOne(
            new LambdaQueryWrapper<CreditAccount>()
                    .eq(CreditAccount::getUserId, userId));
    recordTransaction(userId, amount.negate(), account.getBalance(), "CONSUME", bizNo);
    return true;
}
```

`CreditAccountMapper` 加一个方法（XML 或注解都行）：

```java
@Update("UPDATE credit_account SET balance = balance - #{amount} " +
        "WHERE user_id = #{userId} AND balance >= #{amount}")
int deductBalance(@Param("userId") Long userId, @Param("amount") BigDecimal amount);
```

为什么这就防超扣了：`UPDATE ... WHERE balance >= ?` 在**行锁内判断 + 修改**，第二个并发事务必须等第一个提交，看到的是扣完的余额 → 条件不满足 → 影响行数 0 → 返回 false。**“先查再减”的两步竞态被数据库锁消除。**

> 面试点：条件 UPDATE 为什么原子？——UPDATE 会锁行，判断和修改在锁内完成。

### 4.3 正确写法 B：Redis Lua（缓存余额场景）

如果余额存在 Redis（比如秒杀），就用 Lua 把三步变原子。`StringRedisTemplate.execute` 执行脚本：

```java
private static final String DEDUCT_LUA =
        "local b = tonumber(redis.call('GET', KEYS[1]) or '0') " +
        "if b >= tonumber(ARGV[1]) then " +
        "  redis.call('DECRBY', KEYS[1], ARGV[1]) " +
        "  return 1 " +
        "else " +
        "  return 0 " +
        "end";

public boolean deductFromCache(Long userId, BigDecimal amount) {
    DefaultRedisScript<Long> script = new DefaultRedisScript<>(DEDUCT_LUA, Long.class);
    Long result = stringRedisTemplate.execute(
            script,
            List.of("credit:cache:" + userId),   // KEYS[1]
            amount.toPlainString()                // ARGV[1]
    );
    return Long.valueOf(1).equals(result);
}
```

理解：

- `KEYS[1]` 是余额 key，`ARGV[1]` 是扣减量；
- 脚本在 Redis 单线程里原子执行，两个并发请求**排队**，第二个看到的是扣完的值 → 返回 0；
- 本项目余额真相在 MySQL，这个 Lua 方法是“如果以后余额放 Redis”的标准答案，**面试用它讲原理**，线上扣减用 4.2。

## 5. Step 5：接到提问流程

`RagChatService.streamChat` 开头（限流之后）加扣减：

```java
if (!creditService.consume(userId, BigDecimal.ONE, "chat:" + UUID.randomUUID())) {
    throw new BusinessException(CreditErrorCode.INSUFFICIENT_CREDIT);
}
```

每次提问扣 1，`bizNo` 用 `chat:{uuid}` 保证不重复扣。扣不到抛“额度不足”业务错误（自己加，比如 402 或 409）。

## 6. Step 6：验证

### 6.1 基本流程

1. 给用户送额度：调 grant（或注册时送）。
2. 提问一次 → 查流水 `SELECT * FROM credit_transaction ORDER BY id DESC`，多了一行 `CONSUME`。
3. 余额查出来少了 1。

### 6.2 并发验证（验收项：并发 10 次最多成功 1 次）

把余额调到 1，然后**同时**发 10 个提问。用 IDEA 的 HTTP 并发工具，或者一个简单脚本（10 个 curl 同时发）。观察：成功的回答只有 1 个，其余返回“额度不足”，流水里只有 1 条 CONSUME。

> 如果并发下出现了 2 条成功，说明你的扣减不是原子的——回去检查 4.2 的条件 UPDATE 或事务配置。

## 7. 复盘题

1. “先查再减”为什么并发会超扣？条件 UPDATE 为什么能防？
2. Lua 脚本为什么原子？`KEYS[1]` / `ARGV[1]` 分别是什么？
3. 流水表为什么必须有 `biz_no` 唯一索引？
4. 限流和扣额度是两件事吗？分别防什么？
5. 余额存在 MySQL 和 Redis 各有什么取舍？（提示：真相 vs 速度，MySQL 原子 UPDATE vs Lua）

完成后进入 [Session C](session-c-order-and-idempotency.md)：模拟订单、支付回调幂等、定时关单。
