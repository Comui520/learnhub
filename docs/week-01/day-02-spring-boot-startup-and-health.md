# Day 2：Spring Boot 启动、自动配置与健康检查

## 今天的结果

完成后，应用能以 `dev` 环境启动，可执行 JAR 可以运行，访问 `/actuator/health` 返回 `UP`。

预计用时：3～4 小时。

## 一、原理课

### 1. `@SpringBootApplication` 做了什么

它主要组合了三类能力：

- `@SpringBootConfiguration`：声明这是 Boot 配置入口。
- `@EnableAutoConfiguration`：根据类路径、配置和 Bean 条件启用自动配置。
- `@ComponentScan`：扫描启动类所在包及其子包。

因此启动类放在根包 `com.github.comui520.learnhub`，其他模块也使用这个根包的子包，是有意的架构选择。

### 2. 自动配置不是“自动连接一切”

自动配置通常依据三类条件：

```text
类路径中是否存在某个类
配置中是否存在某个属性
容器中是否已经存在某个 Bean
```

出现启动失败时，先看异常最底层的 `Caused by`，再问：是哪一个 Starter 让这个自动配置生效？

### 3. Spring Boot 启动的大致过程

```text
main 方法
 -> 创建 SpringApplication
 -> 准备 Environment 与 Profile
 -> 创建 ApplicationContext
 -> 扫描组件并执行自动配置
 -> 创建 Bean
 -> 启动内嵌 Tomcat
 -> 发布应用已就绪事件
```

现阶段不要求记住每个事件，但要能区分“编译失败”和“Bean 创建阶段启动失败”。

### 4. Actuator 的角色

Actuator 提供生产诊断端点。`health` 用于回答“应用是否存活/可用”，不是普通业务接口。

本周只暴露：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info
```

不要在公网无保护地暴露 `env`、`beans`、`configprops` 等敏感端点。

## 二、动手任务

### 任务 1：检查启动类

当前启动类已经修正。你需要自己确认：

```java
package com.github.comui520.learnhub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
```

文件路径和 package 必须匹配：

```text
learnhub-application/src/main/java/
└── com/github/comui520/learnhub/LearnHubApplication.java
```

### 任务 2：理解配置分层

保留公共 `application.yml`，创建 `application-dev.yml`。

建议分工：

```text
application.yml       所有环境共享的应用名、通用配置
application-dev.yml   本地开发端口、日志级别、本地服务地址
环境变量              密码、Secret、API Key
```

不要在公共配置里固定激活 `dev`。运行时显式选择 Profile，能减少误把开发配置用于生产的风险。

### 任务 3：第一次启动

方法 A：在 IDEA 中直接运行 `LearnHubApplication.main()`。

方法 B：构建并运行可执行 JAR：

```powershell
cd D:\LearnHub\LearnHubBackend
mvn -pl learnhub-application -am package -DskipTests
java -jar .\learnhub-application\target\learnhub-application-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
```

观察日志，至少找到：

- 激活的 Profile。
- Tomcat 端口。
- 应用启动耗时。
- 是否存在默认 Security 密码或外部服务连接错误。

如果出现数据库、Security、Qdrant 或模型配置错误，回到 Day 1 查依赖树并移除当前不需要的运行时依赖。不要先堆积 `exclude`。

### 任务 4：访问健康检查

另开一个 PowerShell：

```powershell
curl.exe http://localhost:8080/actuator/health
```

期望：

```json
{"status":"UP"}
```

再访问一个不存在的地址，观察 HTTP 404：

```powershell
curl.exe -i http://localhost:8080/not-found
```

重点观察 HTTP 状态行，不要只看响应 JSON。

### 任务 5：测试环境变量覆盖

在 PowerShell 临时指定端口：

```powershell
$env:SERVER_PORT="8081"
java -jar .\learnhub-application\target\learnhub-application-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
Remove-Item Env:SERVER_PORT
```

确认健康检查改为 `http://localhost:8081/actuator/health`。理解环境变量为何能覆盖 YAML 配置。

## 三、排错方法

按这个顺序阅读启动错误：

1. 找第一段 `APPLICATION FAILED TO START`。
2. 看 `Description` 和 `Action`。
3. 从最底部向上找最具体的 `Caused by`。
4. 判断错误发生在编译、配置绑定、Bean 创建还是端口监听阶段。
5. 用 `dependency:tree` 找到触发该自动配置的 Starter。

不要只截图红色最后一行；复制从错误开始到最底层 `Caused by` 的完整文本。

## 四、常见错误

### `Port 8080 was already in use`

说明端口被其他进程占用。可以停止旧进程，或临时使用 `SERVER_PORT=8081`。不要随意结束不认识的系统进程。

### `Unable to find main class`

确认启动模块启用了 `spring-boot-maven-plugin`，并确认 main class 位于规范包路径。

### 健康检查返回 401

说明 Security 进入了运行时类路径。第一周应检查是否过早组合了 `learnhub-user`，而不是直接关闭所有安全过滤器并忘记恢复。

## 五、验收

- [ ] Maven 构建成功并生成可执行 JAR。
- [ ] 使用 `dev` Profile 启动成功。
- [ ] `/actuator/health` 返回 HTTP 200 和 `UP`。
- [ ] 不存在默认 Security 密码提示。
- [ ] 不依赖当前尚未使用的数据库、MQ 或 AI 服务即可启动。
- [ ] 能用环境变量修改端口。

## 六、口头复盘题

1. `@SpringBootApplication` 为什么建议放在根包？
2. 自动配置依据哪些条件生效？
3. 为什么添加一个 Starter 可能导致完全没有调用过的外部服务影响启动？
4. Actuator health 与自己写一个 `/ping` 有什么不同？
5. 编译错误和 Spring Bean 创建失败分别发生在哪个阶段？

## 七、今日提交建议

```text
feat: bootstrap application with actuator health check
```

完成后，把启动日志关键部分、health 响应和复盘题答案发给老师。

