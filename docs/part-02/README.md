# Part 2 课程：用户、登录与权限

> 适合对象：Part 1 已完成，测试和 OpenAPI 的基本用法已经会（不熟先看 [Part 1 回顾教学](../part-01/review-testing-and-openapi.md)）。
>
> 目标：亲手实现注册、登录、JWT 鉴权、RBAC 权限和登录失败限制，得到一段面试能讲清楚的“用户体系实战经历”。

## 1. 这套课会怎样教你

和 week-01 一样，每个 Session 都按同一套流程：

1. **先建立直觉**：这个概念解决什么问题，不用它会怎样。
2. **再认识语法**：关键注解、类、配置逐行解释，不把代码当咒语。
3. **跟着做一遍**：给出准确路径、完整代码和执行的命令。
4. **观察结果**：告诉你成功时应该看到什么。
5. **主动制造错误**：亲手弄坏，读报错，再修好。
6. **独立练习**：迁移知识，不复制粘贴。
7. **对照参考答案**：做完再对，而不是边做边看。

## 2. 教学分三档，重复结构不重复教

后端接口开发有大量重复套路：每个接口基本是“DTO → Controller → Service → Mapper → 测试”五件套。所以本 Part（以及后续 Part）按三档模式推进：

| 档位 | 含义 | 你会得到什么 |
|---|---|---|
| 🧑‍🏫 我带 | 新概念、第一个接口 | 完整代码 + 逐步讲解 |
| 🤝 我们各写一半 | 结构重复但逻辑略新 | 骨架 + 你补关键部分，答案在文末 |
| 🏃 你自己做 | 完全重复的结构 | 需求 + 提示 + 验收标准，答案在文末 |

**为什么要这样**：校招面试要的是你能独立写出来，而不是“我看过老师写的”。第一次我带，第二次你补，第三次你自己来，三次之后这套五件套就是你的肌肉记忆。

## 3. 你和老师怎样配合

每个 Session 完成后，把以下内容发给我：

```text
Session：Session X
完成到：第 X 节 / 第 X 步
执行的命令：...
期望结果：...
实际结果：...
完整报错：...
我对复盘题的回答：...
```

我会检查代码是否真的符合目标、指出“能运行但设计不对”的地方，并根据你的回答决定要不要重讲某个概念。

## 4. 目前项目处于哪里

- [x] Part 1 完成：工程骨架、统一响应/异常、示例接口、测试、OpenAPI、Docker Compose。
- [x] `learnhub-user` 模块已创建，POM 预置了 Security、MyBatis-Plus、JJWT、Lombok，但**没有任何业务代码**。
- [x] `learnhub-application` 目前只依赖 `learnhub-common`，还没把 user 模块接进来。
- [ ] MySQL 尚未接入（Flyway 迁移、数据源配置都没有）。
- [ ] Docker Desktop 需要先启动（当前没运行）。

## 5. 六个 Session 目录

> 第一次接触 Spring Security？先读 [前置教学：Spring Security 零基础入门](primer-spring-security.md)，再开始 Session A/B 里的安全相关步骤。
>
> 第一次写 `@Mock` / `@InjectMocks`（Session B 的 AuthServiceTest 会用到）？先读 [前置教学：Mockito 与 Service 测试](primer-mockito-and-service-testing.md)。
>
> 完成 Session A ~ E 后，用 [Part 2 重点知识总结](review-key-points.md) 做整体复习和面试速查。

| Session | 主题 | 交付物 | 档位 |
|---|---|---|---|
| [A](session-a-connect-mysql-and-flyway.md) | MySQL + Flyway + 第一张用户表 | 应用启动自动建表 | 🧑‍🏫 我带 |
| [B](session-b-register-and-password-security.md) | 注册与密码安全（BCrypt） | `POST /api/v1/auth/register` | 🧑‍🏫 我带 + 🤝 各写一半 |
| [C](session-c-login-and-jwt.md) | 登录与 JWT 鉴权 | `POST /login`、`GET /users/me` | 🧑‍🏫 我带 + 🤝 各写一半 |
| [D](session-d-rbac.md) | RBAC 角色权限 | 管理员用户列表接口 | 🧑‍🏫 我带 + 🏃 你自己做 |
| [E](session-e-login-attempt-limit-and-audit.md) | 登录失败限制与审计日志 | 连续失败锁定 + 结构化日志 | 🧑‍🏫 我带 + 🏃 你自己做 |
| [F](session-f-acceptance-and-review.md) | 完整验收与答辩 | 全绿测试 + 面试问答 | 🧑‍🏫 我带 |

## 6. 本 Part 的固定约定

- Java 根包：`com.github.comui520.learnhub`；user 模块代码放在 `...learnhub.user.*`。
- HTTP API 前缀：`/api/v1`；认证相关接口在 `/api/v1/auth/**`。
- 统一响应：`ApiResponse<T>`；错误码风格：`USER_ERROR_xxxx`，通用错误用 `COMMON_xxxx`。
- 密码、token、密钥绝不写进日志和响应；`User` 实体绝不直接返回给前端。
- `SecurityConfig` 是进程级全局配置，**只需一份**（现在在 user 模块）；新模块不要重复写过滤器链，公开路径加到它的 `permitAll()` 或按需用 `@PreAuthorize`。
- RBAC 权限全局生效：过滤器给每个请求装好角色/权限，任何模块的接口都能用 `hasRole` / `hasAuthority`；新模块要用的权限点通过迁移往 `permission` 表加种子数据。
- 数据库变更一律走 Flyway，已执行的迁移文件不许修改。
- 迁移文件放在**拥有这些表的模块**的 `src/main/resources/db/migration/` 下；**版本号全局连续**（所有模块共用一条 `flyway_schema_history`），新模块第一版从当前最大版本的下一个数字开始，不要各模块从 V1 重排。
- 控制器只做 Web 边界，业务在 Service，SQL 重要的手写。
- 每完成一个 Session 做一次 Git 提交（Git 由你自己操作）。

## 7. 本 Part 不会提前做什么

- 不接 Redis（登录失败限制先做内存版，Part 6 换成 Redis 并讲清为什么）。
- 不接 RabbitMQ、MinIO、Qdrant、Spring AI（那些在 Part 4 之后）。
- 不写前端，不接短信/邮箱验证码，不实现密码找回。
- 不做 Refresh Token 双令牌（Access + Refresh），先把单令牌讲透，设计权衡写进笔记。

## 8. 最终验收清单（Session F 逐项检查）

- [ ] `mvn clean verify` 全绿。
- [ ] 注册：成功、重复用户名 409、参数错误 400，密码在数据库里是 BCrypt 哈希。
- [ ] 登录：正确返回 token；错误返回统一 401 且不区分“用户不存在/密码错”。
- [ ] 未带 token 访问受保护接口返回 401 JSON；带无效/过期 token 同样 401。
- [ ] 普通用户访问管理员接口返回 403 JSON。
- [ ] 连续 5 次密码错误后账号临时锁定。
- [ ] 日志里没有密码、token、密钥。
- [ ] Swagger 里注册/登录/受保护接口都能演示（带 Bearer Token）。
- [ ] 你能脱离代码回答第 9 节的答辩题。

## 9. 答辩题（Session F 前要能回答）

1. 一个登录请求经过 Spring Security 的哪些关键步骤？
2. JWT 为什么“无状态”？它带来了什么新问题？
3. 退出登录怎么实现？JWT 主动失效为什么难？
4. 为什么密码要加盐哈希？BCrypt 的盐存在哪里？
5. RBAC 的表怎么设计？为什么需要中间表？
6. 401 和 403 分别什么时候返回？
7. `@PreAuthorize` 和 URL 级权限规则有什么区别？
8. 登录失败限制怎么实现？为什么最终要用 Redis？
9. “用户名已存在”和“用户名或密码错误”的提示为什么不能随便给？
10. 数据库唯一索引和 Service 里的查重是什么关系？

从 [Session A](session-a-connect-mysql-and-flyway.md) 开始。不要一口气读完全部六个文档。
