# 前置教学：Redis 与并发一致性

> 阅读对象：第一次接触 Redis / 高并发的你。
> 目标：搞懂 Redis 是干嘛的、为什么快、以及本项目里它怎么解决“缓存、限流、原子扣减、幂等”四件事。
> 建议：这篇偏概念，Session 里遇到代码再对照着回来看。

## 1. Redis 是什么：一个“住在内存里的数据库”

Redis 是一个 KV（键值）数据库，数据**放在内存**里，读写都是微秒级——比 MySQL（磁盘 + SQL 解析）快几个数量级。

| | MySQL | Redis |
|---|---|---|
| 数据在哪 | 磁盘 | 内存 |
| 速度 | 毫秒级 | 微秒级 |
| 职责 | 持久真相 | 缓存 / 瞬时状态 / 计数 |
| 丢了会怎样 | 灾难 | 可从 MySQL 回源重建 |

**铁律：MySQL 是真相，Redis 只是加速。** 这句话贯穿整个 Part 6。

## 2. 数据结构（只学用到的）

Redis 的 key 都是字符串，value 有几种类型：

| 类型 | 长什么样 | 本项目用在哪 |
|---|---|---|
| String | `set user:1:name "zhang"` | 缓存 JSON、计数器 |
| ZSet（有序集合） | `zadd key score member` | 滑动窗口限流（时间戳当 score） |
| Hash / List / Set | 字段/列表/集合 | 本 Part 用不到，知道存在即可 |

**TTL（过期时间）**：`set key value EX 60` 表示 60 秒后自动删除。缓存和限流都靠它。

常用命令（Session A 会变成 Java 代码）：

```text
SET key value            # 写
GET key                  # 读
SET key value EX 60      # 写 + 60 秒过期
DEL key                  # 删
INCR key                 # 自增（原子！）
EXPIRE key 60            # 给已有 key 设过期
```

## 3. Cache Aside：最经典的缓存模式

目标：读多写少的接口（比如知识库详情），用 Redis 挡住大部分请求，减少 MySQL 压力。

**读**：

```text
① 查 Redis → 命中？直接返回
② 没命中 → 查 MySQL → 回填 Redis（带 TTL）→ 返回
```

**写（更新/删除）**：

```text
① 更新 MySQL
② 删除 Redis 里对应的 key
```

为什么是**删缓存**而不是**更新缓存**？

- 更新缓存要额外拼 JSON、容易和数据库值不一致；
- 删掉让下次读的时候重新回填，逻辑最简。

**为什么先写库再删缓存**？因为反过来（先删缓存再写库）的窗口期，并发读会把旧值回填进缓存，导致缓存长期是旧数据。

> 面试点：Cache Aside 的“不一致窗口”怎么产生？——写库成功、删缓存失败。缓解：删除重试、延迟双删、或订阅 binlog 异步删。学习项目做到“删除失败打日志 + 靠 TTL 兜底”即可。

## 4. 缓存的三兄弟（面试高频，背下来）

| 问题 | 场景 | 缓解 |
|---|---|---|
| 穿透 | 查一个**不存在**的 key，每次都打 MySQL | 缓存空值（null 也缓存短 TTL） |
| 击穿 | 一个**热点 key 过期**，瞬间所有请求打 MySQL | 互斥锁重建 / 逻辑过期 |
| 雪崩 | 大量 key **同时过期**，集体打 MySQL | TTL 加随机抖动 |

本 Part 只要知道概念和一句话缓解方案，不用全部实现。

## 5. 分布式锁：多实例下的“synchronized”

`synchronized` 锁的是**当前 JVM 内的线程**。项目部署两个实例时，两个 JVM 各有一把锁，谁也锁不住谁。

分布式锁 = 一把“所有实例都能看见的锁”。经典实现就是 Redis：

```text
SET lock:order:123 1 NX EX 30
```

- `NX`：key 不存在才设置成功（谁先抢到谁拿锁）；
- `EX 30`：30 秒自动过期（防止持锁者崩溃导致死锁）。

**Redisson** 把这个封装好了，还带“看门狗”自动续期（锁没干完自动延长，干完了释放）。用法（Session C 用）：

```java
RLock lock = redissonClient.getLock("lock:order:" + orderNo);
lock.lock();
try {
    // 临界区
} finally {
    lock.unlock();
}
```

> 面试点：分布式锁三要素——互斥（NX）、防死锁（过期）、防误删（value 存唯一标识，删前校验）。

## 6. Lua：让“读-判断-写”变成原子操作

这是本 Part 的**核心中的核心**。

### 6.1 问题：两步操作不是原子的

扣额度，直觉写法：

```java
int balance = redis.get("credit:1");   // ① 读
if (balance >= 1) {
    redis.set("credit:1", balance - 1); // ② 判断 + 写
}
```

两个请求并发时：

```text
请求 A：读到 balance=1
请求 B：读到 balance=1   ← B 也读到 1！
请求 A：写 0
请求 B：写 0             ← 两人都成功，余额变 -1？超扣！
```

### 6.2 Lua 解决：Redis 单线程，脚本原子执行

Redis 执行 Lua 脚本时**整个脚本不被打断**（单线程事件循环）。把三步写进脚本：

```lua
-- KEYS[1] = 余额 key，ARGV[1] = 要扣的数量
local balance = tonumber(redis.call('GET', KEYS[1]) or '0')
if balance >= tonumber(ARGV[1]) then
    redis.call('DECRBY', KEYS[1], ARGV[1])
    return 1
else
    return 0
end
```

返回 1 = 扣成功，返回 0 = 余额不足。因为脚本原子执行，两个并发请求会**排队**，不会同时读到旧值。

> 面试点：为什么 Lua 能原子？——Redis 是单线程，脚本执行期间不会有其他命令插入。

## 7. 限流：控制单位时间内的请求数

两种做法：

| 方案 | 原理 | 评价 |
|---|---|---|
| 固定窗口 | 1 秒一个计数器，超了就拒 | 简单，但窗口边界可能双倍放行 |
| 滑动窗口（ZSet） | 把每次请求的时间戳存进 ZSet，统计最近 N 秒的数量 | 更准，稍复杂 |
| Redisson RRateLimiter | 现成限流器，令牌桶思想 | **本项目用这个**，简单可靠 |

```java
RRateLimiter limiter = redissonClient.getRateLimiter("rate:user:" + userId);
limiter.trySetRate(RateType.PER_CLIENT, 5, 1, RateIntervalUnit.SECONDS);
boolean allowed = limiter.tryAcquire();
```

## 8. 幂等：同一个操作做 N 遍 = 做 1 遍

支付回调是最典型的场景：支付平台可能**重复通知**（网络重试），你的接口会被调用 N 次，但**只能入账一次**。

两个防重手段：

1. **唯一索引兜底**：`credit_order.order_no` 建唯一索引，重复插入直接失败。
2. **状态机防重**：订单状态只有 `CREATED → PAID` 合法；回调时 `UPDATE ... WHERE status='CREATED'`，影响行数为 0 说明已处理过，直接返回成功。

```java
int rows = orderMapper.updateStatusByOrderNo(orderNo, "PAID", "CREATED");
if (rows == 0) {
    // 已经处理过（或状态不对），幂等返回
    return "duplicate";
}
// 只有第一次走到这里，入账
```

## 9. BigDecimal：钱不能用 double 算

```java
0.1 + 0.2          // double 结果是 0.30000000000000004
BigDecimal("0.1").add(BigDecimal("0.2"))  // 0.3 ✅
```

规则：

- 金额字段：MySQL 用 `DECIMAL`，Java 用 `BigDecimal`；
- 构造用**字符串**：`new BigDecimal("0.1")`，不要 `new BigDecimal(0.1)`；
- 加/减/乘用 `add/subtract/multiply`，**不要用 double 运算后转回来**。

## 10. 订单状态机

```text
CREATED（待支付）
   ├── 支付成功回调 → PAID（已支付，入账额度）
   └── 超时 → CLOSED（已关闭，不占额度）
```

状态只允许合法迁移，非法迁移直接拒绝——这是幂等的底层保障。

## 11. 学完这篇，你该能回答

1. Redis 快在哪？它和 MySQL 谁说了算？
2. Cache Aside 的读写流程各是什么？
3. 为什么“先查余额再扣减”并发会超扣？Lua 怎么救？
4. 分布式锁和 synchronized 的区别？
5. 支付回调怎么做到重复调用只入账一次？

概念通了，开始 [Session A](session-a-redis-cache.md)。
