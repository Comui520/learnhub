# LearnHub 第一周课程：Java 恢复与工程骨架

> 学习周期：Day 1～Day 7  
> 项目根目录：`D:\LearnHub\LearnHubBackend`  
> 本周成果：一个能构建、能启动、响应统一、校验完整、错误可控、带测试和接口文档的 Spring Boot 应用。

## 先说明我们的学习方式

这个项目不是“照抄一次就算完成”。每天按下面的闭环学习：

1. 先读当天文档的“原理课”，控制在 45～60 分钟。
2. 不看完整答案，先按“动手任务”自己实现。
3. 用文档给出的命令和测试验收。
4. 主动制造至少一个错误，并记录排查过程。
5. 回答当天的复盘问题，不能回答就回看对应知识。
6. 把代码、报错或你的答案发给老师进行审查。

遇到问题时，请提供四项信息：

```text
我正在做：Day X / 第 X 步
我期望看到：...
实际看到：...
完整报错或相关代码：...
```

不要只发“报错了”，完整错误通常比最后一行更重要。

## 当前基线（2026-07-20 已检查）

- [x] JDK 21 和 Maven 配置统一。
- [x] 父项目能够完成 Maven Reactor 构建。
- [x] 启动类已有正确包名和 `@SpringBootApplication`。
- [x] `learnhub-application` 已引入 `spring-boot-starter-web`。
- [x] `spring-boot-maven-plugin` 已启用。
- [x] 已创建基础 `application.yml`。
- [ ] 尚未初始化 Git。
- [ ] 尚未完成运行时依赖瘦身。
- [ ] 尚未确认应用能够成功启动并通过健康检查。
- [ ] 尚未实现统一响应、异常处理和示例接口。
- [ ] 尚未编写 Web 测试和 Docker Compose。

“代码已经改对”与“能够解释为什么这样改”是两个验收项。已完成的内容仍需要学习对应原理。

## 七天课程导航

| 天数 | 主题 | 当天可交付成果 |
|---|---|---|
| [Day 1](day-01-project-baseline-and-maven.md) | 工程基线、Git 与 Maven | 可重复构建的干净仓库，明确模块和依赖 |
| [Day 2](day-02-spring-boot-startup-and-health.md) | Spring Boot 启动与健康检查 | 应用成功启动，`/actuator/health` 返回 `UP` |
| [Day 3](day-03-java-generics-errors-and-api-response.md) | 泛型、枚举与异常 | `ApiResponse<T>`、错误码、业务异常及单元测试 |
| [Day 4](day-04-mvc-validation-and-exception-handler.md) | MVC、参数校验与全局异常 | 示例接口覆盖成功、参数错误、业务错误 |
| [Day 5](day-05-web-tests-and-openapi.md) | MockMvc、JUnit 5 与 OpenAPI | 三类 Web 测试通过，Swagger 可查看接口 |
| [Day 6](day-06-docker-compose-and-dev-config.md) | Docker Compose 与配置管理 | 五个基础组件启动，敏感配置不入库 |
| [Day 7](day-07-architecture-review-and-acceptance.md) | 架构、验收与复盘 | 依赖图、README、完整构建和第一周复盘 |

## 每天的时间建议

```text
基础知识        45～60 分钟
项目实现        90～150 分钟
测试与排错      30～45 分钟
复盘与记录      15～30 分钟
算法题          30 分钟（与本课程独立）
```

时间不够时，优先保证当天的“最小验收线”，不要靠跳过测试赶进度。

## 本周统一约定

- Java 根包：`com.github.comui520.learnhub`。
- API 前缀：`/api/v1`。
- 配置文件使用 YAML。
- 业务代码不使用默认包。
- Controller 不捕获所有异常，由全局异常处理器统一转换。
- HTTP 状态码与业务错误码分工明确，不能永远返回 HTTP 200。
- 密码、Token、API Key 等不能提交到 Git。
- 每天至少进行一次有意义的 Git 提交。

## 本周暂时不学

- JWT 与 Spring Security 过滤器链。
- MyBatis/MyBatis-Plus 和数据库表设计。
- Redis 缓存与分布式锁。
- RabbitMQ 消费和重试。
- RAG、Embedding、Qdrant 调用。
- 微服务和 Spring Cloud。

这些内容后续都会学。本周提前接入只会增加自动配置和排错变量。

## 第一周毕业标准

- [ ] `mvn clean verify` 成功。
- [ ] 可执行 JAR 能启动。
- [ ] `/actuator/health` 返回 `UP`。
- [ ] 示例接口成功、参数错误、业务错误三条路径均可验证。
- [ ] 三类路径都有自动化测试。
- [ ] Swagger UI 可以打开并展示示例接口。
- [ ] Docker Compose 中五个组件状态正常。
- [ ] `.gitignore`、`.env.example`、README 和依赖图齐全。
- [ ] 能口头解释本周复盘题，而不只是展示代码。

