# Day 6：Docker Compose、基础设施与开发配置

## 今天的结果

使用 Docker Compose 启动 MySQL、Redis、RabbitMQ、MinIO 和 Qdrant；敏感信息通过环境变量提供，不提交真实 `.env`。

预计用时：4～6 小时，首次下载镜像可能更久。

## 一、原理课

### 1. 镜像、容器和数据卷

- 镜像：只读模板。
- 容器：镜像的一次运行实例。
- 数据卷：独立于容器生命周期保存数据。
- 端口映射：`宿主机端口:容器端口`。

删除容器不一定删除命名数据卷。`docker compose down -v` 会删除卷，本项目日常不要随意使用。

### 2. Compose 服务名就是内部 DNS 名

容器之间访问 MySQL 应使用服务名和容器端口，例如：

```text
mysql:3306
```

宿主机上的 Spring Boot 访问容器，则通常使用：

```text
localhost:3306
```

不要混淆两个网络视角。

### 3. `depends_on` 不等于服务已经可用

容器进程启动不代表 MySQL 已完成初始化。应为服务配置 healthcheck，并让依赖方具备重试或等待策略。

### 4. 配置与秘密

建议：

```text
.env.example   提交；只提供变量名和安全的示例值
.env           不提交；保存本机真实值
application.yml       提交；共享配置
application-dev.yml   提交；通过 ${VAR:default} 引用变量
```

## 二、Compose 设计任务

在仓库根目录创建 `compose.yaml`，包含五个服务。

### 1. MySQL

要求：

- 使用 MySQL 8 系列明确版本标签。
- 数据库名 `learnhub`。
- 普通应用用户与 root 密码来自环境变量。
- 挂载命名数据卷。
- 配置健康检查。
- 映射 3306，但如果本机已占用可使用 3307。

### 2. Redis

要求：

- 使用 Redis 7 系列明确版本标签。
- 挂载数据卷。
- 使用 `redis-cli ping` 健康检查。
- 开发环境映射 6379。

### 3. RabbitMQ

要求：

- 使用带 management 控制台的镜像。
- 用户名和密码来自环境变量。
- 映射 AMQP 端口 5672 和管理端口 15672。
- 挂载数据卷并配置健康检查。

### 4. MinIO

要求：

- 访问密钥来自环境变量。
- 映射 API 端口 9000、控制台端口 9001。
- 命令为 `server /data --console-address ":9001"`。
- 挂载数据卷。

### 5. Qdrant

要求：

- 使用明确版本标签。
- 映射 HTTP 端口 6333 和 gRPC 端口 6334。
- 挂载 `/qdrant/storage` 数据卷。

镜像标签选择原则：不要长期使用 `latest`。第一次可以选择官方维护的主/次版本标签，确认能运行后把实际测试版本记录到 README。

## 三、环境变量

创建 `.env.example`，只放示例：

```dotenv
MYSQL_DATABASE=learnhub
MYSQL_USER=learnhub
MYSQL_PASSWORD=change-me
MYSQL_ROOT_PASSWORD=change-root-password
RABBITMQ_DEFAULT_USER=learnhub
RABBITMQ_DEFAULT_PASS=change-me
MINIO_ROOT_USER=learnhub
MINIO_ROOT_PASSWORD=change-me-please
```

复制为本机 `.env` 后修改真实值，确认 `.gitignore` 已忽略 `.env`：

```powershell
Copy-Item .env.example .env
git status
```

`git status` 不应显示 `.env`。

## 四、启动与验证

先验证 Compose 语法和变量替换：

```powershell
docker compose config
```

启动：

```powershell
docker compose up -d
docker compose ps
```

查看单个服务日志：

```powershell
docker compose logs mysql
docker compose logs rabbitmq
```

验证入口：

| 组件 | 地址/端口 |
|---|---|
| MySQL | `localhost:3306` |
| Redis | `localhost:6379` |
| RabbitMQ 管理台 | `http://localhost:15672` |
| MinIO 控制台 | `http://localhost:9001` |
| Qdrant Dashboard | `http://localhost:6333/dashboard` |

停止但保留数据：

```powershell
docker compose down
```

不要在不理解后果时加 `-v`。

## 五、开发配置设计

本周只建立配置结构，不要求业务连接全部组件。`application-dev.yml` 可预留环境变量形式，但不要为了“看起来完整”而激活尚未使用的 Starter。

示例思想：

```yaml
spring:
  datasource:
    url: ${MYSQL_URL:jdbc:mysql://localhost:3306/learnhub}
    username: ${MYSQL_USER:learnhub}
    password: ${MYSQL_PASSWORD}
```

密码没有安全默认值，缺少时应显式失败。数据库依赖到第二周真正接入时再启用这段配置。

## 六、故障练习

至少完成两个：

1. 停止 MySQL，观察 `docker compose ps` 和日志，再恢复。
2. 临时制造端口冲突，理解报错后恢复配置。
3. 输入错误的 RabbitMQ 密码，观察管理台认证失败。
4. `docker compose down` 后再次 `up -d`，确认数据卷仍存在。

记录：现象、使用的命令、根因、恢复方式。

## 七、验收

- [ ] `docker compose config` 成功。
- [ ] 五个服务均已启动，配置了合理的数据卷。
- [ ] 有健康检查的服务最终为 healthy。
- [ ] RabbitMQ、MinIO、Qdrant 管理页面可访问。
- [ ] `.env.example` 已提交，`.env` 不在 Git 状态中。
- [ ] README 记录实际验证过的镜像版本和端口。
- [ ] 能解释宿主机地址和容器内服务名的差异。

## 八、口头复盘题

1. 镜像、容器、数据卷分别是什么？
2. `docker compose down` 和 `down -v` 有什么区别？
3. 为什么 `depends_on` 不能完全解决服务就绪问题？
4. Spring Boot 在宿主机和容器内运行时，MySQL 地址为何不同？
5. 为什么 `.env.example` 可以提交而 `.env` 不应提交？

## 九、今日提交建议

```text
chore: add local infrastructure with Docker Compose
```

完成后，把 `docker compose ps`、`git status` 和两个故障练习记录发给老师。

