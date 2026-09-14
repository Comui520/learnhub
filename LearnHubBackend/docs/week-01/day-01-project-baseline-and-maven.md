# Day 1：从文件夹到可维护工程——Git 与 Maven 多模块

## 0. 今天到底要学会什么

你现在看到的是很多文件夹和 `pom.xml`。Day 1 要把它们从“我知道大概是配置文件”变成一张你能读懂的工程地图。

完成后，你应该能够：

1. 知道 PowerShell 当前在哪个目录，命令会作用到哪里。
2. 知道 Git 管理的是源码历史，不是编译出来的 `target`。
3. 看懂一个基本 POM 的坐标、父子关系、模块、依赖和插件。
4. 分清“管理依赖版本”和“真正引入依赖”。
5. 知道 Maven 为什么按一定顺序构建多个模块。
6. 把启动模块暂时不用的依赖移走，降低 Day 2 的启动难度。
7. 从干净状态完成一次完整构建。

预计时间：5～6 小时。第一次认真学习 Maven，不建议压缩到一小时。

---

## 1. 先认识目录、路径和终端

### 1.1 什么是工作目录

终端执行命令时，总有一个“当前位置”，叫工作目录。相对路径都从这里开始解释。

打开 PowerShell，输入：

```powershell
Set-Location D:\LearnHub\LearnHubBackend
Get-Location
```

预期看到：

```text
Path
----
D:\LearnHub\LearnHubBackend
```

下面两条命令含义不同：

```powershell
Get-Content pom.xml
Get-Content D:\LearnHub\LearnHubBackend\pom.xml
```

第一条使用相对路径，依赖当前位置；第二条使用绝对路径，从哪个目录执行都指向同一个文件。

### 1.2 查看当前工程

```powershell
Get-ChildItem
```

你应该看到根 `pom.xml` 和八个 `learnhub-*` 文件夹。

再执行：

```powershell
Get-ChildItem -Recurse -Filter pom.xml | Select-Object FullName
```

这会递归寻找所有 POM。`-Recurse` 表示进入子目录，`-Filter` 表示只保留指定文件名。

### 1.3 为什么一定先确认目录

如果你在 `D:\LearnHub` 执行 `mvn clean verify`，Maven 会寻找 `D:\LearnHub\pom.xml`；但真实父 POM 在 `D:\LearnHub\LearnHubBackend`，于是会报“没有 POM”。

以后看到类似错误：

```text
The goal you specified requires a project to execute but there is no POM in this directory
```

第一件事不是重装 Maven，而是执行 `Get-Location`。

---

## 2. Git：给代码建立可以回退的历史

### 2.1 Git 是什么

Git 是版本控制系统。你每完成一个有意义的阶段，就拍一张“项目快照”，这张快照叫 commit。

它解决的问题：

- 改坏后可以比较和回退。
- 知道某行代码为什么改变。
- 多人协作时合并不同修改。
- 向面试官展示真实开发过程，而不是最后一次性上传全部文件。

GitHub 是托管 Git 仓库的网站；Git 本身可以完全在本机使用。今天只初始化本地 Git，不发布远程仓库。

### 2.2 初始化仓库

确认当前位置后执行：

```powershell
git init
```

预期出现类似：

```text
Initialized empty Git repository in D:/LearnHub/LearnHubBackend/.git/
```

`.git` 是隐藏目录，保存版本历史。不要手工编辑或删除其中内容。

验证：

```powershell
git status
```

你会看到很多 `Untracked files`，意思是文件存在，但 Git 还没有开始跟踪。

### 2.3 为什么需要 `.gitignore`

Maven 编译后会创建 `target`。IDEA 会创建 `.idea`。这些文件是本机或构建工具生成的，不应该进入源码历史：

- 可以重新生成，提交只会增大仓库。
- 不同电脑的 IDE 配置可能冲突。
- 日志和本地配置可能包含路径或秘密。

在 `D:\LearnHub\LearnHubBackend` 创建 `.gitignore`，完整内容先使用：

```gitignore
# Maven build output
**/target/

# IntelliJ IDEA
.idea/
*.iml

# VS Code
.vscode/

# Logs
*.log
logs/

# Local secrets and machine-specific configuration
.env
application-local.yml
application-local.yaml

# Operating system files
.DS_Store
Thumbs.db
```

每行含义：

- `#` 开头是注释。
- `**/target/` 忽略任意深度的 target 文件夹。
- `.env` 只忽略真实环境变量文件；以后会提交 `.env.example`。
- 不要写 `*.yml`，否则公共配置和 Compose 也会全部被忽略。

再次执行：

```powershell
git status --short
```

预期不会出现 `.idea` 和任何 `target`。`??` 表示尚未跟踪的新文件，这是正常的。

如果之前已经把某个文件提交过，后来加入 `.gitignore` 不会自动停止跟踪。因为现在是新仓库，暂时没有这个问题。

---

## 3. Maven 是什么

### 3.1 Maven 解决什么问题

纯 Java 可以手工运行 `javac`，但真实项目还需要：

- 下载 Spring、JUnit 等第三方库。
- 管理这些库的版本和传递依赖。
- 按规范编译主代码与测试代码。
- 运行测试。
- 打包 JAR。
- 构建多个相互依赖的模块。

Maven 根据 `pom.xml` 完成这些工作。POM 的全称是 Project Object Model，可以理解为“项目说明书”。

### 3.2 Maven 坐标

一个 Maven 产物通常由三项识别：

```xml
<groupId>com.github.comui520</groupId>
<artifactId>learnhub-common</artifactId>
<version>0.0.1-SNAPSHOT</version>
```

- `groupId`：组织或项目组，类似姓。
- `artifactId`：具体模块名，类似名。
- `version`：版本。
- `SNAPSHOT`：仍在开发、内容可能变化的版本。

组合起来：

```text
com.github.comui520:learnhub-common:0.0.1-SNAPSHOT
```

这就是其他模块声明依赖时使用的身份。

### 3.3 父 POM 与子 POM

子模块 POM 中有：

```xml
<parent>
    <groupId>com.github.comui520</groupId>
    <artifactId>learnhub</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</parent>
```

意思是它继承 LearnHub 父 POM 的公共配置，例如 Java 21、编码和依赖版本。

这类似 Java 子类继承父类的一部分配置，但不要把它理解为完全相同的语言机制。

父 POM 中：

```xml
<packaging>pom</packaging>
```

说明根项目本身不打成业务 JAR，主要负责聚合和管理。

### 3.4 聚合模块

根 POM 的：

```xml
<modules>
    <module>learnhub-application</module>
    <module>learnhub-common</module>
    ...
</modules>
```

告诉 Maven：从根目录构建时，这些子目录一起进入 Reactor。

Reactor 是 Maven 对“这一次一起构建的项目集合”的称呼。它会分析模块依赖，计算正确顺序。

### 3.5 生命周期

常见阶段：

```text
validate -> compile -> test -> package -> verify -> install -> deploy
```

后面的阶段会包含前面的工作。例如执行 `package` 时会先编译并测试。

- `compile`：编译 `src/main/java`。
- `test`：编译并执行 `src/test/java`。
- `package`：生成 JAR。
- `verify`：运行额外质量检查；当前项目即使没有额外插件，也把它作为完整本地验证入口。
- `install`：将模块产物复制进本机 Maven 仓库，通常位于用户目录的 `.m2/repository`。
- `deploy`：上传到远程制品库，不是把网站部署上线。

`clean` 属于另一套生命周期，它删除 `target`，保证不是靠旧编译结果成功。

所以本项目日常完整检查使用：

```powershell
mvn clean verify
```

---

## 4. 最容易混淆的地方：版本管理不等于引入依赖

### 4.1 `dependencyManagement`

父 POM 中的：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
            <version>${springdoc.version}</version>
        </dependency>
    </dependencies>
</dependencyManagement>
```

只表达：哪个子模块以后使用 springdoc 时，默认采用哪个版本。

它不会把 springdoc 放进所有模块的类路径。

### 4.2 `dependencies`

启动模块中的：

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
</dependencies>
```

才是真正使用依赖。

之前 IDE 无法解析 `SpringBootApplication`，本质上要检查包含该注解的 Spring Boot 依赖是否进入了启动模块的编译类路径。

### 4.3 为什么 Starter 没写版本

根 POM 继承：

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.16</version>
</parent>
```

Spring Boot 已管理大量兼容版本。子模块再手工给每个 Spring 依赖写版本，反而可能破坏兼容组合。

---

## 5. 依赖范围 scope

你会看到：

```xml
<scope>test</scope>
```

常见范围：

| scope | 编译主代码 | 运行应用 | 编译/运行测试 | 常见用途 |
|---|---|---|---|---|
| 默认 compile | 是 | 是 | 是 | Spring Web、业务库 |
| runtime | 否 | 是 | 是 | MySQL 驱动 |
| test | 否 | 否 | 是 | JUnit、Mockito |
| provided | 是 | 通常由外部提供 | 是 | 特殊容器 API |

JUnit 使用 `test`，因为生产应用运行时不需要测试框架。

`optional>true` 表示依赖通常不应该自动传递给使用当前模块的下游项目。DevTools 和配置处理器常这样设置。

---

## 6. 为什么现在要做依赖瘦身

你当前 `learnhub-application` 组合了所有业务模块。业务模块又引入：

- Spring Security
- MyBatis 与 MySQL
- Redis 与 Redisson
- RabbitMQ
- MinIO
- Spring AI 与 Qdrant

即使你还没写一行调用代码，Spring Boot 看到这些类，也可能尝试相应的自动配置。结果是 Day 2 只想启动一个健康检查，却可能同时遇到数据源、Security 或模型 Bean 报错。

正确原则是：依赖在真正使用时加入。父 POM 可以提前管理版本，但运行模块不要提前激活所有 Starter。

### 6.1 修改启动模块 POM

打开：

```text
D:\LearnHub\LearnHubBackend\learnhub-application\pom.xml
```

保留原来的 `<parent>`、`artifactId`、`name` 和 `<build>`。把 `<dependencies>` 调整为下面这组本周实际需要的依赖：

```xml
<dependencies>
    <!-- Day 3 的统一响应和业务异常位于 common。 -->
    <dependency>
        <groupId>com.github.comui520</groupId>
        <artifactId>learnhub-common</artifactId>
    </dependency>

    <!-- 提供 Spring MVC、内嵌 Tomcat 和 JSON 序列化。 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <!-- 提供 @Valid、@NotBlank、@Size 等参数校验。 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>

    <!-- 提供 /actuator/health。 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>

    <!-- 生成 OpenAPI JSON 和 Swagger UI。 -->
    <dependency>
        <groupId>org.springdoc</groupId>
        <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    </dependency>

    <!-- 让 IDEA 对 @ConfigurationProperties 提供提示。 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-configuration-processor</artifactId>
        <optional>true</optional>
    </dependency>

    <!-- 仅用于本地开发自动重启，不传递给下游。 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-devtools</artifactId>
        <scope>runtime</scope>
        <optional>true</optional>
    </dependency>

    <!-- JUnit 5、Mockito、AssertJ、MockMvc 等测试工具。 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

现在暂时移出启动模块：

- 五个业务模块依赖。
- AOP Starter。
- Flyway 和 MySQL 驱动。
- Security Test。
- Testcontainers。

这不是永远删除功能。第二周开始真实用户功能时再加入 `learnhub-user`、数据库与 Security。

### 6.2 让 common 保持纯净

打开：

```text
D:\LearnHub\LearnHubBackend\learnhub-common\pom.xml
```

Day 3 的代码只使用 JDK 类型，因此生产依赖暂时可以为空，只保留测试依赖：

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

Validation 属于 Web 输入边界，放在实际使用它的 `learnhub-application`。本周也不使用 Lombok，因为 Java 21 的 record 已足够。

### 6.3 为什么根 `<modules>` 不删除业务模块

根 `<modules>` 决定完整工程包含哪些模块；启动模块的 `<dependencies>` 决定运行时组合哪些模块。

空的未来模块仍可参与构建，但不会因为启动模块直接依赖它们而把所有 Starter 带入应用。二者职责不同。

---

## 7. 用 dependency tree 验证，而不是猜

执行：

```powershell
mvn -pl learnhub-application dependency:tree
```

输出会很长。重点是树形缩进：某依赖下面缩进的项是它带来的传递依赖。

检查 Security：

```powershell
mvn -pl learnhub-application dependency:tree "-Dincludes=org.springframework.security"
```

瘦身后应没有正常依赖行，或显示没有匹配依赖。

检查 Spring Boot：

```powershell
mvn -pl learnhub-application dependency:tree "-Dincludes=org.springframework.boot"
```

应该能看到 Web、Actuator、Boot、AutoConfigure 等。

检查 common：

```powershell
mvn -pl learnhub-application dependency:tree "-Dincludes=com.github.comui520:learnhub-common"
```

应该看到：

```text
com.github.comui520:learnhub-common:jar:0.0.1-SNAPSHOT:compile
```

---

## 8. 创建最小 README

在根目录创建 `README.md`，先使用下面内容，再用自己的话补充：

```markdown
# LearnHub

LearnHub 是一个 AI 驱动的个人知识库与学习平台。当前采用 Maven 多模块的模块化单体架构。

## 环境要求

- JDK 21
- Maven 3.9+
- Docker Desktop（Day 6 使用）

## 构建

```powershell
mvn clean verify
```

## 模块

- `learnhub-application`：启动与模块组合。
- `learnhub-common`：稳定的公共响应和错误契约。
- `learnhub-user`：用户与权限。
- `learnhub-knowledge`：知识库与文档。
- `learnhub-ai`：RAG 与模型调用。
- `learnhub-study`：练习、错题和复习。
- `learnhub-credit`：额度与订单。
- `learnhub-infrastructure`：外部系统适配。

## 当前进度

正在进行第一周：Java 恢复与工程骨架。
```

注意 Markdown 中嵌套代码块时，实际编辑可使用四个反引号包住外层，或直接手工整理，避免围栏提前结束。

---

## 9. 完整构建

执行：

```powershell
mvn clean verify
```

第一次可能下载依赖，需要等待。成功时最后应出现：

```text
[INFO] Reactor Summary for LearnHubBackend ...
[INFO] learnhub ................................ SUCCESS
[INFO] learnhub-common ......................... SUCCESS
...
[INFO] BUILD SUCCESS
```

模块顺序可能与 `<modules>` 书写顺序不同，因为 Reactor 会优先构建被其他模块依赖的模块。

如果出现 `BUILD FAILURE`：

1. 找到第一个失败模块。
2. 向上寻找第一个 `[ERROR]`。
3. 不要只看 Reactor Summary，它只告诉你谁失败，不告诉你根因。
4. 修复后重新运行相同命令。

---

## 10. 第一次提交

先查看：

```powershell
git status --short
```

确认没有 `target`、`.idea`、`.env` 后执行：

```powershell
git add .
git status --short
git commit -m "chore: establish Maven project baseline"
```

`git add .` 把当前变更放进暂存区；`git commit` 才真正创建历史快照。

如果 Git 提示没有用户名和邮箱，只为本机配置：

```powershell
git config --global user.name "你的名字"
git config --global user.email "你的邮箱"
```

然后重新 commit。

提交完成后：

```powershell
git log --oneline -5
git status
```

`git status` 理想结果：

```text
nothing to commit, working tree clean
```

---

## 11. 必做实验

### 实验：亲眼验证 dependencyManagement 不引入依赖

1. 在父 POM 找到 MinIO 的 dependencyManagement。
2. 执行：

```powershell
mvn -pl learnhub-application dependency:tree "-Dincludes=io.minio:minio"
```

3. 启动模块没有实际声明 MinIO，也没有依赖会传递引入它时，依赖树中不应出现 MinIO。

结论要用自己的话写：父 POM 只是知道“如果有人使用 MinIO，它应使用哪个版本”，不代表启动模块已经携带 MinIO。

---

## 12. 常见问题与排查

### 问题 A：`mvn` 无法识别

执行：

```powershell
mvn -version
```

如果仍无法识别，说明 Maven 的 `bin` 没在 PATH。你的环境此前已经验证 Maven 3.9.11 正常，所以更可能是换了一个没有刷新环境变量的终端，重开 PowerShell 再试。

### 问题 B：Java 版本不是 21

```powershell
java -version
mvn -version
```

注意第二条输出中的 Java version 才是 Maven 实际使用的 JDK。系统 `java` 和 Maven 使用的 JDK 有可能不同。

### 问题 C：IDEA 仍显示旧依赖

在 IDEA 右侧 Maven 工具窗口点击 Reload All Maven Projects。命令行 `mvn clean verify` 成功但 IDE 标红，通常是 IDE 项目模型尚未刷新。

### 问题 D：删除依赖后其他空模块构建失败

看错误发生在哪个模块。只应调整启动模块和 common 的依赖；不要随意删除父 POM 的版本管理或其他模块 POM 中仍用于依赖关系解析的内部模块坐标。

---

## 13. 今日验收清单

- [ ] 我能用 `Get-Location` 确认工作目录。
- [ ] Git 已初始化。
- [ ] `.gitignore` 不会忽略源码和公共 YAML。
- [ ] `git status` 不包含 target、IDE 文件和秘密。
- [ ] 启动模块只保留第一周需要的实际依赖。
- [ ] 我能从 dependency tree 找到 common 和 Spring Boot 依赖。
- [ ] `mvn clean verify` 显示 BUILD SUCCESS。
- [ ] README 有环境、构建和模块说明。
- [ ] 已创建第一次 Git commit。

---

## 14. 复盘题：先自己回答

1. Maven 为什么需要坐标？
2. 父 POM 的 `<modules>` 和启动模块的 `<dependencies>` 有什么不同？
3. `dependencyManagement` 为什么不能让 Java 代码直接 import 一个类？
4. `mvn clean verify` 中 clean 和 verify 分别做什么？
5. 为什么 JUnit 依赖使用 test scope？
6. 为什么加入一个暂时没调用的 Spring Starter，也可能影响应用启动？
7. 为什么不能提交 target、`.idea` 和 `.env`？
8. Maven 命令说当前目录没有 POM 时，先做什么？

## 15. 参考答案

1. 坐标唯一识别一个模块或第三方制品，让 Maven 知道下载、依赖和构建的是谁。
2. `<modules>` 决定一次 Reactor 构建包含哪些子项目；`<dependencies>` 决定某个模块编译和运行时需要哪些制品。
3. 它只管理版本，没有把 JAR 加进当前模块类路径；实际依赖必须在 `<dependencies>` 中声明或由其他依赖传递引入。
4. clean 删除旧 target；verify 依次完成校验、编译、测试、打包并执行额外验证，避免旧产物让构建假成功。
5. 生产主代码和运行环境不需要测试框架，test scope 能限制它只进入测试类路径。
6. Starter 会带入类和自动配置；Boot 根据类路径条件创建 Bean，可能因此要求数据库、认证或外部服务配置。
7. target 可重新生成，IDE 配置因电脑而异，`.env` 可能包含秘密；提交它们会污染历史或泄露信息。
8. 执行 `Get-Location`，确认位于 `D:\LearnHub\LearnHubBackend`，再确认该目录确实存在 `pom.xml`。

完成后交给老师：`git status`、`mvn clean verify` 的 Reactor Summary、dependency tree 中 common 的一段输出，以及八道题自己的答案。

