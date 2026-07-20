# Day 1：工程基线、Git 与 Maven 多模块

## 今天的结果

完成后，你应拥有一个可重复构建、可以安全提交的仓库，并能解释父 POM、子模块、依赖管理和实际依赖之间的区别。

预计用时：3～4 小时。

## 一、原理课

### 1. Maven 生命周期

必须理解这些阶段按顺序包含前面的阶段：

```text
validate -> compile -> test -> package -> verify -> install -> deploy
```

- `compile`：编译主代码。
- `test`：编译并运行测试。
- `package`：生成 JAR。
- `verify`：执行用于验证包质量的检查。
- `install`：把产物安装到本机 Maven 仓库，供其他构建使用。
- `deploy`：上传到远程制品仓库，不是部署应用服务器。

`clean` 属于另一套生命周期，用于删除构建产物 `target`。

### 2. 父项目的三个角色

当前根 `pom.xml` 同时承担：

1. **继承父 POM**：继承 Spring Boot 的默认版本和插件配置。
2. **聚合器**：通过 `<modules>` 决定 Reactor 构建哪些模块。
3. **项目父 POM**：让子模块继承 Java 版本、属性和依赖版本。

重点区别：

```text
dependencyManagement：规定“如果使用这个依赖，使用哪个版本”
dependencies：真正把依赖加入当前模块的类路径
```

这也是之前 `SpringBootApplication` 无法解析时需要首先检查的地方。

### 3. Reactor 与模块依赖

Maven Reactor 会根据模块间依赖计算构建顺序，而不是只按 `<modules>` 的书写顺序盲目构建。

常用命令：

```powershell
mvn clean verify
mvn -pl learnhub-application -am package
mvn -pl learnhub-common test
mvn -pl learnhub-application dependency:tree
```

- `-pl`：只选择指定项目。
- `-am`：同时构建它所依赖的 Reactor 模块。
- `dependency:tree`：查看实际进入类路径的依赖，包括传递依赖。

### 4. 为什么要减少暂时不用的依赖

依赖不只是“以后可能调用的类库”。Spring Boot Starter 还可能触发自动配置。

例如：

- MySQL + Flyway 可能要求 DataSource。
- Security 可能自动保护接口并生成临时密码。
- Spring AI/Qdrant 可能要求模型或向量存储 Bean。
- Redis、RabbitMQ 可能增加连接配置和启动检查。

本周推荐让 `learnhub-application` 只组合当前真正用到的模块和依赖：

- `learnhub-common`
- `spring-boot-starter-web`
- `spring-boot-starter-actuator`
- `springdoc-openapi-starter-webmvc-ui`
- `spring-boot-starter-test`（test scope）

父 POM 中可以继续保留未来依赖的版本管理。到对应周再把实际依赖加入相应子模块。

## 二、动手任务

### 任务 1：建立 Git 基线

先确认当前目录：

```powershell
cd D:\LearnHub\LearnHubBackend
git init
```

创建 `.gitignore`，至少覆盖：

```gitignore
**/target/
.idea/
*.iml
.vscode/
*.log
.env
application-local.yml
```

不要忽略：

- `pom.xml`
- `application.yml`
- `.env.example`
- 数据库迁移脚本
- Docker Compose 文件

执行：

```powershell
git status
```

检查是否没有 `target`、`.idea` 和真实 `.env` 等待提交。

### 任务 2：写最小根 README

根目录 `README.md` 目前至少写：

1. LearnHub 是什么。
2. 当前阶段是模块化单体。
3. 环境要求：JDK 21、Maven、Docker。
4. 如何执行完整构建。
5. 模块列表和一句话职责。
6. 当前开发进度链接。

### 任务 3：检查并瘦身启动类依赖

先看依赖树，而不是凭感觉删除：

```powershell
mvn -pl learnhub-application dependency:tree
```

搜索这些依赖为何进入启动模块：

```powershell
mvn -pl learnhub-application dependency:tree "-Dincludes=org.springframework.security"
mvn -pl learnhub-application dependency:tree "-Dincludes=org.springframework.ai"
mvn -pl learnhub-application dependency:tree "-Dincludes=org.flywaydb"
```

调整原则：

- 当前没有代码使用的功能模块，可以暂不由 `learnhub-application` 组合。
- 当前没有代码使用的外部系统 Starter，可以暂不放进实际 `<dependencies>`。
- 不要删除父 POM 的版本管理。
- 每次只调整一组依赖，调整后立即运行构建。

### 任务 4：完成基线构建

```powershell
mvn clean verify
```

保存最后的 Reactor Summary。所有模块应为 `SUCCESS`。

## 三、今天必须做的小实验

从 `learnhub-application` 的 `<dependencies>` 中任选一个依赖：

1. 在父 POM 的 `dependencyManagement` 中找到或说明它的版本来源。
2. 用 `dependency:tree` 找到它及其传递依赖。
3. 说明删除实际 `<dependency>` 后，为什么版本管理仍在，但类无法再 import。

把结论写入学习笔记，不需要真的反复删除代码。

## 四、常见错误

### Maven 可以编译，但 IDEA 标红

先使用 Maven 面板执行 Reload。命令行能编译通常说明代码与依赖正确，问题多半在 IDE 项目模型或索引。

### 把 `target` 提交进 Git

先完善 `.gitignore`，再首次提交。不要依靠每次手工取消勾选。

### 所有依赖都放父 POM 的 `dependencies`

这样每个子模块都会继承不需要的依赖，模块边界会失去意义。父 POM主要管理版本，子模块声明自己真正需要的依赖。

## 五、验收

- [ ] `git status` 不包含 `target`、`.idea`、`.env`。
- [ ] 根 README 能让新同学知道如何构建项目。
- [ ] 能解释 `dependencyManagement` 与 `dependencies`。
- [ ] 能解释 `-pl` 与 `-am`。
- [ ] `mvn clean verify` 成功。
- [ ] 能指出至少一个暂时不该激活的 Starter。

## 六、口头复盘题

1. `package` 和 `install` 有什么不同？
2. 父 POM 是不是一定等于聚合 POM？
3. 为什么只在 `dependencyManagement` 中写 starter，启动类仍然无法 import 注解？
4. 传递依赖有什么好处，又可能造成什么问题？
5. 为什么构建成功不能证明应用可以启动？

## 七、今日提交建议

```text
chore: initialize repository and establish Maven baseline
```

完成后，把 `git status`、Reactor Summary 和五道复盘题答案发给老师。

