# Session A：接入 Redis + 缓存知识库（Cache Aside）

> 目标：把 Redis 接进项目，让“知识库详情”走缓存——第一次查库，之后读 Redis。做完能看到日志里“第二次查询不再打 MySQL”。
> 档位：🧑🏫 我带（代码全给，逐段解释）。预计 3～4 小时。

## 0. 本 Session 完成时的样子

```text
GET /knowledge-bases/1
  → 查 Redis：没有 → 查 MySQL → 回填 Redis（TTL 60s）→ 返回
  → 再查：命中 Redis，直接返回（日志里没有 SQL）

PUT /knowledge-bases/1  或  DELETE
  → 改/删 MySQL → 删 Redis key
```

## 1. Step 1：解开依赖

`learnhub-infrastructure/pom.xml` 里两个注释解开：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-boot-starter</artifactId>
</dependency>
```

- `spring-boot-starter-data-redis`：官方 Redis 客户端，提供 `RedisTemplate` / `StringRedisTemplate`（Session A/B 用）。
- `redisson-spring-boot-starter`：高级客户端，提供分布式锁、限流器（Session B/C 用）。

## 2. Step 2：yml 配置

`application-dev.yml` 加：

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
```

6379 是 Redis 默认端口，`.env` 里已有 `REDIS_PORT=6379`。`docker compose ps` 确认 `learnhub-redis-1` healthy。

## 3. Step 3：先学会 RedisTemplate（本 Session 只用 3 个操作）

项目里用 `StringRedisTemplate`（key 和 value 都是 String），最省心——不会像默认 `RedisTemplate` 那样把对象 JDK 序列化成乱码。

```java
@Autowired
private StringRedisTemplate redis;

// 写：值 + 过期秒数
redis.opsForValue().set("kb:detail:1", jsonString, 60, TimeUnit.SECONDS);

// 读
String json = redis.opsForValue().get("kb:detail:1");

// 删
redis.delete("kb:detail:1");
```

只需要这三个。`opsForValue()` 是 String 类型操作的入口，`TimeUnit.SECONDS` 对应 Redis 的 TTL。

> 为什么存 JSON 字符串而不是对象：`StringRedisTemplate` 天然是字符串；对象自己用 Jackson 转成 JSON（`ObjectMapper`，Part 2 用过）。

## 4. Step 4：知识库详情加缓存

`KnowledgeBaseService.getById` 改成 Cache Aside：

```java
private static final String KB_CACHE_KEY = "kb:detail:";

private final StringRedisTemplate redis;
private final ObjectMapper objectMapper;
// 构造器注入

public KnowledgeBaseResponse getById(Long userId, Long id) {
    // ① 查缓存
    String cacheKey = KB_CACHE_KEY + id;
    String cached = redis.opsForValue().get(cacheKey);
    if (cached != null) {
        try {
            return objectMapper.readValue(cached, KnowledgeBaseResponse.class);
        } catch (Exception e) {
            log.warn("cache parse failed, fallback to db: key={}", cacheKey, e);
        }
    }

    // ② 缓存没命中 → 查库（原来的逻辑）
    KnowledgeBase kb = findOwned(userId, id);


    // ③ 回填缓存（60 秒过期）
    try {
        redis.opsForValue().set(cacheKey,
                objectMapper.writeValueAsString(response), 60, TimeUnit.SECONDS);
    } catch (Exception e) {
        log.warn("cache set failed, ignore: key={}", cacheKey, e);
    }
    return response;
}
```

逐段理解：

- **缓存 key 设计**：`kb:detail:1` = 业务前缀 + 资源名 + id。Redis 没有表，key 命名就是你的“表结构”，全项目要统一风格。
- **① 命中直接返回**：省掉一次 MySQL 查询。
- **① 解析失败降级**：缓存里 JSON 坏了不能影响业务，catch 住继续走查库——**缓存永远是可有可无的加速层**。
- **③ 回填带 TTL**：60 秒。TTL 是缓存安全的最后防线：就算删缓存失败，最多 60 秒后也会自动过期回源。
- **③ 写缓存失败也不能让接口失败**：catch + log。

> 面试点：为什么回填/读取失败都要降级？——因为缓存不是真相，MySQL 才是；缓存挂了业务不能挂。

## 5. Step 5：更新/删除时删缓存（关键！）

缓存只加在读路径上是不够的——改完 description 后，Redis 里还是旧值，用户会读到脏数据。所以**写路径必须删缓存**。

打开 `KnowledgeBaseService`，在 `update` 方法里，`this.updateById(kb);` 之后加一行：

```java
public KnowledgeBaseResponse update(Long userId, Long id, UpdateKnowledgeBaseRequest request) {
    KnowledgeBase kb = findOwned(userId, id);
    // 改名查重：排除自己
    if (this.exists(new LambdaQueryWrapper<KnowledgeBase>()
            .eq(KnowledgeBase::getUserId, userId)
            .eq(KnowledgeBase::getName, request.name())
            .ne(KnowledgeBase::getId, id))) {
        throw new BusinessException(KnowledgeErrorCode.KNOWLEDGE_BASE_NAME_EXISTS);
    }
    kb.setName(request.name());
    kb.setDescription(request.description());
    this.updateById(kb);
    redis.delete(KB_CACHE_KEY + id);   // ← 加这一行：先改库，再删缓存
    return new KnowledgeBaseResponse(kb.getId(), kb.getName(), kb.getDescription(), kb.getCreatedAt());
}
```

`delete` 方法同理，`this.removeById(kb.getId());` 之后加：

```java
@Transactional
public void delete(Long userId, Long id) {
    KnowledgeBase kb = findOwned(userId, id);
    // 有文档先删文档（拒绝级联删除，避免孤儿记录）
    if (knowledgeBaseDocumentMapper.countByKnowledgeBaseId(id) > 0) {
        throw new BusinessException(KnowledgeErrorCode.KNOWLEDGE_BASE_HAS_DOCUMENTS);
    }
    this.removeById(kb.getId());
    redis.delete(KB_CACHE_KEY + id);   // ← 加这一行
}
```

要点：

- **顺序必须是先改库、再删缓存**（primer 3 节讲过原因）。反过来会有并发读把旧值回填的窗口。
- **删缓存失败最多导致 60 秒旧数据**，TTL 兜底，业务不受影响。
- `redis` 和 `objectMapper` 在 Step 4 已经注入构造器，这里直接用同一个 `redis` 就行。
- `create` 不需要删缓存（新 id 本来就没有缓存）；`page` 列表接口先不缓存（列表缓存失效点更多，属于升级题）。

## 5.5 为什么先缓存“知识库详情”，而不是 chat 里的 fileIds？

你可能会想：chat 每次都要查 `listBoundFileIds`，那才是热路径，为什么不先缓存它？

|      | 知识库详情                 | listBoundFileIds   |
| ---- | --------------------- | ------------------ |
| 调用频率 | 详情页打开时                | **每次 chat 都调**（更热） |
| 失效点  | 只有 update / delete 两处 | bind / unbind 两处   |
| 数据形态 | 单条对象                  | 一个 id 列表           |
| 教学价值 | 最经典的 Cache Aside 入门   | 同一模式，但失效点更多        |

**Part 6 的主线是“额度 + 订单并发”，缓存只是教学入口**：先用最简单的“详情缓存”把 Cache Aside 模式练熟，再在升级题里把同一模式用到热路径。优化不是本 Part 的目标，学会“什么时候该缓存、什么时候该删”才是。

## 5.6 升级题（推荐做）：缓存 chat 的 fileIds

把 Cache Aside 用到热路径上。`RagChatService.streamChat` 每次提问都调 `listBoundFileIds`，里面查两次 MySQL（归属校验 + 关联表）。缓存只包住**关联表查询**，归属校验保持实时（安全优先，删库后立刻不能访问）。

1. key 设计：`kb:files:1`（业务前缀 + 资源 + id），value 存 JSON 数组 `[5,6,7,8,9,10]`，TTL 60 秒。
2. 读路径：`listBoundFileIds` 里先查缓存，命中直接反序列化返回；miss 才查关联表并回填。
3. 失效点：`bindDocuments` 和 `unbindDocuments` 成功后，`redis.delete("kb:files:" + knowledgeBaseId)`。
4. 验证：第一次 chat 日志有 `SELECT ... FROM knowledge_base_document`，第二次没有；bind/unbind 后缓存被删，再 chat 重新回填。

> 提示：`listBoundFileIds` 里的 `findOwned` 不要缓存——归属校验必须实时，否则用户删库后还能靠缓存继续访问。

## 6. Step 6：验证

1. 重启应用。
2. 第一次 `GET /api/v1/knowledge-bases/1`：日志出现 `SELECT ... FROM knowledge_base WHERE id=? AND user_id=?`（查了库）。
3. 第二次再查：**日志没有这条 SQL**，响应秒回。
4. 去 Redis 里亲眼看看：

```powershell
docker compose exec redis redis-cli
GET kb:detail:1
TTL kb:detail:1
```

能看到 JSON 和剩余过期秒数。

5. `PUT /api/v1/knowledge-bases/1` 改名后，`GET kb:detail:1` 应该返回空（缓存被删），再查详情是新名字。

## 7. 主动制造错误

**错误 A：注释掉第 ③ 步回填**——每次查询都打 MySQL。亲手感受“没有缓存”的日志。

**错误 B：回填不设 TTL**——缓存永远不过期。然后手动删缓存（`DEL kb:detail:1`）再查，还是好的；但以后代码里任何“删缓存失败”都会留下永久脏数据。体会 TTL 兜底的意义。

## 8. 复盘题

1. 缓存 key 为什么这么设计？`kb:detail:` 前缀的作用？
2. 为什么读缓存/写缓存失败都要降级而不是报错？
3. 先改库再删缓存，窗口期是什么？TTL 怎么兜底？
4. 什么数据适合缓存，什么不适合？（提示：读多写少 vs 写多读少）

完成后进入 [Session B](session-b-rate-limit-and-credit.md)：限流 + 额度 + 原子扣减——本 Part 的硬核部分。
