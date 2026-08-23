# Part 6 课程：Redis、额度与订单并发

> 前置要求：Part 5 完成（RAG 问答能流式回答），Docker Compose 里 Redis 正常运行（`docker compose ps` 看到 learnhub-redis-1 healthy）。
>
> 本 Part 是**第一次接触 Redis**，请先读 [前置教学：Redis 与并发一致性](primer-redis-and-concurrency.md)，再开始 Session A。

## 0. 本 Part 到底在做什么（一句话）

给你的 AI 问答加上**商业化闭环**：用户有额度，每次提问扣一次；并发下不会多扣、不会欠账；充值走模拟订单，支付回调重复调用只入账一次。整套东西用 Redis 撑起缓存、限流、原子扣减——这是面试里“高并发”最常问的一块。

## 1. 本 Part 新增的技术（第一次见面，先认识）

| 技术 | 是干嘛的 | 依赖怎么写 |
|---|---|---|
| Redis | 内存数据库：缓存、计数器、限流、分布式锁 | `spring-boot-starter-data-redis`（infrastructure 解开注释） |
| Redisson | Redis 的 Java 客户端，自带分布式锁/限流器 | `redisson-spring-boot-starter`（infrastructure 解开注释） |
| Lua 脚本 | 让 Redis 把“读-判断-写”三步原子执行，防并发超扣 | Redis 自带能力，不用加依赖 |
| BigDecimal | 金额计算的正确姿势（浮点不能算钱） | JDK 自带 |

**依赖放哪**：Redis/Redisson 是外部系统适配 → `learnhub-infrastructure`。额度、订单的业务代码放 `learnhub-credit`（这个模块终于用上了），它依赖 infrastructure，方向单向不循环。application 模块要加上对 `learnhub-credit` 的依赖。

## 2. 学习路径

| 文档 | 主题 | 档位 |
|---|---|---|
| [primer](primer-redis-and-concurrency.md) | Redis 数据结构 + 缓存模式 + 分布式锁 + Lua + 幂等 + BigDecimal（零基础） | 🧑🏫 阅读 |
| [Session A](session-a-redis-cache.md) | 接入 Redis + 缓存知识库（Cache Aside） | 🧑🏫 我带 |
| [Session B](session-b-rate-limit-and-credit.md) | 限流 + 额度表 + 原子扣减（Lua） | 🧑🏫 概念 + 🤝 代码 |
| [Session C](session-c-order-and-idempotency.md) | 模拟订单 + 支付回调幂等 + 定时关单 | 🧑🏫 概念 + 🤝 代码 |

## 3. 固定约定

- **数据库是真相，Redis 是加速**：缓存丢了可以回源，额度余额以 MySQL 为准（Redis 只是挡并发）。
- **金额一律 `BigDecimal`**，禁止 `double`/`float`。
- **额度流水只增不改**（insert-only），每次变动一行，能还原历史。
- **并发扣减必须原子**：Lua 脚本或 Redisson 锁，禁止“先查再减”的两步 Java 代码。
- **支付回调必须幂等**：同一订单重复回调，只入账一次。

## 4. 最终验收清单

- [ ] 第二次查同一知识库不走 MySQL（缓存生效，日志可证）。
- [ ] 更新/删除知识库后，缓存被正确删除，不会读到旧数据。
- [ ] 限流：1 秒内超过 N 次请求返回 429（或业务错误码）。
- [ ] 用户剩余 1 次额度时，**并发 10 次提问最多成功 1 次**。
- [ ] 额度流水能完整还原余额变化。
- [ ] 同一支付回调重复调用 N 次，只入账一次、订单状态只 PAID 一次。
- [ ] 超时订单能自动关闭，不占用额度。
- [ ] `mvn compile` 全绿。

## 5. 答辩题预告

1. Redis 为什么快？和 MySQL 的分工是什么？
2. Cache Aside 模式怎么做？为什么“删缓存”而不是“更新缓存”？
3. 并发扣减为什么不能“先查再减”？Lua 为什么能保证原子？
4. 分布式锁和本地锁（synchronized）的区别？什么场景必须分布式锁？
5. 支付回调重复调用怎么保证只入账一次？
6. 金额为什么用 BigDecimal？
7. 缓存穿透/雪崩/击穿分别是什么？怎么缓解？

从 [前置教学](primer-redis-and-concurrency.md) 开始。
