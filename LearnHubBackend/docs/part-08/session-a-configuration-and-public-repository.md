# Session A：配置分层、环境变量与公开仓库

## 1. 为什么有两个 YAML 文件

Spring Boot 会把配置文件加载到 Environment。配置可以来自文件、环境变量、命令行参数和启动参数，后加载的来源通常会覆盖前面的同名值。

项目中的两个文件职责不同：

### 1.1 application.yml

这是所有 profile 都可以使用的基础配置。适合放：

- 应用名称；
- 不依赖机器的通用设置；
- 不涉及密码的默认行为；
- 所有环境都应该一致的配置。

当前文件里的 server.port=9090 只是基础默认值。

### 1.2 application-dev.yml

这是 dev profile 的覆盖配置。只有启动时激活 dev，它才会参与配置合并：

    SPRING_PROFILES_ACTIVE=dev

或者：

    mvn spring-boot:run -pl learnhub-application -Dspring-boot.run.profiles=dev

dev 文件适合放本机 Docker 地址、8080 端口、开发日志级别和本机服务连接信息。

## 2. 配置优先级

同一个配置项同时存在时，可以把它理解成：

    application.yml
        < application-dev.yml
        < 环境变量
        < 命令行参数

例如：

    password: ${MYSQL_PASSWORD:change-me-mysql}

冒号右边是默认值。运行时如果存在 MYSQL_PASSWORD，Spring 使用环境变量；没有时才使用占位符。

## 3. 为什么不能把真实值写进 YAML

Git 会保存每一次提交的内容。即使后来把一行密码删掉，旧 commit 仍然可能包含它。公开仓库中的配置只应该出现：

- 环境变量引用；
- 文档示例占位符；
- 不可用的开发默认值。

真实 API key、JWT secret、数据库密码、MinIO 密码放在本机 .env 或系统环境变量中，并且 .env 必须被 Git 忽略。

## 4. 本项目的安全配置形态

当前公开配置使用类似形式：

    api-key: ${API_KEY:}
    password: ${MYSQL_PASSWORD:change-me-mysql}
    secret: ${JWT_SECRET:dev-only-jwt-secret-change-me-in-production-0123456789}

这三行的共同点是：配置结构公开，真正的值由运行环境提供。

空的 API_KEY 默认值意味着没有配置 provider 时，依赖模型的功能会失败，但应用本身不应该因为仓库中没有密钥而泄漏凭据。

## 5. 如何检查一次提交是否安全

提交前依次检查：

    git status --short
    git diff --cached --name-only
    git grep --cached -n -I -E "sk-|AKIA|BEGIN .*PRIVATE KEY|eyJ..."

还要检查文件名：

- .env；
- *.pem、*.key、*.p12、*.jks；
- logs/；
- target/；
- IDE workspace 文件。

最后要检查历史，而不是只检查工作区：

    git log --all --oneline
    git grep -n -I -E "sk-|AKIA|BEGIN .*PRIVATE KEY" HEAD -- .

如果真实密钥曾经提交过，先轮换密钥，再清理历史。删除文件本身不能让旧 commit 消失。

## 6. 为什么根仓库使用新的历史

原 backend 是一个独立 Git 仓库，而现在要上传的是 D:\LearnHub 整体。直接嵌套会导致 GitHub 只看到一个子仓库指针。

当前做法是：

1. 保留原 backend 的 Git 元数据到仓库外的本机备份；
2. 在 D:\LearnHub 建立根仓库；
3. 将 backend 源码作为普通目录加入；
4. 重新审计新根仓库的全部历史。

这样公开仓库的主线清晰、敏感信息边界清楚。旧 commit 仍在本机备份中，可用于追溯，但没有未经审计地进入公开历史。

## 7. 学习重点

完成本 Session 后，你应该能够解释：

- profile 是配置选择，不是代码分支；
- application-dev.yml 为什么覆盖 application.yml；
- ${NAME:default} 的含义；
- .env 为什么不能提交；
- 删除敏感文件为什么不能自动清除 Git 历史；
- 为什么公开仓库需要单独的安全审计。
