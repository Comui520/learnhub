# Day 6：把外部服务装进可重复环境——Docker Compose 与配置管理

## 0. 今天到底要学会什么

LearnHub 后续需要 MySQL、Redis、RabbitMQ、MinIO 和 Qdrant。如果全部手工安装到 Windows，不同版本、端口和数据目录很容易混乱。今天用 Docker Compose 把开发环境写成一份可以重复执行的文件。

完成后，你应该能够：

1. 分清镜像、容器、端口映射、数据卷和网络。
2. 知道宿主机地址和容器内地址为什么不同。
3. 看懂并运行完整 `compose.yaml`。
4. 使用 `.env.example` 与 `.env` 管理本地参数。
5. 启动、查看、停止五个服务而不误删数据。
6. 使用 `ps`、`logs`、健康检查定位常见故障。
7. 理解 Compose 的 `.env` 不会自动成为 Spring Boot 环境变量。

预计时间：6～8 小时。第一次下载镜像会受网络速度影响。

---

## 1. Docker 的五个基础概念

### 1.1 镜像 Image

镜像是应用和运行环境的只读模板。例如：

```text
mysql:8.4
```

表示 MySQL 官方镜像的 8.4 标签。镜像类似“安装包 + 预配置文件系统”，但它本身不是正在运行的进程。

### 1.2 容器 Container

容器是镜像的一次运行实例。一个 MySQL 镜像可以启动多个容器，只要端口和数据目录不冲突。

容器有自己的文件系统和网络空间。删除容器后，容器内部未挂载的数据通常会丢失。

### 1.3 端口映射

```yaml
ports:
  - "3306:3306"
```

格式：

```text
宿主机端口:容器端口
```

左边 3306 是 Windows 上访问的端口，右边 3306 是 MySQL 在容器内监听的端口。

如果 Windows 已安装 MySQL 占用 3306，可以改成：

```yaml
- "3307:3306"
```

此时宿主机 Spring Boot 访问 `localhost:3307`，容器内其他服务仍访问 `mysql:3306`。

### 1.4 数据卷 Volume

```yaml
volumes:
  - mysql-data:/var/lib/mysql
```

左边是 Docker 管理的命名卷，右边是容器中的数据目录。容器删除再重建，命名卷仍可保留数据。

### 1.5 Compose 网络与服务名

Compose 默认给同一项目的服务创建内部网络。服务名会成为 DNS 名：

```text
mysql:3306
redis:6379
rabbitmq:5672
```

两种视角：

| Spring Boot 运行位置 | MySQL 地址 |
|---|---|
| 直接运行在 Windows | `localhost:3306` |
| 以后也运行在 Compose 容器 | `mysql:3306` |

容器内的 `localhost` 指容器自己，不是 Windows，也不是 MySQL 容器。

---

## 2. 检查 Docker Desktop

先启动 Docker Desktop，等待界面显示 Docker Engine 正常。

PowerShell 执行：

```powershell
docker version
docker compose version
```

两条命令都应输出版本。若 `docker` 无法识别：

- 确认 Docker Desktop 已安装。
- 重开 PowerShell 使 PATH 生效。

若只有 Client 信息、Server 连接失败：

- Docker Desktop 可能没有启动完成。
- 等待或重启 Docker Desktop。

本日所有命令都在：

```powershell
Set-Location D:\LearnHub\LearnHubBackend
```

---

## 3. 环境变量文件

### 3.1 创建 `.env.example`

路径：

```text
D:\LearnHub\LearnHubBackend\.env.example
```

内容：

```dotenv
# Host ports. Change the left-side port here when Windows already uses it.
MYSQL_PORT=3306
REDIS_PORT=6379
RABBITMQ_PORT=5672
RABBITMQ_MANAGEMENT_PORT=15672
MINIO_API_PORT=9000
MINIO_CONSOLE_PORT=9001
QDRANT_HTTP_PORT=6333
QDRANT_GRPC_PORT=6334

# Local development credentials. Replace them in your real .env.
MYSQL_DATABASE=learnhub
MYSQL_USER=learnhub
MYSQL_PASSWORD=change-me-mysql
MYSQL_ROOT_PASSWORD=change-me-root

RABBITMQ_DEFAULT_USER=learnhub
RABBITMQ_DEFAULT_PASS=change-me-rabbitmq

MINIO_ROOT_USER=learnhub
MINIO_ROOT_PASSWORD=change-me-minio-please
```

`.env.example` 可以提交，因为它主要说明需要哪些变量。示例值不能在生产使用。

### 3.2 创建真实 `.env`

```powershell
Copy-Item .env.example .env
```

打开 `.env`，修改密码。本地学习也不要全部使用 `123456`，养成习惯。

确认 Day 1 的 `.gitignore` 包含：

```gitignore
.env
```

验证：

```powershell
git status --short
```

应该看到 `.env.example`，但不能看到 `.env`。

### 3.3 重要区别：Compose `.env` 不等于系统环境变量

Docker Compose 会自动读取同目录 `.env`，用来替换 `${MYSQL_PORT}`。

但你直接运行：

```powershell
java -jar ...
```

Spring Boot 不会自动读取这个 `.env` 文件。以后 Spring 需要密码时，你要：

- 在 IDEA Run Configuration 设置环境变量；或
- 在 PowerShell 设置 `$env:MYSQL_PASSWORD=...`；或
- 使用部署平台的 Secret 配置。

不要因为文件名叫 `.env` 就假设所有程序都会读取它。

---

## 4. 创建完整 `compose.yaml`

在仓库根目录创建：

```text
D:\LearnHub\LearnHubBackend\compose.yaml
```

完整内容：

```yaml
name: learnhub

services:
  mysql:
    image: mysql:8.4
    environment:
      MYSQL_DATABASE: ${MYSQL_DATABASE}
      MYSQL_USER: ${MYSQL_USER}
      MYSQL_PASSWORD: ${MYSQL_PASSWORD}
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}
    ports:
      - "${MYSQL_PORT:-3306}:3306"
    volumes:
      - mysql-data:/var/lib/mysql
    healthcheck:
      test: ["CMD-SHELL", "mysqladmin ping -h 127.0.0.1 -uroot -p$${MYSQL_ROOT_PASSWORD} --silent"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 30s

  redis:
    image: redis:7.4-alpine
    command: ["redis-server", "--appendonly", "yes"]
    ports:
      - "${REDIS_PORT:-6379}:6379"
    volumes:
      - redis-data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 10

  rabbitmq:
    image: rabbitmq:4.1-management
    environment:
      RABBITMQ_DEFAULT_USER: ${RABBITMQ_DEFAULT_USER}
      RABBITMQ_DEFAULT_PASS: ${RABBITMQ_DEFAULT_PASS}
    ports:
      - "${RABBITMQ_PORT:-5672}:5672"
      - "${RABBITMQ_MANAGEMENT_PORT:-15672}:15672"
    volumes:
      - rabbitmq-data:/var/lib/rabbitmq
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "-q", "ping"]
      interval: 10s
      timeout: 10s
      retries: 10
      start_period: 20s

  minio:
    image: minio/minio:RELEASE.2025-04-22T22-12-26Z
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD}
    ports:
      - "${MINIO_API_PORT:-9000}:9000"
      - "${MINIO_CONSOLE_PORT:-9001}:9001"
    volumes:
      - minio-data:/data

  qdrant:
    image: qdrant/qdrant:v1.14.1
    ports:
      - "${QDRANT_HTTP_PORT:-6333}:6333"
      - "${QDRANT_GRPC_PORT:-6334}:6334"
    volumes:
      - qdrant-data:/qdrant/storage

volumes:
  mysql-data:
  redis-data:
  rabbitmq-data:
  minio-data:
  qdrant-data:
```

### 4.1 `name`

```yaml
name: learnhub
```

指定 Compose 项目名。容器、网络和卷会带上相应前缀，方便识别。

### 4.2 `services`

每个一级服务名代表一个可运行组件。服务名同时用于内部网络寻址。

### 4.3 `${VAR:-default}`

```yaml
"${MYSQL_PORT:-3306}:3306"
```

如果 `MYSQL_PORT` 已定义就使用它；否则使用 3306。

密码变量没有默认值，是为了缺失时尽早暴露问题，而不是悄悄使用弱密码。

### 4.4 为什么 healthcheck 中是 `$${...}`

```yaml
-p$${MYSQL_ROOT_PASSWORD}
```

Compose 使用 `$` 做变量替换。写成 `$$` 是转义，意思是把 `$MYSQL_ROOT_PASSWORD` 原样传进容器，再由容器 shell 读取容器环境变量。

如果只写 `${MYSQL_ROOT_PASSWORD}`，Compose 会在宿主机配置解析阶段直接替换。

### 4.5 Redis AOF

```yaml
command: ["redis-server", "--appendonly", "yes"]
```

开启 Append Only File 持久化，把写命令追加到磁盘。它不是生产调优方案，只是让本地 Redis 数据更容易保留。

### 4.6 为什么 MinIO/Qdrant 没写容器名

Compose 会自动管理名称；业务通信使用稳定的服务名即可。固定 `container_name` 会减少同一配置启动多个项目副本的灵活性。

### 4.7 镜像标签说明

本教材给出可以明确复现的版本/版本线，不长期使用 `latest`。镜像生态会更新；如果某个标签将来不可用，先到官方镜像说明确认可用稳定标签，并把实际验证版本记录到 README，不要随意换成来源不明镜像。

---

## 5. 在启动前验证配置

执行：

```powershell
docker compose config
```

它会：

- 检查 YAML 语法。
- 展开 `.env` 变量。
- 输出 Compose 最终理解的配置。

注意：展开后的输出可能包含真实密码，不要把完整输出发到公开 Issue 或提交到仓库。

如果出现：

```text
The "MYSQL_PASSWORD" variable is not set
```

检查 `.env` 是否与 `compose.yaml` 在同一目录，变量名是否拼写一致。

如果出现 YAML 行号错误，检查缩进和 Tab。

---

## 6. 第一次启动

```powershell
docker compose up -d
```

解释：

- `up`：创建需要的网络、卷和容器，然后启动。
- `-d`：detached，后台运行，终端不会一直被日志占用。

第一次会下载镜像。完成后：

```powershell
docker compose ps
```

你应看到五个服务。MySQL、Redis、RabbitMQ 在启动完成后应显示 healthy；MinIO 与 Qdrant 即使没有 Compose healthcheck，也应显示 Up。

状态可能短暂显示 `health: starting`。MySQL 首次初始化需要时间，等待几十秒再 `ps`，不要立即认定失败。

---

## 7. 逐个验证服务

### 7.1 MySQL

使用容器里的环境变量执行 ping：

```powershell
docker compose exec mysql sh -c 'mysqladmin ping -h 127.0.0.1 -uroot -p"$MYSQL_ROOT_PASSWORD"'
```

应看到：

```text
mysqld is alive
```

查看数据库：

```powershell
docker compose exec mysql sh -c 'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -e "SHOW DATABASES;"'
```

应包含 `learnhub`。

### 7.2 Redis

```powershell
docker compose exec redis redis-cli ping
```

应返回：

```text
PONG
```

### 7.3 RabbitMQ

```powershell
docker compose exec rabbitmq rabbitmq-diagnostics -q ping
```

应成功返回 Ping 信息。

浏览器打开：

```text
http://localhost:15672
```

使用 `.env` 中 RabbitMQ 用户名密码登录。不要把密码截图发到公开位置。

### 7.4 MinIO

健康地址：

```powershell
curl.exe -i http://localhost:9000/minio/health/live
```

应返回 HTTP 200。

控制台：

```text
http://localhost:9001
```

使用 MinIO 变量登录。本周不创建业务 Bucket，第三周文件功能再做。

### 7.5 Qdrant

```powershell
curl.exe -i http://localhost:6333/healthz
```

应返回 HTTP 200。

Dashboard：

```text
http://localhost:6333/dashboard
```

本周不创建 collection，第五周向量功能再做。

---

## 8. 查看日志

所有服务最近日志：

```powershell
docker compose logs --tail 100
```

单个服务：

```powershell
docker compose logs --tail 100 mysql
```

持续跟随：

```powershell
docker compose logs -f rabbitmq
```

按 Ctrl+C 只停止日志跟随，不会停止后台容器。

排错时不要先反复重启。先看：

1. `docker compose ps` 的状态。
2. 失败服务的日志。
3. 端口是否冲突。
4. 环境变量是否缺失。
5. 数据卷中是否有旧配置影响初始化。

---

## 9. 停止、启动和删除的区别

暂停容器进程但保留容器：

```powershell
docker compose stop
```

再次启动现有容器：

```powershell
docker compose start
```

停止并删除容器与默认网络，但保留命名卷：

```powershell
docker compose down
```

重新创建：

```powershell
docker compose up -d
```

危险区别：

```powershell
docker compose down -v
```

`-v` 会删除 Compose 命名卷，MySQL/Redis/RabbitMQ/MinIO/Qdrant 数据都会丢失。本项目日常不要使用。只有明确需要彻底重置本地数据且已确认无重要数据时才考虑。

---

## 10. 端口冲突怎么处理

典型错误：

```text
Bind for 0.0.0.0:3306 failed: port is already allocated
```

说明 Windows 的 3306 已被其他程序使用。

不要改容器内端口。修改 `.env`：

```dotenv
MYSQL_PORT=3307
```

然后：

```powershell
docker compose up -d
```

以后宿主机 Spring JDBC URL 使用：

```text
jdbc:mysql://localhost:3307/learnhub
```

但容器内仍是 `mysql:3306`。

其他组件同理修改左侧宿主机端口变量。

---

## 11. `depends_on` 为什么还不够

很多教程写：

```yaml
depends_on:
  - mysql
```

它主要控制启动顺序，不自动保证 MySQL 已完成初始化并能接受 SQL。容器进程启动与服务就绪之间可能相隔几十秒。

真实应用需要：

- 健康检查。
- 合理连接超时。
- 有限重试。
- 启动失败时清晰日志。

本周 Spring Boot 还不连接这些服务，所以暂时不写 application 的依赖关系。第二周接 MySQL 时再使用配置与测试验证。

---

## 12. 故障练习

### 练习 A：停止一个服务再恢复

```powershell
docker compose stop redis
docker compose ps
docker compose exec redis redis-cli ping
```

第三条应失败，因为容器没运行。查看状态后恢复：

```powershell
docker compose start redis
docker compose exec redis redis-cli ping
```

应重新返回 PONG。

### 练习 B：验证数据卷保留数据

写入 Redis：

```powershell
docker compose exec redis redis-cli SET learnhub:test persisted
docker compose exec redis redis-cli GET learnhub:test
```

执行：

```powershell
docker compose down
docker compose up -d
```

Redis healthy 后再次：

```powershell
docker compose exec redis redis-cli GET learnhub:test
```

如果 AOF 正常和卷保留，应看到 `persisted`。练习后删除：

```powershell
docker compose exec redis redis-cli DEL learnhub:test
```

### 练习 C：阅读一个端口冲突

可以在已有服务占用端口时临时把另一个端口变量设成相同值，执行 `docker compose up -d`，阅读错误后立即恢复。不要修改真实数据卷或删除系统进程。

---

## 13. 把使用方法写入 README

追加：

```markdown
## 本地基础设施

1. 复制环境变量示例：`Copy-Item .env.example .env`
2. 修改 `.env` 中的本地密码。
3. 验证配置：`docker compose config`
4. 启动：`docker compose up -d`
5. 查看状态：`docker compose ps`
6. 停止并保留数据：`docker compose down`

默认端口：MySQL 3306、Redis 6379、RabbitMQ 5672/15672、MinIO 9000/9001、Qdrant 6333/6334。
```

同时记录你实际验证的镜像标签。如果因环境调整端口，记录使用 `.env` 覆盖，而不是把个人端口写死为全项目唯一选择。

---

## 14. 今日验收清单

- [ ] `docker version` 和 `docker compose version` 正常。
- [ ] `.env.example` 可以提交，真实 `.env` 被忽略。
- [ ] `docker compose config` 通过。
- [ ] MySQL、Redis、RabbitMQ 为 healthy。
- [ ] MinIO health 返回 200，控制台可登录。
- [ ] Qdrant health 返回 200，Dashboard 可打开。
- [ ] 五个服务均使用命名卷。
- [ ] 我能解释宿主机端口与容器端口。
- [ ] `docker compose down` 后数据卷仍存在。
- [ ] 我没有执行 `down -v` 删除数据。
- [ ] README 已加入启动与停止说明。

---

## 15. 复盘题与参考答案

1. **镜像和容器区别？** 镜像是只读运行模板，容器是镜像的一次运行实例。
2. **为什么需要数据卷？** 把持久数据从容器可丢弃文件系统中分离，容器重建后仍能复用。
3. **`3307:3306` 两边分别是什么？** 左边是 Windows 宿主机端口，右边是容器内服务端口。
4. **容器间为什么用 `mysql:3306`？** Compose 内部 DNS 使用服务名解析目标容器，不经过宿主机映射端口。
5. **容器内的 localhost 是谁？** 当前容器自己，不是宿主机或其他服务。
6. **`docker compose down` 与 `down -v` 区别？** 前者删除容器和网络但保留命名卷；后者还删除卷和数据。
7. **为什么 depends_on 不能保证 MySQL 可用？** 它能控制容器启动关系，但进程已启动不等于服务初始化完成。
8. **为什么 `.env.example` 提交而 `.env` 不提交？** example 描述变量契约，真实 `.env` 可能包含本机秘密。
9. **Compose 的 `.env` 会自动被 Spring Boot 读取吗？** 不会；Compose 用它做变量插值，直接运行的 Java 进程需要自己的环境变量来源。
10. **服务启动失败的排查顺序？** 先 ps 看状态，再看对应 logs，然后检查端口、变量、健康检查和数据卷影响。

完成后交给老师：`compose.yaml`、`.env.example`、`docker compose ps`、五项服务验证结果、Redis 持久化实验结果和十道题自己的答案。不要发送真实 `.env` 或密码。

