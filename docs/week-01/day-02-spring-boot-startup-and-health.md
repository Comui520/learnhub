# Day 2：让 Spring Boot 真正启动——包扫描、自动配置、Profile 与 Health

## 0. 今天到底要学会什么

Day 1 证明“代码能被 Maven 编译”。Day 2 要证明“程序真的能运行并接收 HTTP 请求”。这不是同一件事。

完成后，你应该能够：

1. 逐行解释启动类。
2. 知道注解不是注释，而是框架会读取的元数据。
3. 理解为什么启动类放在根包。
4. 初步理解 Spring 容器、Bean、组件扫描和自动配置。
5. 分清公共配置、开发配置和环境变量。
6. 构建并运行可执行 JAR。
7. 使用浏览器和 curl 验证健康检查。
8. 按固定顺序阅读常见启动错误。

预计时间：5～6 小时。

---

## 1. 从普通 Java main 方法开始

当前启动类位于：

```text
D:\LearnHub\LearnHubBackend\learnhub-application\src\main\java\com\github\comui520\learnhub\LearnHubApplication.java
```

代码应为：

```java
package com.github.comui520.learnhub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LearnHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(LearnHubApplication.class, args);
    }
}
```

### 1.1 `package` 是什么

```java
package com.github.comui520.learnhub;
```

package 用来组织类和避免重名。文件路径通常与包名一一对应：

```text
com.github.comui520.learnhub
   ↓ 把点换成目录分隔符
com/github/comui520/learnhub
```

不要使用默认包。默认包中的类难以被其他有包名的代码引用，也会让 Spring 扫描范围失控。

### 1.2 `import` 是什么

```java
import org.springframework.boot.SpringApplication;
```

Java 类真正的完整名称是 `org.springframework.boot.SpringApplication`。import 让本文件后面可以写简称 `SpringApplication`。

IDEA 之前显示“无法解析 SpringBootApplication”，意思不是注解拼错了，而是 IDE 在当前模块的类路径里找不到这个完整类。Day 1 已通过实际依赖解决。

### 1.3 `public static void main`

这是 JVM 运行普通 Java 程序时寻找的入口：

- `public`：JVM 可以从类外调用。
- `static`：不需要先创建 `LearnHubApplication` 对象。
- `void`：方法不返回值。
- `String[] args`：接收命令行参数，例如 `--spring.profiles.active=dev`。

### 1.4 `SpringApplication.run`

```java
SpringApplication.run(LearnHubApplication.class, args);
```

这行代码不是单纯“启动 Tomcat”。它会：

1. 创建和准备 Spring 应用。
2. 读取命令行参数、环境变量和 YAML。
3. 创建 Spring ApplicationContext，也就是常说的 Spring 容器。
4. 扫描和创建 Bean。
5. 执行符合条件的自动配置。
6. 因为 Web Starter 存在，创建内嵌 Tomcat。
7. 监听端口，等待 HTTP 请求。

`LearnHubApplication.class` 告诉 Spring 从哪个主配置类开始。

---

## 2. 注解、容器和 Bean

### 2.1 注解不是普通注释

Java 注释：

```java
// 这是给人看的，编译器通常忽略
```

Java 注解：

```java
@SpringBootApplication
```

注解是结构化元数据。Spring 在运行时读取它，决定如何配置应用。

### 2.2 什么是 Spring 容器

没有 Spring 时，你经常自己创建对象：

```java
DemoService service = new DemoService();
DemoController controller = new DemoController(service);
```

使用 Spring 后，框架负责创建、保存和连接这些对象。这个管理对象的环境叫 Spring 容器或 ApplicationContext。

被 Spring 创建和管理的对象叫 Bean。

优点：

- 对象创建与业务使用分离。
- 依赖关系集中管理。
- 测试时可以替换依赖。
- 框架可以在 Bean 周围增加事务、安全、监控等能力。

第二周会深入 IOC 和依赖注入。今天先建立这个直觉。

### 2.3 `@SpringBootApplication` 的三项核心作用

它是组合注解，核心包含：

```text
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan
```

分别理解：

1. **Boot 配置入口**：声明这个类是应用主配置。
2. **自动配置**：根据依赖、属性和已有 Bean，配置常见能力。
3. **组件扫描**：从启动类所在包向下寻找 Spring 组件。

---

## 3. 为什么启动类必须位于根包

你的启动类 package 是：

```text
com.github.comui520.learnhub
```

以后组件会位于：

```text
com.github.comui520.learnhub.demo
com.github.comui520.learnhub.user
com.github.comui520.learnhub.knowledge
```

它们都是根包的子包，所以默认扫描可以找到。

如果启动类放在：

```text
com.github.comui520.learnhub.application
```

默认扫描通常只向下扫描 `application.*`，与它平级的 `user.*` 可能不会被发现。表现是代码编译成功，但访问 Controller 返回 404，或注入 Bean 失败。

组件扫描与 Maven 模块不是一回事：

- Maven 模块决定 JAR 和编译依赖。
- Java package 决定类的命名空间。
- Spring 组件扫描依据 package。

只要某个模块的 JAR 在运行类路径中，而且组件位于扫描包下，Spring 就可以发现它。

---

## 4. 自动配置到底“自动”在哪里

### 4.1 三种常见条件

Spring Boot 自动配置经常检查：

1. 类路径有没有某个类。
2. 配置文件有没有某项属性。
3. 容器中有没有用户自己定义的 Bean。

例子：

```text
类路径有 spring-webmvc + Tomcat
 -> Boot 判断这是 Servlet Web 应用
 -> 配置 DispatcherServlet 和内嵌 Tomcat
```

```text
类路径有数据库驱动 + JDBC Starter
 -> Boot 尝试配置 DataSource
 -> 如果没有 URL 或嵌入式数据库，可能启动失败
```

这就是 Day 1 依赖瘦身的原因。

### 4.2 自动配置不是魔法

它是大量带条件的普通 Java 配置。以后想调查为什么某配置生效，可以临时使用：

```text
--debug
```

例如：

```powershell
java -jar .\learnhub-application\target\learnhub-application-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev --debug
```

它会输出 Condition Evaluation Report，内容很多。今天只知道它能说明哪些自动配置匹配或没有匹配，不需要逐条读完。

---

## 5. 配置文件：YAML 与 Profile

### 5.1 YAML 基本规则

YAML 用缩进表示层级：

```yaml
spring:
  application:
    name: learnhub
```

等价思想：`spring.application.name=learnhub`。

注意：

- 使用空格，不使用 Tab。
- 同一层级缩进数量一致，通常两个空格。
- 冒号后要有空格。
- 大小写和拼写必须正确。

### 5.2 公共 `application.yml`

文件路径：

```text
learnhub-application/src/main/resources/application.yml
```

使用下面内容：

```yaml
spring:
  application:
    name: learnhub

management:
  endpoints:
    web:
      exposure:
        include: health,info
```

逐项解释：

- `spring.application.name`：应用名，未来日志和指标会使用。
- `management.endpoints.web.exposure.include`：允许通过 HTTP 暴露哪些 Actuator 端点。
- `health`：健康状态。
- `info`：应用信息；没有额外信息时可能返回空对象。

### 5.3 开发环境 `application-dev.yml`

在同一目录创建：

```text
application-dev.yml
```

内容：

```yaml
server:
  port: 8080

logging:
  level:
    com.github.comui520.learnhub: DEBUG
```

解释：

- `server.port`：内嵌 Tomcat 监听端口。
- `logging.level.包名`：开发环境对自己项目的日志使用 DEBUG。

暂时不要把 `spring.profiles.active: dev` 固定写进公共配置。原因是同一 JAR 以后可能运行在测试或生产环境，环境应由启动者选择。

### 5.4 如何激活 Profile

命令行：

```text
--spring.profiles.active=dev
```

环境变量：

```powershell
$env:SPRING_PROFILES_ACTIVE="dev"
```

IDEA：Run Configuration 中把 Active profiles 设为 `dev`，或 Program arguments 加相同参数。

配置优先级的完整规则很多。今天记住：命令行和环境变量通常可以覆盖 YAML，适合部署时注入差异配置。

---

## 6. Actuator 与健康检查

### 6.1 为什么不用自己写 `/ping`

你当然可以写一个返回字符串的 Controller，但 Actuator health 有更完整的扩展机制：以后可组合数据库、Redis、磁盘空间等健康贡献者，并被监控系统识别。

本周没有接数据库时，health 主要证明：

- Spring Context 成功创建。
- Web 服务器正在监听。
- Actuator Endpoint 已注册。

### 6.2 为什么只暴露 health 和 info

Actuator 还有 `env`、`beans`、`configprops` 等诊断端点，可能暴露配置和内部结构。开发中也应从最小暴露开始，未来配合 Security 管理。

---

## 7. 构建可执行 JAR

### 7.1 Boot Maven 插件作用

普通 Java JAR 不一定包含依赖，也不一定知道 main class。`spring-boot-maven-plugin` 的 repackage 目标会生成可执行 Boot JAR，把依赖按 Boot 结构打包，并写入启动信息。

启动模块 POM 中应有：

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
        </plugin>
    </plugins>
</build>
```

插件版本由 Spring Boot Parent 管理，不需要重复写。

### 7.2 从根目录构建

先确认目录：

```powershell
Set-Location D:\LearnHub\LearnHubBackend
```

执行：

```powershell
mvn -pl learnhub-application -am clean package
```

解释：

- `-pl learnhub-application`：选择启动模块。
- `-am`：同时构建它所依赖的 Reactor 模块，例如 common。
- `clean package`：删除旧产物、编译、测试、打包。

成功后查看：

```powershell
Get-ChildItem .\learnhub-application\target\
```

应有类似：

```text
learnhub-application-0.0.1-SNAPSHOT.jar
learnhub-application-0.0.1-SNAPSHOT.jar.original
```

- `.jar`：Boot 插件重新打包后的可执行 JAR。
- `.jar.original`：重新打包前的普通 JAR。

---

## 8. 启动应用

执行：

```powershell
java -jar .\learnhub-application\target\learnhub-application-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
```

终端会被应用占用，这是正常的。不要立刻关闭。

成功日志通常包含：

```text
The following 1 profile is active: "dev"
Tomcat started on port 8080 (http)
Started LearnHubApplication in ... seconds
```

不同版本文字可能稍有差异，关键是：Profile、端口、Started。

如果日志显示：

```text
Using generated security password
```

说明 Security 仍通过某条依赖路径进入启动模块，回到 Day 1 查看 dependency tree。

如果看到 DataSource、Qdrant、EmbeddingModel 等 Bean 错误，也说明未来依赖仍在运行类路径。

---

## 9. 发送第一个 HTTP 请求

### 9.1 什么是 HTTP 请求和响应

浏览器或 curl 是客户端，Spring Boot 是服务器。

请求包含：

- 方法：GET、POST 等。
- 地址：如 `/actuator/health`。
- Header。
- 可选 Body。

响应包含：

- 状态码：200、404、500 等。
- Header。
- Body。

### 9.2 使用 curl

保持应用终端运行，另开一个 PowerShell：

```powershell
curl.exe -i http://localhost:8080/actuator/health
```

这里明确使用 `curl.exe`，避免 PowerShell 某些版本把 `curl` 解析成其他命令别名。

`-i` 表示把响应头也打印出来。成功应类似：

```text
HTTP/1.1 200
Content-Type: application/vnd.spring-boot.actuator.v3+json
...

{"status":"UP"}
```

你需要同时观察：

- HTTP 状态是 200。
- Body 中状态是 UP。

### 9.3 制造 404

```powershell
curl.exe -i http://localhost:8080/does-not-exist
```

应该看到 HTTP 404。它证明服务器收到了请求，但没有找到对应处理器。404 和“无法连接”不同。

### 9.4 停止应用

回到运行应用的终端，按：

```text
Ctrl + C
```

这会向进程发送中断信号。确认终端重新出现 PowerShell 提示符。

---

## 10. 环境变量覆盖实验

在 PowerShell 中：

```powershell
$env:SERVER_PORT="8081"
java -jar .\learnhub-application\target\learnhub-application-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
```

虽然 `application-dev.yml` 写了 8080，环境变量应将其覆盖为 8081。

另开终端验证：

```powershell
curl.exe -i http://localhost:8081/actuator/health
```

停止应用后清除当前终端的临时变量：

```powershell
Remove-Item Env:SERVER_PORT
```

如果不清除，同一 PowerShell 窗口后续启动仍会使用 8081。

---

## 11. 学会读启动错误

Spring 错误日志经常很长。固定按以下顺序：

1. 找 `APPLICATION FAILED TO START`。
2. 阅读它下面的 `Description`。
3. 阅读 `Action`，它是框架建议，不一定是最终方案。
4. 向上或向最底部寻找最具体的 `Caused by`。
5. 判断属于哪一类：编译、配置、Bean 创建、Web 端口、外部连接。

### 错误 A：端口被占用

典型信息：

```text
Web server failed to start. Port 8080 was already in use.
```

含义：应用已经走到启动 Web Server，但操作系统不允许两个进程监听同一个地址端口。

解决：

- 检查是否有前一次应用没停。
- 临时使用 `SERVER_PORT=8081`。
- 不要随便结束不认识的系统进程。

### 错误 B：无法配置 DataSource

典型信息：

```text
Failed to configure a DataSource: 'url' attribute is not specified
```

含义：数据库相关自动配置被激活，但你尚未提供连接信息。

本周正确解决：用 dependency tree 找出谁带入 JDBC/MyBatis/Flyway，并移走未来依赖。不要先在启动类加一串自动配置 exclude。

### 错误 C：401 Unauthorized

应用已启动，但 health 返回 401，并可能在日志看到默认密码。说明 Security Starter 仍存在。回 Day 1 检查 `learnhub-user` 是否仍由启动模块组合。

### 错误 D：Connection refused

curl 显示无法连接，而不是 HTTP 404：

```text
Failed to connect to localhost port 8080
```

说明该端口没有服务器监听。检查：

- 应用是否真的还在运行。
- 日志端口是否是 8080。
- 是否被环境变量改成 8081。
- 应用是否启动失败后已经退出。

### 错误 E：YAML 解析失败

常因 Tab、缩进或冒号格式。逐行检查缩进，IDEA 通常会标出位置。不要把整个文件改成单行来绕过。

---

## 12. 今天的独立练习

### 练习 1：新增 info 信息

查阅这里给出的示例即可，不需要外部搜索。在 `application.yml` 添加：

```yaml
info:
  app:
    name: LearnHub
    description: AI knowledge base and learning platform
```

启动后访问：

```powershell
curl.exe -i http://localhost:8080/actuator/info
```

如果 Boot 默认不显示自定义 info，添加：

```yaml
management:
  info:
    env:
      enabled: true
```

注意它应与 `management.endpoints` 同级，不能错误地嵌套到 `exposure` 下。

### 练习 2：观察 8080 与 8081

用 YAML 启动一次，再用环境变量覆盖启动一次。把两次启动日志的 Tomcat 端口行保存到学习笔记，并解释哪个配置优先。

### 练习 3：故意制造一次端口冲突

让第一个应用运行在 8080，再开第二个终端运行同一个 JAR，不设置新端口。阅读失败日志，然后停止第二次失败的进程，确保第一个仍可健康检查。

---

## 13. 今日验收清单

- [ ] 我能逐行解释启动类。
- [ ] 我知道 Bean 是 Spring 管理的对象。
- [ ] 我知道启动类为什么放根包。
- [ ] `application.yml` 与 `application-dev.yml` 职责分开。
- [ ] `mvn -pl learnhub-application -am clean package` 成功。
- [ ] target 中存在可执行 JAR。
- [ ] JAR 使用 dev Profile 启动成功。
- [ ] health 返回 HTTP 200 和 UP。
- [ ] 不存在默认 Security 密码和未使用外部系统导致的启动失败。
- [ ] 环境变量可以把端口覆盖为 8081。
- [ ] 我亲手制造并读懂一次端口冲突。

---

## 14. 复盘题：先自己回答

1. 注解和普通注释有什么区别？
2. 什么是 Spring 容器，什么是 Bean？
3. `SpringApplication.run` 大致完成哪些事情？
4. `@SpringBootApplication` 的三项核心能力是什么？
5. 为什么启动类放在 `com.github.comui520.learnhub`？
6. Maven 编译成功为什么不能证明应用一定能启动？
7. `application.yml` 与 `application-dev.yml` 如何分工？
8. health 返回 404、401、无法连接，分别说明什么？
9. 为什么本周不建议用 exclude 掩盖数据库自动配置错误？
10. `spring-boot-maven-plugin` 对最终 JAR 做了什么？

## 15. 参考答案

1. 注释主要给人阅读；注解是结构化元数据，可被编译器或框架读取并影响行为。
2. Spring 容器负责创建、保存和连接应用对象；被容器管理的对象叫 Bean。
3. 它读取环境与参数、创建容器、扫描组件、执行自动配置、创建 Bean，并在 Web 应用中启动内嵌服务器。
4. Boot 配置入口、自动配置、组件扫描。
5. 默认组件扫描从启动类所在包向下进行；放根包能覆盖所有业务子包，包括其他 Maven 模块中的类。
6. 编译只证明语法和编译类路径成立；启动还要完成配置绑定、Bean 创建、端口监听和可能的外部连接。
7. 公共文件保存各环境共享配置；dev 文件保存本地开发差异，启动时显式激活。
8. 404 表示服务器可达但没有这个处理器；401 表示请求被安全机制拒绝；无法连接表示端口没有服务监听或地址/端口错误。
9. 当前根因是过早激活依赖。排除自动配置会把错误隐藏，未来容易忘记恢复，也让依赖边界继续混乱。
10. 它重新打包普通 JAR，加入依赖和启动元数据，使其能通过 `java -jar` 运行。

完成后交给老师：启动日志中的 Profile/端口/Started 三行、health 完整响应、端口冲突的关键错误，以及十道题自己的答案。

