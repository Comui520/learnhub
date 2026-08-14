# Session F：完整验收与答辩

> 目标：从干净状态跑通全部测试和接口，整理 Bug 记录，用面试题检验自己是否真的掌握。
>
> 预计时间：3～4 小时（答辩部分可以分两次）。

## 0. 今天到底要学会什么

1. 模拟“一个陌生开发者拿到你的仓库”的完整验收流程。
2. 把 Part 2 的六个 Session 串成一段能讲 5 分钟的项目故事。
3. 用 10 道面试题检验掌握程度，每道都能按“是什么 → 解决什么 → 本项目怎么用 → 限制”四段式回答。

---

## 1. 完整验收流程

### 1.1 检查工作区

```powershell
cd D:\LearnHub\LearnHubBackend
git status
```

确认：

- 没有 `target`、`.env`、日志文件被跟踪。
- `application-dev.yml` 里没有真实生产密钥。
- 你的 Git 历史里每个 Session 有一次有意义的提交。

### 1.2 完整构建与测试

```powershell
mvn clean verify
```

记录：

- Reactor 是否全部 SUCCESS。
- Tests run、Failures、Errors 数量。
- 最终 BUILD SUCCESS。

### 1.3 启动并走完整业务流

```powershell
mvn -pl learnhub-application -am spring-boot:run "-Dspring-boot.run.profiles=dev"
```

按顺序在 Swagger UI（http://localhost:8080/swagger-ui/index.html）或 curl 里走一遍：

1. 注册普通用户 `alice` → 200。
2. 重复注册 `alice` → 409。
3. 空用户名注册 → 400。
4. 登录 `alice` → 200，拿到 token。
5. 不带 token 访问 `/api/v1/users/me` → 401 JSON。
6. 带 token 访问 `/api/v1/users/me` → 200。
7. 普通用户访问 `/api/v1/users` → 403 JSON。
8. 把 `alice` 提成 ADMIN（SQL），重新登录，访问 `/api/v1/users` → 200。
9. 连续输错密码 5 次 → 423 锁定。
10. 用错误密码登录日志里能看到 `login failed`，没有密码明文。

### 1.4 数据库核对

```powershell
docker compose exec mysql mysql -ulearnhub -pchange-me-mysql learnhub -e "SELECT id, username, status FROM user; SELECT user_id, role_id FROM user_role; SELECT * FROM flyway_schema_history;"
```

确认迁移版本 1/2（有审计表则 3）全部 success=1。

---

## 2. Definition of Done 检查

Part 2 的每个接口都确认：

- [ ] 正常流程可用。
- [ ] 参数边界经过校验（400）。
- [ ] 身份与权限经过校验（401/403 语义正确）。
- [ ] 异常响应统一为 `ApiResponse`，不泄漏内部信息。
- [ ] 密码/token 不出现在响应和日志中。
- [ ] 数据库变更走 Flyway，已执行文件未修改。
- [ ] 有单元测试或切片测试覆盖主要路径。
- [ ] OpenAPI 注解已更新（含错误响应）。
- [ ] 考虑了重复请求、并发或服务不可用（写出你的思考）。

有一条不满足就回对应 Session 修，不要带病进入 Part 3。

---

## 3. 整理 Bug 记录

回顾 Part 2 你踩过的坑，挑 2～3 个写进 Bug 记录（比如 `@ApiResponse` 同名冲突、`@EnableMethodSecurity` 忘加、过滤器抛异常导致 500、迁移文件单下划线）。模板：

```text
现象：
我最初以为：
我检查了：
真正根因：
修复方式：
下次怎样更快定位：
```

这是面试“讲一个你解决的 Bug”的直接素材。

---

## 4. 更新主计划

打开 `D:\LearnHub\LEARNHUB_PLAN.md`：

- 勾选 Part 2 的任务和验收标准。
- 在“决策与问题记录”追加本 Part 的真实决策，例如：
  - 用户表字段与唯一索引设计。
  - 登录失败限制先内存后 Redis（为什么）。
  - 单 JWT 令牌、暂不做 Refresh Token（为什么）。
  - 注册默认 USER 角色，权限粒度先角色后权限。

---

## 5. 答辩题与参考要点

每题先脱离文档自己回答，再对照要点。四段式：**是什么 → 解决什么问题 → LearnHub 怎么用 → 有什么限制**。

### 1. 一个登录请求经过 Spring Security 的哪些关键步骤？

要点：请求进来 → `JwtAuthenticationFilter` 尝试从 `Authorization: Bearer` 解析 token（没有则跳过）→ 后续过滤器判断是否已认证 → 未认证走 `AuthenticationEntryPoint` 返回 401 JSON → 已认证放行进 Controller → `@PreAuthorize` 检查权限 → 无权限抛 `AccessDeniedException` 由全局异常处理器转 403。

### 2. JWT 为什么“无状态”？它带来什么新问题？

要点：服务端不存会话，token 自包含，任意实例都能验证签名，天然支持水平扩展。代价：签发后难以主动失效（退出登录、封号难）、token 泄露有风险窗口。解法方向：黑名单（Redis）、短有效期 + Refresh Token，Part 6 实现。

### 3. 退出登录怎么实现？

要点：单令牌方案下“前端删 token + 服务端短期黑名单”。黑名单需要 Redis（Part 6）；Refresh Token 方案则吊销 refresh token。要能说出：纯 JWT 无状态下“服务端不知道 token 被删了”，所以必须引入状态。

### 4. 为什么密码要加盐哈希？BCrypt 的盐存在哪里？

要点：哈希不可逆，盐防彩虹表、让相同密码哈希不同。BCrypt 的盐是**随机生成、嵌在哈希串里**（`$2a$10$` 后的前 22 个字符），验证时从存储串取出盐重算比对，所以不需要单独存盐字段。

### 5. RBAC 的表怎么设计？为什么需要中间表？

要点：user、role、permission + user_role、role_permission 五张表。用户和角色、角色和权限都是多对多，中间表存关联；唯一索引防重复绑定，外键保证引用完整。好处：加角色/改权限不动用户表。

### 6. 401 和 403 分别什么时候返回？

要点：401 未认证（没 token、token 无效/过期），403 已认证但无权限。实现上：401 由 `AuthenticationEntryPoint` 返回，403 有两条路（过滤器链 `AccessDeniedHandler` / 方法级 `@PreAuthorize` 由全局异常处理器兜住）。

### 7. `@PreAuthorize` 和 URL 级权限规则有什么区别？

要点：URL 规则在过滤器链里按路径匹配，适合粗粒度；`@PreAuthorize` 在方法调用前用 SpEL 检查，能拿到方法参数做更细的判断（如“只能改自己的资料”）。后者需要 `@EnableMethodSecurity`。

### 8. 登录失败限制怎么实现？为什么最终要用 Redis？

要点：计数 + 锁定窗口，用 `ConcurrentHashMap` 原子计数。内存版重启丢失、多实例不共享；Redis 提供共享存储、键过期、Lua 原子操作（Part 6）。

### 9. “用户名已存在”和“用户名或密码错误”为什么不能随便给？

要点：注册接口的“用户名已存在”是业务需要，但登录接口如果把“用户不存在”和“密码错误”分开返回，就成了用户名枚举器。安全原则：失败信息要统一。

### 10. 数据库唯一索引和 Service 查重是什么关系？

要点：Service 查重提供友好报错（409），唯一索引是并发下的最后防线（两个请求同时注册同名，数据库保证只有一个成功）。单靠查重有竞态，单靠索引报错不友好，两个都要。

---

## 6. 五分钟项目故事（脱稿能讲）

按这个顺序串起来，就是面试的“项目介绍”：

```text
LearnHub 是 AI 知识库平台，我是后端负责人之一。
Part 1 搭了模块化单体骨架：统一响应、异常处理、测试、OpenAPI、Docker Compose。
Part 2 实现了用户体系：
  - Flyway 管理表结构，BCrypt 存密码；
  - 登录签发 JWT，自定义过滤器鉴权；
  - RBAC 角色权限，管理员接口越权返回 403；
  - 登录失败锁定 + 结构化审计日志。
设计取舍：单令牌 + 短期黑名单，先内存后 Redis；权限先角色后权限粒度。
踩过的坑：@ApiResponse 同名冲突、@EnableMethodSecurity 忘加导致 403 变 500……
```

---

## 7. 完成后的提交

全部通过后：

- 更新 `LEARNHUB_PLAN.md` 勾选和决策记录。
- 做一次 Git 提交（内容自定，但要说清改了什么）。
- 把最终 `git status`、Reactor Summary、测试摘要、10 道答辩题答案发给我。

通过验收后进入 **Part 3：知识库与文件管理**（MinIO + 文档状态机 + 用户数据隔离）。Part 3 会大量出现重复的 CRUD 接口，正好用上我们约定的三档模式：第一个知识库 CRUD 我带，后面你自己做。

