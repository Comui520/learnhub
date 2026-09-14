# LearnHub 第一周零基础跟练课

> 适合对象：会一点 Java 语法，但几乎没有实际项目开发经验。  
> 项目目录：`D:\LearnHub\LearnHubBackend`  
> 本周目标：亲手完成一个可以构建、启动、访问、校验参数、统一处理错误、自动测试并展示接口文档的 Spring Boot 工程骨架。

## 1. 这套课会怎样教你

你指出得很对：只告诉你“添加统一响应”“接入 Validation”，并不能让你学会，因为初学者还不知道这些名词解决什么问题，也不知道代码应该放在哪。

从这一版开始，每天都按同一个顺序教学：

1. **先建立直觉**：用白话和例子解释新概念解决什么问题。
2. **再认识语法**：解释关键注解、类和 Maven 配置，不把代码当咒语。
3. **跟着做一遍**：给出准确路径、完整代码和执行命令。
4. **观察结果**：告诉你成功时应该看到什么，而不只是说“运行一下”。
5. **主动制造错误**：亲眼看到错误，再学习如何读日志和定位。
6. **独立做小练习**：确认你能迁移知识，不只是复制。
7. **对照参考答案**：练习后检查理解，不需要去外部搜索很久。

完整代码不是让你无脑复制。正确用法是：先读解释，自己敲一遍；每完成一个类就停下来回答“这个类的职责是什么”。

## 2. 你和老师怎样配合

每天只做一个 Day。完成后将以下内容发给我：

```text
Day：Day X
完成到：第 X 节 / 第 X 步
执行的命令：...
期望结果：...
实际结果：...
完整报错：...
我对复盘题的回答：...
```

我会做三件事：

- 检查代码是否真的满足当天目标。
- 指出“能运行但设计不正确”的地方。
- 根据你的回答判断哪些概念需要重新讲，而不是直接让你进入下一天。

当你发报错时，请尽量发文本，不要只截最后一行。Java/Spring 的真正根因经常在最下面一段 `Caused by` 中。

## 3. 目前项目处于哪里

我在 2026-07-20 检查到：

- [x] 使用 JDK 21。
- [x] 使用 Maven 3.9.11。
- [x] Maven 多模块编译通过。
- [x] 启动类路径和 package 已正确。
- [x] 已正确导入 `SpringApplication` 和 `SpringBootApplication`。
- [x] `learnhub-application` 已有 Web Starter 和 Boot Maven 插件。
- [x] 已创建基础 `application.yml`。
- [ ] Git 尚未初始化。
- [ ] 启动模块仍组合了很多未来模块，会传递引入数据库、Security、AI 等自动配置。
- [ ] 尚未实际证明应用能启动并返回健康状态。
- [ ] 尚未实现统一响应、异常处理、示例接口和测试。
- [ ] 尚未创建 Docker Compose。

所以你不是从空文件夹开始，但仍从 Day 1 学起。已经写好的代码也要能解释。

## 4. 七天课程目录

| 天数 | 今天从零学什么 | 完成后你能做到什么 |
|---|---|---|
| [Day 1](day-01-project-baseline-and-maven.md) | 目录、终端、Git、POM、Maven 生命周期与依赖 | 看懂基本 POM，建立 Git，完成干净构建 |
| [Day 2](day-02-spring-boot-startup-and-health.md) | main 方法、注解、包扫描、自动配置、Profile、Actuator | 启动可执行 JAR，并自己定位常见启动错误 |
| [Day 3](day-03-java-generics-errors-and-api-response.md) | 泛型、接口、枚举、record、异常、Instant、JUnit | 写出统一响应和业务错误模型，而不是只复制代码 |
| [Day 4](day-04-mvc-validation-and-exception-handler.md) | HTTP、JSON、Controller、DTO、Service、Validation、Advice | 完成正常/参数错误/业务错误三条接口路径 |
| [Day 5](day-05-web-tests-and-openapi.md) | 自动化测试、Mock、MockMvc、JSONPath、OpenAPI | 为接口写可靠测试，并能阅读 Swagger 文档 |
| [Day 6](day-06-docker-compose-and-dev-config.md) | 镜像、容器、端口、卷、健康检查、环境变量 | 用一份 Compose 启动五个基础组件并排错 |
| [Day 7](day-07-architecture-review-and-acceptance.md) | 模块化单体、依赖方向、README、完整验收 | 向别人讲清项目骨架并独立从零启动它 |

## 5. 每天需要多长时间

按初学者速度估算，每天 4～6 小时比较合理：

```text
阅读与跟练          90～120 分钟
自己重新实现        90～150 分钟
测试和故障练习      45～60 分钟
复盘题与笔记        30～45 分钟
```

如果一天做不完，可以拆成两天。七天是课程结构，不是必须赶完的行政期限。理解优先于打勾。

## 6. 阅读代码时必须养成的习惯

看到任何一段新代码，都问这五个问题：

1. 这个类/注解来自 JDK、Spring，还是我们自己的项目？
2. 它的输入是什么？
3. 它的输出或副作用是什么？
4. 如果删掉它，编译时还是运行时出错？
5. 为什么它放在这个模块、这个包，而不是其他地方？

例如看到：

```java
@SpringBootApplication
```

不能只记住“启动类要加它”，还要知道它来自 Spring Boot、会触发配置和组件扫描、删除后代码可能仍能编译但应用不会按预期装配。

## 7. 本周使用的固定约定

- Java 根包：`com.github.comui520.learnhub`。
- HTTP API 前缀：`/api/v1`。
- 使用 Spring Boot 3.5.16，因此导入 `jakarta.*`，不是旧版 `javax.*`。
- 使用 Java 21，可以使用 `record`、泛型和现代日期时间 API。
- Controller 只处理 Web 边界，不堆业务逻辑。
- HTTP 状态码与业务错误码都要正确表达错误。
- 密码、Token、API Key、真实 `.env` 不提交到 Git。
- 每天完成一次能描述真实改动的 Git 提交。

## 8. 本周不会提前做什么

本周不实现 JWT、数据库 CRUD、Redis 缓存、RabbitMQ 消费、文件上传和 RAG。原因不是这些不重要，而是你还没有搭好承载它们的工程骨架。

学习顺序是：

```text
先让一个请求正确进入和离开系统
 -> 再增加数据库
 -> 再增加身份和权限
 -> 再增加外部系统和异步流程
```

## 9. 第一周最终验收

- [ ] `git status` 干净且没有敏感文件。
- [ ] `mvn clean verify` 全部成功。
- [ ] 可执行 JAR 使用 `dev` Profile 启动。
- [ ] `/actuator/health` 返回 HTTP 200 和 `UP`。
- [ ] 示例接口能返回成功、参数错误、业务错误。
- [ ] 未知异常不会把堆栈暴露给客户端。
- [ ] 自动化测试覆盖主要路径。
- [ ] Swagger UI 可以展示并调用接口。
- [ ] Docker Compose 五个组件正常启动。
- [ ] README 足够让另一台电脑的开发者启动项目。
- [ ] 你能回答 Day 7 的答辩题，而不是只说“框架就是这样写”。

现在从 [Day 1](day-01-project-baseline-and-maven.md) 开始。不要同时打开七份文档赶进度。

