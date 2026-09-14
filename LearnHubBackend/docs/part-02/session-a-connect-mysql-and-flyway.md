# Session A：把 MySQL 接进来——数据源、Flyway 与第一张用户表

> 目标：让应用能连上 Docker Compose 里的 MySQL，用 Flyway 自动建出第一张 `user` 表，并亲眼看到迁移日志和表结构。
>
> 预计时间：2～3 小时。

## 0. 今天到底要学会什么

1. 为什么业务数据要存进数据库，而不是放在内存里。
2. 数据源（DataSource）是什么，Spring Boot 怎么配置它。
3. Flyway 为什么能把“表结构”也当成代码来管理。
4. 为什么把 `learnhub-user` 模块接进应用后，Spring Security 会“突然出现”，以及怎么处理。

完成本 Session 后，你的应用启动时会自动在 MySQL 里建好用户表，你能在 MySQL 里查到它。

---

## 1. 先建立直觉：为什么需要数据库和表结构管理

### 1.1 数据放内存会发生什么

现在你的项目里没有任何持久化：重启应用，一切归零。真实系统里用户、文档、订单都是要“活过重启”的数据，所以必须落盘。数据库（MySQL）就是干这个的。

### 1.2 表结构也要“版本管理”

代码有 Git 管理版本，数据库的表结构同样会演进：今天建用户表，明天加一列 `nickname`，后天加角色表。如果靠人手工在 MySQL 里敲 `CREATE TABLE`，会出现三个问题：

- 换一台电脑，表没了，还得重新敲。
- 加了字段之后，别人不知道要执行哪条 SQL。
- 生产环境和本地环境结构不一致。

Flyway 解决这个问题：**迁移文件像代码一样按版本号排列，应用启动时自动执行没执行过的文件，并把执行记录写进一张历史表**。以后谁的机器上跑一次应用，表结构就到位了。

### 1.3 连接数据库需要什么

Java 程序连 MySQL 需要三样东西：

1. **JDBC 驱动**：`mysql-connector-j`，负责把 Java 的数据库操作翻译成 MySQL 协议。
2. **连接信息**：地址、端口、库名、用户名、密码。
3. **连接池**：每次操作都新建连接太慢，Spring Boot 默认用 HikariCP 维护一批复用连接。

Spring Boot 里，这三样东西合在一起叫“数据源自动配置”：你只要把连接信息写进 `spring.datasource.*`，Boot 自动创建 `DataSource` Bean。

---

## 2. 先认识几个关键概念

### 2.1 `spring.datasource.*`

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/learnhub?...
    username: learnhub
    password: change-me-mysql
    driver-class-name: com.mysql.cj.jdbc.Driver
```

- `url` 里的 `jdbc:mysql://` 是 JDBC 驱动识别协议；`localhost:3306` 是 MySQL 地址和端口；`learnhub` 是库名。
- `username` / `password` 对应 Docker Compose 里创建的账号。
- `driver-class-name` 写 MySQL 8 的新驱动类；其实驱动能自己识别，写上更明确。

### 2.2 Flyway 迁移文件命名

```text
V1__init_user_tables.sql
V2__init_role_tables.sql
```

规则：`V` + 版本号 + **两个下划线** + 描述。只认这个格式；执行过的版本记录在 `flyway_schema_history` 表里，**已经执行过的文件绝对不要改内容**，要改就新建更高版本的迁移文件。

> 常见坑：写成一个下划线 `V1_init.sql`，Flyway 会直接忽略它，表不会建，而且不报错——这是本 Session 要你亲手踩的坑。

### 2.3 Compose 的 `.env` 不会自动给 Java 进程

`.env` 文件是 **Docker Compose 自己读取**的。你启动 Java 应用时，Spring Boot 不会去读 `.env`。所以要么在 Java 进程的环境变量里设置，要么在配置里写默认值。本 Session 的配置会写成“默认值和环境变量二选一”的形式，让你理解这两种来源。

### 2.4 MyBatis-Plus 是干嘛的（先认识，Session B 才写代码）

它让我们少写大量重复 SQL：一个实体类 + 一个 Mapper 接口，就自带增删改查。复杂 SQL 仍然手写。本 Session 只需要它在类路径上。

### 2.5 你 POM 里那些“预置依赖”都是干嘛的

搭骨架时很多依赖是提前放进 POM 的，所以你不认识它们很正常。版本统一由两个地方管：**Spring Boot BOM**（`spring-boot-starter-parent` 里的 `<dependencyManagement>`）管 Spring 全家桶；**父 POM 的 `dependencyManagement`** 管第三方库（比如 jjwt 0.13.0、spring-ai 1.1.8）。这就是为什么子模块 POM 里多数依赖不用写版本号。

| 依赖 | 是什么 | 解决什么问题 | 第一次真正用到 |
|---|---|---|---|
| `spring-boot-starter-security` | Spring Security 的自动配置集合 | 认证与授权（登录、权限、过滤器链） | Session B |
| `mybatis-plus-spring-boot3-starter` | MyBatis-Plus 的 Boot 3 启动器 | 少写重复 SQL：实体 + Mapper 自带 CRUD | Session B |
| `spring-boot-starter-validation` | Jakarta Validation | `@NotBlank`、`@Size`、`@Pattern` 参数校验 | Session B（Part 1 demo 已用过） |
| `org.projectlombok:lombok` | 编译期样板代码生成器 | 少写 getter/setter | Session B |
| `io.jsonwebtoken:jjwt-api/impl/jackson` | JWT 签发与解析库 | 登录后签发、校验 token | Session C |
| `com.mysql:mysql-connector-j` | MySQL 的 JDBC 驱动 | Java 程序与 MySQL 通信 | 本 Session |
| `org.flywaydb:flyway-core` + `flyway-mysql` | 数据库迁移工具 | 表结构版本化管理、自动执行 | 本 Session |

依赖的写法就是在 `<dependencies>` 里加坐标，比如 Flyway：

```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
```

几个常见 `scope` 先认识一下（Session C 的 jjwt 会用到）：

- 不写（默认 compile）：编译和运行都需要，会传递给下游模块。
- `runtime`：编译不需要、运行需要（如 JDBC 驱动、实现类）。
- `test`：只在测试时有效（如 `spring-boot-starter-test`）。
- `optional`：编译需要但不传递给下游（如 Lombok）。

---

## 3. 跟着做一遍

### Step 0：检查 Docker 并启动 MySQL

打开 Docker Desktop（如果没启动，先启动它并等 Engine running），然后：

```powershell
cd D:\LearnHub\LearnHubBackend
docker compose up -d
docker compose ps
```

期望看到 MySQL 的 `STATUS` 是 `healthy`（首次启动镜像下载可能要几分钟）。如果报“无法连接 docker daemon”，说明 Docker Desktop 没起来，先解决它再继续。

查看 MySQL 是否就绪：

```powershell
docker compose logs mysql --tail 20
```

> 注意：如果你在 `.env` 里改过密码，后面的命令和配置都要用你自己的密码，不要照抄 `change-me-mysql`。

### Step 1：修改 `learnhub-user/pom.xml`

先看现在的 POM：它已经预置了 Security、MyBatis-Plus、JJWT、Lombok，但**还缺数据库驱动、Flyway 和 Validation**，并且依赖了 `learnhub-infrastructure`。

把整个文件替换成下面这份（改动点都有注释）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.github.comui520</groupId>
        <artifactId>learnhub</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>

    <artifactId>learnhub-user</artifactId>
    <name>learnhub-user</name>

    <dependencies>
        <dependency>
            <groupId>com.github.comui520</groupId>
            <artifactId>learnhub-common</artifactId>
        </dependency>

        <!-- 1) 暂时去掉 learnhub-infrastructure 依赖 -->
        <!-- 原因：infrastructure 带着 Redis/AMQP/MinIO/Qdrant 的自动配置，
             现在一个都用不上。按需接入，等 Part 4 需要 RabbitMQ 时再加回来。 -->

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
        </dependency>

        <!-- 2) MySQL 驱动：运行时才需要 -->
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- 3) Flyway：管理数据库表结构；Boot 3.5 的 BOM 已统一版本 -->
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-mysql</artifactId>
        </dependency>

        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
            <scope>runtime</scope>
        </dependency>

        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

回答自己两个问题：

1. 为什么 `mysql-connector-j` 和 `flyway-mysql` 一个用 `runtime` 作用域、一个不用？
2. 为什么 `flyway-core` 不需要写版本号？（提示：父 POM 的 `dependencyManagement` 和 Boot BOM）

> 面试点：把“为什么暂时不依赖 infrastructure”讲成“按需接入，避免未使用的 Starter 触发自动配置，增加启动变量”，正好呼应 Part 1 Day 7 的内容。

### Step 2：让应用模块依赖 `learnhub-user`

编辑 `learnhub-application/pom.xml`，在 `<artifactId>learnhub-application</artifactId>`（**保持这个 artifactId 不变**）的 `learnhub-common` 依赖下面，**只加这一个依赖块**：

```xml
<dependency>
    <groupId>com.github.comui520</groupId>
    <artifactId>learnhub-user</artifactId>
</dependency>
```

⚠️ 注意：**绝对不要把 `learnhub-user/pom.xml` 的整个内容复制过来**。Step 1 里那份完整 POM 是给 user 模块自己用的；application 模块只负责“组合”，它需要的依赖（Security、MyBatis-Plus、Flyway、迁移文件）会通过 `learnhub-user` 这个依赖**传递**过来。

常见错误：把 application 的 `<artifactId>` 也改成了 `learnhub-user`，结果 Maven 报 “referencing itself”——自己依赖自己，构建直接失败。改完记得检查 application POM 的 artifactId 还是 `learnhub-application`。

现在应用运行时会带上 user 模块的代码、配置和迁移文件。

### Step 3：配置数据源

编辑 `learnhub-application/src/main/resources/application-dev.yml`，在末尾追加：

```yaml
spring:
  datasource:
    url: jdbc:mysql://${MYSQL_HOST:localhost}:${MYSQL_PORT:3306}/${MYSQL_DATABASE:learnhub}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
    username: ${MYSQL_USER:learnhub}
    password: ${MYSQL_PASSWORD:change-me-mysql}
    driver-class-name: com.mysql.cj.jdbc.Driver
  flyway:
    enabled: true
```

读法：`${MYSQL_HOST:localhost}` 表示“优先读环境变量 `MYSQL_HOST`，没有就用 `localhost`”。这样你既能用默认值直接跑，也能在正式环境用环境变量覆盖。

url 里的参数逐个搞清楚：

- `useUnicode=true&characterEncoding=utf8`：以 UTF-8 存取中文。
- `serverTimezone=Asia/Shanghai`：让 JDBC 时区和你一致，避免时间差 8 小时。
- `useSSL=false`：本地开发不加密连接。
- `allowPublicKeyRetrieval=true`：MySQL 8 默认认证插件需要的开关，本地开发常用。

### Step 4：写第一个 Flyway 迁移

创建文件：

```text
learnhub-user/src/main/resources/db/migration/V1__init_user_tables.sql
```

内容：

```sql
CREATE TABLE `user`
(
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    username      VARCHAR(30)     NOT NULL COMMENT '登录名',
    password_hash VARCHAR(100)    NOT NULL COMMENT 'BCrypt 哈希后的密码',
    status        TINYINT         NOT NULL DEFAULT 1 COMMENT '账号状态：1 正常，0 禁用',
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='用户表';
```

逐个看懂：

- `id BIGINT UNSIGNED AUTO_INCREMENT`：自增主键，用无符号大整数，够用且常见。
- `username VARCHAR(30)` + `UNIQUE KEY uk_username`：登录名唯一——这是“用户名不能重复”的**数据库层保证**。
- `password_hash`：不叫 `password`，因为我们永远不存明文，只存哈希（Session B 讲 BCrypt）。
- `status`：账号状态，1 正常 0 禁用，先留个口子。
- `created_at / updated_at`：所有表都该有，面试必问。
- `ENGINE=InnoDB`：支持事务和外键；`utf8mb4`：支持完整 Unicode（包括 emoji）。

> 为什么表名 `user` 要加反引号：`user` 在 MySQL 里是关键字相关词，用反引号包起来最保险。这是面试里“表名/字段名怎么规避保留字”的答案。

### Step 5：创建最小 SecurityConfig

这一步不是提前做安全，而是**不得不做**：user 模块带了 `spring-boot-starter-security`，一旦 application 依赖它，Security 自动配置就会生效，默认把所有接口都锁起来（你没配置任何过滤器链时，Spring Boot 会给一个默认链，要求认证）。

创建文件：

```text
learnhub-user/src/main/java/com/github/comui520/learnhub/user/config/SecurityConfig.java
```

```java
package com.github.comui520.learnhub.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 我们做无状态 JWT API，不需要 CSRF（Session C 讲为什么）
                .csrf(AbstractHttpConfigurer::disable)
                // 不创建 Session，保持无状态
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 先全部放行，让骨架跑起来；Session B 开始收紧
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
```

现在先理解三行代码各自干什么，Session B/C 会逐行加深。

### Step 6：跑测试，亲眼看到 Security 带来的变化

```powershell
mvn -pl learnhub-application -am test
```

你会看到 `DemoControllerTest` **失败**，报错类似 401/Unauthorized。这就是 Security 进入类路径后的真实效果：Web 切片测试里也加载了 Security 自动配置，默认要求认证。

修复：在 `DemoControllerTest` 类上、`@WebMvcTest` 下面加一行：

```java
@AutoConfigureMockMvc(addFilters = false)
```

```java
@WebMvcTest(DemoController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
public class DemoControllerTest {
```

解释（能讲出来才算懂）：

- `addFilters = false` 表示 MockMvc 不加载 Servlet 过滤器，包括 Security 的过滤器链。
- 这不是“绕过安全测试”，而是**切片测试的职责划分**：`DemoControllerTest` 只测 Controller 的映射、校验、异常处理；安全过滤器的行为会在 Session C/D 用专门的测试覆盖。

> ⚠️ 如果跑测试时遇到 `Could not self-attach to current VM`（Mockito 初始化失败），这是 JDK 21+ 默认限制动态加载 agent 导致的，和测试代码无关。解决办法是在根 POM 的 `maven-surefire-plugin` 配置里加 `<argLine>-XX:+EnableDynamicAgentLoading</argLine>`（本项目已加好，换新机器时如果报这个错，先检查它）。

再跑一次，应该全绿。

### Step 7：启动应用，观察 Flyway

```powershell
mvn -pl learnhub-application -am spring-boot:run "-Dspring-boot.run.profiles=dev"
```

等几秒，观察日志。你**必须看到**类似这样的一行：

```text
Flyway Community Edition ... has been enabled
Successfully applied 1 migration to schema `learnhub` (execution time ...)
```

如果只看到应用启动、没有迁移日志，说明迁移文件没被找到——回去检查文件名是不是 `V1__init_user_tables.sql`（两个下划线）以及文件路径。

### Step 8：到 MySQL 里验证

另开一个 PowerShell：

```powershell
docker compose exec mysql mysql -ulearnhub -pchange-me-mysql learnhub -e "SHOW TABLES; SHOW COLUMNS FROM user; SELECT * FROM flyway_schema_history;"
```

期望：

- `SHOW TABLES` 里有 `user` 和 `flyway_schema_history`。
- `DESCRIBE user` 显示 6 个字段，和你迁移文件一致。
- `flyway_schema_history` 里有一条记录：version = 1，success = 1。

> ⚠️ 不要写 `DESCRIBE \`user\``：在 PowerShell 和 sh 里反引号是转义/命令替换符号，会被 shell 吃掉。MySQL 8 里 `user` 不是保留字，直接写 `DESCRIBE user` 或 `SHOW COLUMNS FROM user` 即可。

---

## 4. 观察结果：成功长什么样

把本 Session 成功的“证据”记下来，以后面试或复盘直接引用：

```text
成功证据：
1. mvn test 全绿（DemoControllerTest 已加 addFilters=false）
2. 启动日志有 Successfully applied 1 migration
3. MySQL 里 user 表和 flyway_schema_history 存在
```

---

## 5. 主动制造错误（每个都做完再恢复）

错误是最好的老师。以下四个错，每个都要**先看报错长什么样，再修复，再确认全绿**。

**错误 A：迁移文件名少一个下划线**

把文件改成 `V1_init_user_tables.sql`（单下划线），重启应用。观察：**没有迁移日志、表没建、应用照常启动**。这最坑——不报错但啥也没发生。改回来。

**错误 B：SQL 写错**

把 `VARCHAR(30)` 改成 `VARCHAR(3000)` 再改成别的非法内容？不用，直接把 `username VARCHAR(30) NOT NULL` 改成 `username VARCHAR(30)` 然后删掉后面的逗号之类制造语法错误，重启，看启动失败日志里 `Caused by` 指向哪一行 SQL。修复并确认迁移重新成功。

**错误 C：密码写错**

把 `application-dev.yml` 里密码改成 `wrong-password`，重启，观察 `Communications link failure` / `Access denied` 这类报错。改回来。

**错误 D：删掉 flyway-mysql 依赖**

把 POM 里的 `flyway-mysql` 删掉，重启，观察 Flyway 报“不支持的数据库/找不到 MySQL 支持”之类的错误。加回来。

---

## 6. 独立练习（先自己写，再看答案）

1. 新建 `V2__add_nickname_to_user.sql`，给 `user` 表加一列 `nickname VARCHAR(30) NULL`，重启应用，确认迁移 2 成功，然后 `DESCRIBE user` 能看到新列。
2. 重启应用两次，观察第二次启动的 Flyway 日志和第一次有什么区别（提示：看 `Successfully applied` 变成了什么）。
3. 想一想：为什么 Flyway 里**已经执行过的文件不能改内容**？如果改了会发生什么？（可以亲手试，改完记得恢复）

---

## 7. 参考答案

1. `V2__add_nickname_to_user.sql`：

```sql
ALTER TABLE `user`
    ADD COLUMN nickname VARCHAR(30) NULL COMMENT '昵称' AFTER username;
```

2. 第二次启动不会再出现 `Successfully applied 1 migration`，而是 `Schema is up to date. No migration necessary`——因为 `flyway_schema_history` 里已经记录了版本 1 执行过。

3. Flyway 启动时会校验已执行迁移文件的 checksum，你改了旧文件，校验和变了，它会报 `Migration checksum mismatch` 并拒绝启动。这是保护：防止“生产环境已经执行过的结构被悄悄改动”。正确做法永远是新建更高版本迁移。

---

## 8. 复盘题（发给老师前先自己回答）

1. 为什么业务数据要落库？Flyway 解决了手工管理表结构的哪些问题？
2. `spring.datasource.url` 里每一段各是什么含义？
3. Flyway 迁移文件命名规则是什么？执行记录存在哪张表？
4. 为什么 `learnhub-user` 一接入应用，DemoControllerTest 就失败了？`addFilters = false` 是“绕过安全”吗？
5. 为什么 Compose 的 `.env` 不会自动成为 Spring Boot 的环境变量？你现在的配置怎么兼容“默认值”和“环境变量”两种来源？

全部完成并把结果发给老师后，进入 [Session B](session-b-register-and-password-security.md)：注册与密码安全。
