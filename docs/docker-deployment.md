# LearnHub Docker 部署

LearnHub 可以通过仓库根目录的 `compose.yaml` 一次启动：

- Nginx 前端；
- Spring Boot 后端；
- MySQL；
- Redis；
- RabbitMQ；
- MinIO；
- Qdrant。

## 运行要求

- Docker Engine / Docker Desktop；
- Docker Compose v2；
- 可访问所配置的大模型服务。

不需要在宿主机安装 Java、Maven、Node.js、MySQL 或 Redis。

## 面试演示：双击启动

仓库根目录提供了 Windows 演示脚本：

```text
start-demo.cmd       # 自动检查 Docker、启动服务并打开浏览器
stop-demo.cmd        # 停止服务，但保留数据库、文档和向量数据
rebuild-demo.cmd     # 代码更新后重新构建镜像并启动
```

第一次使用前，只需要准备好根目录 `.env`；如果不存在，`start-demo.cmd` 会从 `.env.example` 创建模板并提示你填写配置。之后的面试演示通常只需要双击 `start-demo.cmd`。

## 第一次启动

在仓库根目录执行：

```powershell
Copy-Item .env.example .env
```

编辑 `.env`，至少必须修改：

```text
MYSQL_PASSWORD
MYSQL_ROOT_PASSWORD
RABBITMQ_DEFAULT_PASS
MINIO_ROOT_PASSWORD
API_KEY
JWT_SECRET
```

随后构建并启动：

```powershell
docker compose up -d --build
```

查看状态：

```powershell
docker compose ps
```

查看后端启动日志：

```powershell
docker compose logs -f backend
```

默认访问地址：

```text
LearnHub:    http://127.0.0.1:8088
Swagger UI: http://127.0.0.1:8088/swagger-ui/index.html
Health:     http://127.0.0.1:8088/actuator/health
```

这些地址是本地运行地址。部署到服务器时，将 `localhost` 换成服务器域名或 IP，并通过云防火墙开放 `APP_PORT`。

## 本机管理界面

Compose 不公开 MySQL、Redis、RabbitMQ AMQP 或 Qdrant 端口。MinIO API、RabbitMQ 管理页和 MinIO 控制台默认只绑定宿主机 `127.0.0.1`，不会监听所有网卡：

管理地址：

```text
RabbitMQ: http://localhost:15672
MinIO API:     http://127.0.0.1:9000
MinIO Console: http://localhost:9001
```

文档分享链接由 `MINIO_PUBLIC_ENDPOINT` 生成。本机运行保持 `http://127.0.0.1:9000` 即可；部署到远程服务器时，应把它改成浏览器能够访问的 MinIO 域名或服务器地址，并相应配置 `MINIO_BIND_ADDRESS` 或外层反向代理。

## 常用操作

```powershell
# 停止容器，保留数据
docker compose down

# 重新构建应用镜像
docker compose up -d --build

# 查看全部日志
docker compose logs -f

# 查看后端日志卷中的应用日志
docker compose exec backend sh -c 'ls -lah /app/logs'

# 停止并删除全部数据（危险：数据库、文件与向量都会丢失）
docker compose down -v
```

## 更新项目

```powershell
git pull
docker compose up -d --build
```

Flyway 会在后端启动时自动执行尚未应用的数据库迁移。

## 服务器部署建议

当前 Compose 适合：

- 本地完整体验；
- 局域网运行；
- 单机 Linux 服务器；
- 个人项目和作品演示。

如果要公开提供服务，建议进一步配置：

1. 域名和 HTTPS（Caddy、Traefik 或宿主机 Nginx）；
2. 云安全组仅开放 `80/443`，不要公开 MySQL、Redis、RabbitMQ、MinIO、Qdrant；
3. 强随机密码与 JWT secret；
4. 数据卷定期备份；
5. API Key 使用服务器 Secret 管理，而不是写进镜像或 Git；
6. Swagger 与 Actuator 的公开访问策略；
7. 资源限制、监控、告警和日志归档。

## 架构说明

浏览器只访问 Nginx 前端。Nginx 提供静态资源，并把以下路径反向代理到后端：

```text
/api/
/v3/
/swagger-ui/
/actuator/
```

同源代理避免生产环境依赖 Vite dev server，也不需要额外配置浏览器 CORS。`/api/` 已关闭 Nginx 响应缓冲，以支持 SSE 流式回答。
