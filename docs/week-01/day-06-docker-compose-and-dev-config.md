# Day 6：从 Docker 命令到 Docker Compose——为 LearnHub 准备本地开发环境

> 这不是一份“把下面的 YAML 复制进去然后运行”的说明书。今天的目标是：你能看懂 Compose 在替你做什么，能自己判断某个服务为什么连不上、数据为什么还在或为什么丢了。
>
> 今天先不让 Spring Boot 连接数据库。我们先把“外部服务如何被启动和管理”这件事学明白。第二周才会把 MySQL 接入 Java 项目。

---

## 0. 开始前：你现在的 Docker 状态

你之前的 Docker Desktop 报过：

```text
Virtualization support not detected
```

我们已经检查过电脑：硬件虚拟化是正常的，但 WSL 还没有正确安装。因此**先完成下面的前置条件，再做本课的实际操作**；否则每一个 Docker 命令都会失败，这不是你的 Compose 写错了。

用“管理员身份”打开 PowerShell，运行：

```powershell
wsl --install
```

重启 Windows。重启后打开 Docker Desktop，等待它显示 Engine running（引擎正在运行）。然后在普通 PowerShell 中验证：

```powershell
docker version
docker compose version
```

你应该同时看到版本信息。例如：

```text
Client: Docker Engine ...
Server: Docker Engine ...

Docker Compose version v2.x.x
```

这里有一个容易混淆的点：

- `docker version` 只有 `Client`，没有 `Server`：Docker Desktop 没启动完成。
- `docker` 不是内部或外部命令：重新打开 PowerShell；仍不行就检查 Docker Desktop 是否安装成功。
- `docker compose` 和 `docker-compose` 不一样：本教程使用现在 Docker Desktop 自带的 **`docker compose`**（中间是空格）。

确认成功后，进入后端仓库：

```powershell
Set-Location D:\LearnHub\LearnHubBackend
```

后文所有命令默认都在这个目录运行。

---

## 1. 为什么已经会 `docker run`，还要学 Compose？

假设你只用 Docker 命令启动 Redis：

```powershell
docker run -d --name learnhub-redis -p 6379:6379 redis:7.4-alpine
```

这条命令不是魔法，把它翻译成人话就是：

| 片段 | 含义 |
|---|---|
| `docker run` | 从镜像创建一个新容器，并启动它 |
| `-d` | 后台运行，终端不持续显示日志 |
| `--name learnhub-redis` | 给这个容器起名 |
| `-p 6379:6379` | Windows 的 6379 端口转发到容器的 6379 端口 |
| `redis:7.4-alpine` | 使用 Redis 7.4 的轻量镜像 |

一条命令还可以接受。但 LearnHub 后续至少有 MySQL、Redis、RabbitMQ、MinIO、Qdrant。你会需要写五条很长的 `docker run` 命令，还要记住：密码、端口、数据目录、网络、启动顺序。

Docker Compose 就是把这些“启动规则”写进一个名为 `compose.yaml` 的文件。以后只需：

```powershell
docker compose up -d
```

它会按照文件描述，创建网络、数据卷和所有容器。

可以把二者理解为：

```text
docker run      = 手动写一次启动命令
Docker Compose  = 把一组启动命令保存成项目可重复使用的配置
```

Compose **不是另一种容器技术**，它仍然使用 Docker。它只是特别擅长管理“一组彼此有关的容器”。

---

## 2. 先建立正确的心智模型

今天后面每个概念都绕不开这四样东西：镜像、容器、端口、数据卷。

### 2.1 镜像（image）像菜谱，不是已经做好的菜

`redis:7.4-alpine`、`mysql:8.4` 都是镜像名称。镜像包含程序和运行它所需的基础文件，但它本身不会运行。

同一个镜像可以启动多个容器。例如一份 Redis 镜像，理论上可以启动测试 Redis 和开发 Redis 两个容器。

### 2.2 容器（container）是一次真正运行

容器是“根据镜像开出来的一次实例”。它有自己的进程、文件系统和网络。

执行下面这条命令后，看到的每一行就是一个容器：

```powershell
docker ps -a
```

注意 `-a`：没有它时，只显示正在运行的容器；加了它还会显示已经停止的容器。

### 2.3 端口：`左边:右边`，永远先问“从哪里访问”

在 Compose 中：

```yaml
ports:
  - "6379:6379"
```

左边和右边不是重复写错了：

```text
Windows（宿主机）6379  ──转发──>  Redis 容器内部 6379
        左边                         右边
```

所以在 Windows 的浏览器或 Spring Boot 里访问 Redis，写 `localhost:6379`。

如果你把它写成：

```yaml
ports:
  - "6380:6379"
```

含义是 Windows 使用 6380，Redis 容器内部仍然是 6379。Windows 上的程序要连 `localhost:6380`。

### 2.4 数据卷（volume）：容器可换，数据要留下

容器被删除后，容器内部没有额外挂载的数据通常也会消失。数据库数据显然不能这样处理。

```yaml
volumes:
  - redis-data:/data
```

`redis-data` 是 Docker 管理的命名卷；`/data` 是 Redis 容器里的目录。Redis 把数据写到 `/data`，实际由 Docker 放进 `redis-data` 卷中。

关系如下：

```text
Redis 容器（可以删除、重建）
          │ 写入 /data
          ▼
redis-data 命名卷（独立保存，通常会保留）
```

### 2.5 `localhost` 总是指“当前这台机器”

这是初学 Compose 最常犯、也最值得彻底理解的错误。

| 谁在访问 | 写什么地址 | 原因 |
|---|---|---|
| 你 Windows 上运行的 Spring Boot | `localhost:3306` | 它先访问 Windows，再由端口映射进入 MySQL 容器 |
| Redis 容器访问 MySQL 容器 | `mysql:3306` | 同一 Compose 网络里，用服务名找另一个容器 |
| MySQL 容器里的程序访问 `localhost` | MySQL 容器自己 | 它不会跳回 Windows，也不会跳到 Redis |

后面看到 `mysql:3306`，不要把它理解为一个神秘网址：`mysql` 正是 Compose 文件里的服务名称。

---

## 3. 第一个小实验：只启动 Redis

不要一开始就启动五个服务。先用一个非常小的 Compose 文件，把每一步的效果看清楚。

### 3.1 创建练习目录和文件

在仓库根目录创建一个仅用于本课练习的目录：

```powershell
New-Item -ItemType Directory -Path .\docker-compose-lab -Force
```

在 `D:\LearnHub\LearnHubBackend\docker-compose-lab` 新建文件 `compose.yaml`，填入：

```yaml
name: learnhub-lab

services:
  redis:
    image: redis:7.4-alpine
    ports:
      - "6379:6379"
```

YAML 对缩进敏感。请使用空格，不使用 Tab。这里的层级是：

```text
name
services
└── redis
    ├── image
    └── ports
        └── 一条端口映射
```

逐行解释：

```yaml
name: learnhub-lab
```

这是 Compose 项目名称。Docker 会为这个项目生成诸如 `learnhub-lab-redis-1` 的容器名。

```yaml
services:
```

所有要运行的服务都写在这里。你可以把服务理解为“容器的设计说明”。

```yaml
  redis:
```

`redis` 是服务名。它既是当前 Compose 项目里的标识，也是将来其他容器连接它时使用的主机名。

```yaml
    image: redis:7.4-alpine
```

指定由哪个镜像创建容器。固定 `7.4-alpine` 是为了让所有人得到相近的版本；不要在学习项目里随意写 `latest`，因为它会随时间改变。

```yaml
    ports:
      - "6379:6379"
```

把容器端口发布到 Windows。短横线表示这是一个列表项；即使现在只有一个端口，Compose 的格式也是列表，因为服务可能有多个端口。

### 3.2 先验证配置，不要急着启动

进入练习目录：

```powershell
Set-Location .\docker-compose-lab
```

运行：

```powershell
docker compose config
```

这一步只解析和展示最终配置，**不会创建容器**。它相当于让 Compose 先检查：YAML 有没有写坏、字段是否认识。

若看到 `services.redis.image: redis:7.4-alpine` 等内容，说明通过。

常见报错：

- `mapping values are not allowed here`：通常是冒号后或缩进写错。
- `found character '\t'`：用了 Tab，改为空格。
- `docker: command not found` 或连接 Engine 失败：回到第 0 节，先解决 Docker Desktop/WSL。

### 3.3 启动并观察发生了什么

```powershell
docker compose up -d
```

`up` 会做这几件事：

1. 没有镜像就下载 Redis 镜像；
2. 创建 Compose 默认网络；
3. 按 `redis` 服务定义创建容器；
4. 启动容器。

`-d` 是 detached（后台模式）。没有 `-d` 时日志会占满当前终端；按 Ctrl+C 还可能停止容器。学习阶段更推荐 `-d`，然后主动查看日志。

查看本项目状态：

```powershell
docker compose ps
```

预期能看到 `redis` 为 `running` 或 `Up`，端口一列中有 `0.0.0.0:6379->6379/tcp`。

### 3.4 不安装 Redis 客户端，也能验证 Redis

你不需要在 Windows 安装 `redis-cli`。它已经在 Redis 容器里：

```powershell
docker compose exec redis redis-cli ping
```

把命令拆开看：

| 部分 | 意思 |
|---|---|
| `docker compose` | 操作当前目录的 Compose 项目 |
| `exec` | 到一个“正在运行”的容器里执行命令 |
| `redis` | 服务名，不是容器 ID |
| `redis-cli ping` | 容器中执行的实际 Redis 命令 |

正确结果：

```text
PONG
```

再做一次真实的读写：

```powershell
docker compose exec redis redis-cli SET lesson:day6 "hello-compose"
docker compose exec redis redis-cli GET lesson:day6
```

第二条应输出：

```text
hello-compose
```

现在你已经真正使用了 Compose，而不是只“成功启动了一个黑盒”。

### 3.5 停止练习，并观察为什么数据丢了

执行：

```powershell
docker compose down
```

`down` 会停止并删除这次实验创建的容器和网络。它不会删除镜像。

重新启动，再读取键：

```powershell
docker compose up -d
docker compose exec redis redis-cli GET lesson:day6
```

这次通常拿不到 `hello-compose`，因为第一个练习没有配置数据卷；容器删掉后，容器内部数据也跟着没了。这正是下一节要解决的问题。

清理这个小实验：

```powershell
docker compose down
Set-Location ..
```

保留 `docker-compose-lab` 目录也没问题，它是你的学习笔记；如果你想保持仓库干净，也可以手动删除它。它不属于最终项目配置。

---

## 4. 第二个小实验：给 Redis 加数据卷

现在把 `docker-compose-lab\compose.yaml` 改成下面这样。只有加号所在概念是新增内容，实际文件里不要写加号：

```yaml
name: learnhub-lab

services:
  redis:
    image: redis:7.4-alpine
    command: ["redis-server", "--appendonly", "yes"]
    ports:
      - "6379:6379"
    volumes:
      - redis-lab-data:/data

volumes:
  redis-lab-data:
```

这里新增两件事。

### 4.1 `command`：替换镜像默认启动命令

```yaml
command: ["redis-server", "--appendonly", "yes"]
```

Redis 默认主要在内存里保存数据。`--appendonly yes` 开启 AOF（Append Only File）持久化：每次写入会记录到文件。注意它只是让 Redis 有东西可以写入数据卷；**真正让数据跨容器保留的是下一段的 `volumes`**。

方括号写法是 YAML 的短列表写法，等价于：

```yaml
command:
  - redis-server
  - --appendonly
  - "yes"
```

两种都对。本教程在命令参数短时使用第一种，便于阅读。

### 4.2 服务里的 `volumes` 与最外层的 `volumes`

```yaml
    volumes:
      - redis-lab-data:/data
```

这句是“把哪个卷挂到容器哪里”。

```yaml
volumes:
  redis-lab-data:
```

最外层这段是“声明这个命名卷存在，由 Docker 管理”。不要把它缩进到 `redis` 下面；缩进错了，YAML 的意思就变了。

进入实验目录后运行：

```powershell
Set-Location .\docker-compose-lab
docker compose up -d
docker compose exec redis redis-cli SET lesson:volume "I-survive"
docker compose down
docker compose up -d
docker compose exec redis redis-cli GET lesson:volume
```

这次应看到：

```text
I-survive
```

你可以查看 Docker 知道哪些卷：

```powershell
docker volume ls
```

实际卷名通常不是裸 `redis-lab-data`，而是 `learnhub-lab_redis-lab-data`；Compose 用项目名加前缀，避免不同项目恰好同名时冲突。

> 重要：`docker compose down` 默认保留命名卷；`docker compose down -v` 会连卷一起删掉。后者意味着 MySQL 等服务的数据会被删除。Day 6 日常练习**不要执行 `down -v`**。

练习完再运行一次 `docker compose down`，并回到项目根目录：

```powershell
docker compose down
Set-Location ..
```

---

## 5. LearnHub 真正的配置：先管理变量，再管理服务

前两个实验里端口写死，是为了专注理解 Compose。真实项目里，密码和本机端口不应直接散落在 `compose.yaml` 里。

### 5.1 `.env.example` 和 `.env` 分别是什么

在 `D:\LearnHub\LearnHubBackend` 创建 `.env.example`：

```dotenv
# Windows host ports. If a port is occupied, change only the value on the right.
MYSQL_PORT=3306
REDIS_PORT=6379
RABBITMQ_PORT=5672
RABBITMQ_MANAGEMENT_PORT=15672
MINIO_API_PORT=9000
MINIO_CONSOLE_PORT=9001
QDRANT_HTTP_PORT=6333
QDRANT_GRPC_PORT=6334

# Local-development credentials. Do not use these values in production.
MYSQL_DATABASE=learnhub
MYSQL_USER=learnhub
MYSQL_PASSWORD=replace-with-your-local-mysql-password
MYSQL_ROOT_PASSWORD=replace-with-your-local-root-password

RABBITMQ_DEFAULT_USER=learnhub
RABBITMQ_DEFAULT_PASS=replace-with-your-local-rabbitmq-password

MINIO_ROOT_USER=learnhub
MINIO_ROOT_PASSWORD=replace-with-a-long-local-minio-password
```

`.env.example` 是“变量清单和格式示例”，可以提交到 Git。它让后来下载项目的人知道必须配置什么。

接着复制一份真实配置：

```powershell
Copy-Item .env.example .env
```

打开 `.env`，把四个密码都改成你自己的本地密码。格式必须是：

```dotenv
变量名=值
```

等号左右不要加空格。密码如果含有 `$`、`#` 或空格，初学阶段建议先换成不含这些字符的长密码，以免被 Compose 的变量语法干扰。

检查 `.gitignore` 是否已经有这行：

```gitignore
.env
```

然后执行：

```powershell
git status --short
```

理想结果是：能看到 `.env.example`（如果它是新文件），但**看不到 `.env`**。

### 5.2 一个特别重要的误解：Compose `.env` 不等于 Spring Boot 配置

Compose 会自动读取同目录的 `.env`，用于替换 `compose.yaml` 中的 `${MYSQL_PORT}` 这类占位符。

但你用 IDEA 启动 Spring Boot 时，Java 程序并不会因为目录里有 `.env` 就自动读取它。以后 Java 要连接 MySQL，需要在 IDEA 的运行配置、PowerShell 环境变量或 Spring 配置文件中另行提供连接信息。

今天 `.env` 的职责只有一个：**给 Compose 配置赋值**。

---

## 6. 创建最终的 `compose.yaml`

在 `D:\LearnHub\LearnHubBackend` 创建 `compose.yaml`。先完整复制以下内容，下一节会逐个服务解释；不要边抄边“凭感觉删字段”。

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

---

## 7. 逐段读懂最终配置

你已经理解 Redis 的 `image`、`ports`、`volumes`，所以五个服务只是同一组规律的重复应用，不是五套完全不同的知识。

### 7.1 `environment`：把变量传进容器

以 MySQL 为例：

```yaml
environment:
  MYSQL_DATABASE: ${MYSQL_DATABASE}
```

左边 `MYSQL_DATABASE` 是 MySQL 镜像认识的环境变量名；右边 `${MYSQL_DATABASE}` 是 Compose 从 `.env` 取出的值。

假设 `.env` 有：

```dotenv
MYSQL_DATABASE=learnhub
```

Compose 最终传进 MySQL 容器的是：

```text
MYSQL_DATABASE=learnhub
```

MySQL 第一次初始化时会据此创建 `learnhub` 数据库和指定用户。

> 因此，第一次启动 MySQL 后再修改 `.env` 的数据库名或 root 密码，通常不会改变已有数据卷中的 MySQL。不是 Compose 没读到变量，而是 MySQL 已经初始化完成。需要保留数据时不要随便重置；需要重新开始时，以后在明确确认后才会处理数据卷。

### 7.2 `${变量:-默认值}`：有值用值，没有就用默认值

```yaml
- "${MYSQL_PORT:-3306}:3306"
```

可以这样读：

```text
若 .env 定义 MYSQL_PORT，就用它；否则把左边端口当作 3306。
```

密码变量没有默认值：密码漏写应该尽早报错，而不是悄悄使用某个弱密码。

### 7.3 为什么健康检查的密码写成 `$${...}`

```yaml
test: ["CMD-SHELL", "mysqladmin ... -p$${MYSQL_ROOT_PASSWORD} --silent"]
```

Compose 看见 `${...}` 会在 Windows 上先替换它；但此处我们希望变量在 **MySQL 容器内部** 再由 shell 读取。

`$$` 是 Compose 的转义写法，它会把一个 `$` 原样交给容器。因此：

```text
$${MYSQL_ROOT_PASSWORD}
          ↓ Compose 处理后
$MYSQL_ROOT_PASSWORD
          ↓ MySQL 容器内 shell 处理后
真实 root 密码
```

这不是要你死记，记住原则即可：**变量要在哪一层被读取，就让它留到那一层。**

### 7.4 `healthcheck` 不等于“容器正在运行”

MySQL 进程刚启动时，容器可能已经是 Up，但数据库还在创建系统表，尚不能接受 SQL 连接。

健康检查会定时执行一条小命令。通过后 `docker compose ps` 才显示 `healthy`。

| 状态 | 意义 |
|---|---|
| `running` / `Up` | 容器主进程还在运行 |
| `health: starting` | 正在等待健康检查 |
| `healthy` | 检查命令成功 |
| `unhealthy` | 多次检查失败；应看日志 |

MinIO 和 Qdrant 本次没有配置 Compose healthcheck，所以它们显示 `Up` 也是正常的。我们会在浏览器/HTTP 请求中验证它们。

### 7.5 各服务现在和未来分别做什么

| 服务 | 今天是否连接 Java | 未来用途 |
|---|---:|---|
| MySQL | 否 | 用户、课程、订单等关系数据 |
| Redis | 否 | 缓存、验证码、限流等 |
| RabbitMQ | 否 | 异步消息，例如通知或学习进度事件 |
| MinIO | 否 | 图片、课程附件等对象存储 |
| Qdrant | 否 | 向量检索，后期 AI/知识库功能 |

今天全部启动，是为了建立一份可复现的本地基础设施；不是要求你今天掌握它们各自的业务用法。

---

## 8. 正式启动前必须做的检查

确认你已经回到仓库根目录：

```powershell
Set-Location D:\LearnHub\LearnHubBackend
```

先运行：

```powershell
docker compose config
```

这一步非常有价值：它检查 YAML，并把 `.env` 变量替换到配置中。它**不会启动容器**。

如果出现：

```text
The "MYSQL_PASSWORD" variable is not set
```

依次检查：

1. `.env` 是否和 `compose.yaml` 位于同一目录；
2. `.env` 是否确实由 `.env.example` 复制而来；
3. 变量名是否拼写一致；
4. 变量是否写成了 `MYSQL_PASSWORD = xxx`（等号两边有空格是错误习惯）。

不要把 `docker compose config` 的完整输出发到公开平台，因为其中可能包含你的真实密码。

---

## 9. 正式启动、查看状态、逐项验证

### 9.1 启动

```powershell
docker compose up -d
```

第一次下载五个镜像可能需要较久，尤其网络不稳定时。不要在下载期间反复关闭 Docker Desktop 或重复执行同一命令。

### 9.2 查看状态

```powershell
docker compose ps
```

刚启动时 MySQL、Redis、RabbitMQ 可能显示 `starting`，等 30～60 秒再运行一次。预期：前三个服务最终 `healthy`；MinIO/Qdrant 为 `Up`。

### 9.3 验证 MySQL

```powershell
docker compose exec mysql sh -c 'mysqladmin ping -h 127.0.0.1 -uroot -p"$MYSQL_ROOT_PASSWORD"'
```

预期：

```text
mysqld is alive
```

再查看数据库是否被创建：

```powershell
docker compose exec mysql sh -c 'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -e "SHOW DATABASES;"'
```

结果里应有 `learnhub`（若你在 `.env` 里换了数据库名，则是你的名字）。

### 9.4 验证 Redis

```powershell
docker compose exec redis redis-cli ping
```

预期：

```text
PONG
```

### 9.5 验证 RabbitMQ

```powershell
docker compose exec rabbitmq rabbitmq-diagnostics -q ping
```

然后在浏览器打开：<http://localhost:15672>。

输入 `.env` 里的 `RABBITMQ_DEFAULT_USER` 与 `RABBITMQ_DEFAULT_PASS`。请理解：5672 是程序连接 RabbitMQ 的端口，15672 是给人看的管理网页；它们不是重复端口。

### 9.6 验证 MinIO

PowerShell 执行：

```powershell
curl.exe -i http://localhost:9000/minio/health/live
```

看到 `HTTP/1.1 200` 说明 MinIO 存活。

浏览器打开 <http://localhost:9001>，用 `.env` 中的 `MINIO_ROOT_USER` 和 `MINIO_ROOT_PASSWORD` 登录。此时不必创建 Bucket，后续文件功能再做。

### 9.7 验证 Qdrant

```powershell
curl.exe -i http://localhost:6333/healthz
```

看到 `HTTP/1.1 200` 即成功。

浏览器可打开 <http://localhost:6333/dashboard>。本周不创建 collection。

---

## 10. 日常使用的六个命令

| 你想做什么 | 命令 | 它是否删除数据 |
|---|---|---|
| 启动或按配置创建服务 | `docker compose up -d` | 否 |
| 看状态 | `docker compose ps` | 否 |
| 看所有服务最近日志 | `docker compose logs --tail 100` | 否 |
| 持续看一个服务日志 | `docker compose logs -f mysql` | 否；Ctrl+C 只停止看日志 |
| 暂停现有容器 | `docker compose stop` | 否 |
| 恢复已暂停容器 | `docker compose start` | 否 |
| 停止并移除容器、网络 | `docker compose down` | 默认保留命名卷 |

`down` 后再 `up -d` 时，Compose 会重新创建容器，但复用原来的数据卷。这也是为什么它适合“今天关机，明天继续”。

请把下面这条命令当成危险操作：

```powershell
docker compose down -v
```

`-v` 表示把命名卷也删掉；这会删除本地 MySQL、Redis、RabbitMQ、MinIO、Qdrant 的数据。只有你明确想把本地环境完全重置，并确认数据可以丢弃时才使用它。现在不要使用。

---

## 11. 两个最常见故障：按证据排查

### 11.1 端口被占用

典型信息：

```text
Bind for 0.0.0.0:3306 failed: port is already allocated
```

这句话的真实含义是：Windows 上已经有程序占用了 3306，Docker 无法再把 MySQL 暴露到相同端口。它不表示 MySQL 镜像坏了。

先查是谁占用：

```powershell
Get-NetTCPConnection -LocalPort 3306 -ErrorAction SilentlyContinue |
  Select-Object LocalAddress, LocalPort, State, OwningProcess
```

如果你只是想让 LearnHub 用另一个端口，不需要关闭原程序。修改 `.env`：

```dotenv
MYSQL_PORT=3307
```

然后执行：

```powershell
docker compose up -d
```

此时 Windows 上连接 MySQL 用：

```text
localhost:3307
```

容器之间仍使用：

```text
mysql:3306
```

只改左边（宿主机端口），不要改右边（镜像内服务监听端口）。Redis、RabbitMQ 等端口冲突也按同样方式在 `.env` 修改。

### 11.2 容器 `unhealthy` 或启动后立刻退出

不要先删卷或重装 Docker。按这个顺序：

```powershell
docker compose ps
docker compose logs --tail 100 mysql
```

把 `mysql` 换成实际失败的服务名。日志的最后几十行通常比“重启一下试试”更有用。

常见原因：

| 现象 | 常见原因 | 应做什么 |
|---|---|---|
| MySQL 退出 | 密码变量缺失、旧数据卷与新初始化变量冲突 | 先看日志；不要直接删卷 |
| `port is already allocated` | Windows 端口被占用 | 改 `.env` 左边的端口 |
| Docker daemon 连接失败 | Docker Desktop/WSL 没启动 | 回到第 0 节 |
| 拉镜像超时 | 网络或镜像仓库访问问题 | 重试，检查网络；不要随意换来路不明的镜像 |

---

## 12. 今天的理解检查

不要看答案，先自己用一句话回答：

1. 为什么 Docker Compose 不等于 Docker 的替代品？
2. `"3307:3306"` 中左右两边分别属于谁？
3. 为什么 Windows 中运行的 Spring Boot 使用 `localhost:3307`，而容器中的服务使用 `mysql:3306`？
4. `docker compose down` 后，为什么 MySQL 数据通常还在？
5. 为什么 `docker compose down -v` 危险？
6. `.env.example` 为什么可以提交 Git，而 `.env` 不应该提交？
7. Compose 的 `.env` 会自动给 IDEA 中启动的 Spring Boot 提供变量吗？
8. `docker compose ps` 显示 Up，是否一定说明 MySQL 已经能接受 SQL？为什么？
9. 端口冲突时，为什么应该改 `MYSQL_PORT=3307`，而非修改 `:3306` 右边的端口？
10. 遇到服务启动失败时，第一条查看日志的命令是什么？

参考答案：

1. Compose 仍然使用 Docker，只是把多个容器的配置和生命周期集中管理。
2. 左边是 Windows 宿主机端口，右边是容器内端口。
3. 两者所在网络不同；Compose 内部通过服务名 DNS 访问，不需经过 Windows 端口映射。
4. 数据放在命名卷中，`down` 默认不会删除卷。
5. `-v` 会删除命名卷，也就是各服务的本地持久数据。
6. example 是变量契约，真实 `.env` 含个人密码和本机设置。
7. 不会；它只供 Compose 插值，Spring Boot 要单独配置。
8. 不一定。容器进程在跑不等于数据库初始化结束，需要 healthcheck 或连接验证。
9. 右边是镜像内 Redis/MySQL 等服务的固定监听端口；冲突发生在 Windows 左边的端口。
10. 例如 `docker compose logs --tail 100 mysql`，把 mysql 换成失败服务。

---

## 13. Day 6 完成标准

只有下面项目都做到，才算完成 Day 6：

- [ ] Docker Desktop 可正常启动，`docker version` 有 Client 和 Server。
- [ ] 你完成过单 Redis 的 Compose 实验，得到过 `PONG`。
- [ ] 你完成过带数据卷的 Redis 实验，并亲眼验证 `down` 后数据仍在。
- [ ] 仓库根目录有 `compose.yaml`、`.env.example` 和本机 `.env`。
- [ ] `.env` 已被 `.gitignore` 忽略，未出现在 `git status` 中。
- [ ] `docker compose config` 无错误。
- [ ] 五个 LearnHub 服务启动，MySQL/Redis/RabbitMQ healthy，MinIO/Qdrant 的 HTTP 验证通过。
- [ ] 你知道用 `docker compose ps` 和 `docker compose logs --tail 100 服务名` 排查问题。
- [ ] 你没有执行 `docker compose down -v`。

完成后，你发给我以下**不含密码**的信息即可：

```text
1. docker compose ps 的输出
2. 五项验证命令各自的结果（可省略密码）
3. 第 12 节十道题中你觉得最不确定的答案
```

我会按你的实际输出继续带你排错或进入 Day 7。
