# Part 7：学习功能、测试、性能与部署

> 目标：把前面已经能运行的功能，变成一个有业务闭环、有测试证据、有监控、有部署说明的校招项目。
>
> 本 Part 仍然按 Session 学习，但不按天安排。每完成一个 Session，都要先运行验收命令，再进入下一个。

## 0. 先明确本 Part 做什么

Part 7 分成一条核心线和两条增强线：

```text
学习功能：知识库 -> AI 出题 -> 题目 -> 答题 -> 错题 -> 复习
测试能力：Service 单测 -> Controller 测试 -> MySQL 集成测试 -> 并发测试
工程能力：Actuator -> 指标 -> SQL 排查 -> 压测 -> Docker/README
```

学习功能是本 Part 的核心主线；测试、性能和部署是增强线。第一轮不要求三条线全部完成。

## 0.1 你现在应该走哪条路线

按照“先亲自实现业务，再做收尾优化”的目标，当前建议走核心路线：

```text
Session A：学习题目、答题判分、错题记录
    ↓
Session D：知识库检索、AI 出题、JSON 解析、题目入库
    ↓
Part 7 核心验收：能从知识库生成题目并完成答题
    ↓
Part 8：项目收尾、复盘和有限优化
```

Session B 和 Session C 可以跳过第一轮：

- Session B 的 Mockito、MockMvc、Testcontainers、并发测试暂时不作为阻塞项。
- Session C 的 Prometheus、Grafana、k6 和完整部署报告暂时不作为阻塞项。
- 你已经接触过测试、Docker、Actuator 和 RabbitMQ，没必要为了勾选文档重复做一套不够详细的练习。

以后为了面试展示，再从 B/C 中各挑一个最有价值的内容补上即可，不需要完整重做。

## 1. 先检查当前仓库

当前项目中：

- `learnhub-study` 模块已经存在，但目前基本是空模块。
- `learnhub-application` 已经有 `spring-boot-starter-test` 和 Actuator。
- `learnhub-user` 已经有 JUnit 5、Mockito、AssertJ 的测试样例。
- Part 6 的 `CreditService`、支付回调和额度扣减，是本 Part 的重点测试对象。
- 测试默认放在“被测模块的 `src/test/java`”；需要完整 Spring 容器时放到 `learnhub-application/src/test/java`。

不要一开始在所有模块都加测试依赖。先读 primer，再按 Session B 的依赖清单添加。

## 2. 四个 Session 的完成顺序

| 文档 | 你会完成什么 | 主要产物 |
|---|---|---|
| [前置教学](primer-testing-and-integration.md) | 搞懂测试层级和新测试技术 | 能判断该写哪种测试 |
| [Session A](session-a-study-domain.md) | 做一个最小学习闭环 | `learnhub-study` 的表、实体、Mapper、Service、Controller |
| [Session B](session-b-testing-and-concurrency.md) | 可选：为核心业务补测试和并发验证 | 单元测试、MockMvc、Testcontainers、并发测试 |
| [Session C](session-c-observability-performance-deployment.md) | 可选：让项目可观察、可压测、可部署 | Actuator、Prometheus、k6、README 部署流程 |
| [Session D](session-d-ai-question-generation.md) | 把固定题目升级为知识库驱动的 AI 出题 | RAG 检索、Prompt、模型 JSON 解析与业务校验、事务入库、失败重试与接口验收 |

## 3. 每个功能都按同一个流程做

```text
1. 先读本 Session 的技术教学
2. 新建 Flyway migration
3. 新建 Entity / DTO / Mapper
4. 写 Service 的业务规则
5. 写 Controller 和 OpenAPI
6. 先用 Swagger 或 curl 验证正常路径
7. 主动制造非法参数、越权、重复请求
8. 写对应测试
9. 记录日志、指标和排错结论
10. 更新 README 和决策记录
```

## 4. Definition of Done

核心路线不要求“每个方法都有测试”，第一轮只要求业务闭环完成：

- [ ] 用户可以从自己的知识库获取题目。
- [ ] 用户可以提交答案，后端计算正确/错误。
- [ ] 答错会进入错题本，同一用户同一题不会重复产生错题记录。
- [ ] 用户不能读取其他用户的题目或错题。
- [ ] AI 返回非法 JSON 时不会写入半成品题目。
- [ ] 能用 PowerShell 或 Swagger 验证正常路径和至少两个失败路径。

以下内容属于增强路线，可以暂时不勾选：

- [ ] `CreditService` 的完整单元测试和回调幂等测试。
- [ ] Testcontainers MySQL 集成测试。
- [ ] 额度并发扣减测试。
- [ ] Prometheus/Grafana 指标展示。
- [ ] k6 或其他工具压测。
- [ ] 完整部署报告。

## 5. 什么时候算 Part 7 完成

核心路线不要因为“代码能编译”就勾选完成，至少要看到：

```text
AI 出题请求 -> 题目入库            链路成功
GET 题目 -> 不暴露正确答案          返回模型正确
提交答案 -> 判分和解析              复用 Session A
非法 AI JSON -> 统一错误            没有半成品
越权题目访问 -> 统一错误            数据权限成立
```

完成这些后就可以进入 Part 8。B/C 的增强内容不再作为进入 Part 8 的前置条件。

## 6. Session D 的学习方式

Session D 不要求你一次把所有 AI 代码写完。请严格按文档中的“阶段验收”推进：

```text
先确认模型配置
  -> 只调用模型并打印原始文本
  -> 只解析 JSON，不入库
  -> 加入业务校验
  -> 用固定的假数据入库
  -> 接入 Qdrant 检索
  -> 最后接 Controller
```

这样出问题时你能判断是配置、检索、Prompt、JSON 解析，还是数据库事务的问题。不要把这几层一次性写进 Controller，然后用一个“大方法”排查所有错误。
