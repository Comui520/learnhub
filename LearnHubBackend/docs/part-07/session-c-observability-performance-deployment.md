# Session C：可观测性、性能与部署

> 目标：当接口慢、AI 超时、额度扣减失败或 Docker 重启后，你能回答“发生了什么、影响了什么、如何恢复”。
>
> 这一节先做开发环境可观测性，再做小规模压测，最后整理新机器启动文档。不要把压测理解成“发很多请求”；压测必须有目标、指标和验收条件。

## 0. 先认识三类工具

### 0.1 Actuator

Actuator 是 Spring Boot 的运维端点集合：健康检查、指标、应用信息。它不是日志系统，也不是压测工具。

### 0.2 Micrometer

Micrometer 是指标 API。你的代码记录 Counter/Timer，Spring Boot 根据配置把它导出成 Prometheus 格式。

```text
业务代码 -> MeterRegistry -> Prometheus endpoint -> Grafana
```

### 0.3 k6

k6 是 HTTP 压测客户端。它生成虚拟用户，向接口发请求，统计延迟和失败率。它不替你验证数据库余额，因此业务正确性仍要用 SQL 或 Java 测试断言。

## 1. Step 1：确认 Actuator 依赖和配置

### 1.1 依赖

`learnhub-application/pom.xml` 已经有：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

如果你的本地 POM 没有，补上后执行：

```powershell
mvn compile -pl learnhub-application -am -DskipTests
```

### 1.2 修改 `application-dev.yml`

加入：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: never
  info:
    env:
      enabled: true
```

为什么不写 `include: "*"`？因为环境、Bean、配置、线程等端点可能泄露内部信息。开发环境可以临时扩大，提交代码时仍应保持最小暴露。

### 1.3 启动并验证

```powershell
curl.exe -i http://127.0.0.1:8080/actuator/health
curl.exe -i http://127.0.0.1:8080/actuator/info
curl.exe -i http://127.0.0.1:8080/actuator/metrics
curl.exe -i http://127.0.0.1:8080/actuator/prometheus
```

预期：

- `health` 返回 HTTP 200。
- `metrics` 返回可查询的指标名称。
- `prometheus` 返回多行文本，而不是 JSON。

如果返回 404：确认应用加载的是 `dev` profile，确认 `spring-boot-starter-actuator` 在实际运行的 application 模块，而不是只写在一个没有被引入的模块里。

## 2. Step 2：为额度扣减添加业务指标

### 2.1 为什么要记录业务指标

HTTP 500 只能告诉你“请求失败了”，不能告诉你是余额不足、SQL 失败还是 AI API 失败。我们给额度扣减记录：

- 成功次数。
- 余额不足次数。
- 重复业务号次数。
- 扣减耗时。

### 2.2 修改 `CreditService`

注入 `MeterRegistry`：

```java
private final Counter consumeSuccess;
private final Counter consumeInsufficient;
private final Timer consumeTimer;

public CreditService(
        CreditAccountMapper accountMapper,
        OrderMapper orderMapper,
        CreditTransactionMapper transactionMapper,
        MeterRegistry meterRegistry
) {
    this.accountMapper = accountMapper;
    this.orderMapper = orderMapper;
    this.transactionMapper = transactionMapper;
    this.consumeSuccess = meterRegistry.counter(
            "learnhub.credit.consume", "result", "success");
    this.consumeInsufficient = meterRegistry.counter(
            "learnhub.credit.consume", "result", "insufficient");
    this.consumeTimer = meterRegistry.timer("learnhub.credit.consume.duration");
}
```

在 `consume` 方法中包住主要逻辑：

```java
return consumeTimer.record(() -> {
    if (transactionMapper.selectCount(/* bizNo 条件 */) > 0) {
        return true;
    }

    int rows = accountMapper.deductBalance(userId, amount);
    if (rows == 0) {
        consumeInsufficient.increment();
        return false;
    }

    // 查询账户、记录流水
    consumeSuccess.increment();
    return true;
});
```

如果你不想马上重构整个方法，也可以先在成功和余额不足的两个返回点分别 `increment()`，Timer 放到下一次重构做。学习目标是理解“业务事件指标”和“HTTP 指标”的区别。

### 2.3 tag 的边界

正确：

```text
learnhub.credit.consume{result="success"}
learnhub.credit.consume{result="insufficient"}
```

错误：

```text
learnhub.credit.consume{userId="1"}
learnhub.credit.consume{orderNo="..."}
```

用户 ID、订单号数量没有上限，会产生大量时间序列，最终拖垮监控系统。

### 2.4 查看指标

启动应用后：

```powershell
curl.exe -s http://127.0.0.1:8080/actuator/metrics/learnhub.credit.consume
curl.exe -s http://127.0.0.1:8080/actuator/prometheus | Select-String "learnhub_credit_consume"
```

Prometheus 会把点号转换成下划线，并添加 `_count`、`_sum` 等统计字段。

## 3. Step 3：日志和请求定位

### 3.1 关键日志应该记录什么

以 Chat 为例：

```text
requestId=... userId=1 kbId=1 hits=5 model=... elapsedMs=...
```

额度：

```text
requestId=... userId=1 bizNo=... result=INSUFFICIENT
```

不能记录：

- 密码。
- JWT 完整值。
- `Authorization` 请求头。
- API Key。
- 可能包含隐私的完整文档正文。

### 3.2 最小 requestId 做法

如果当前项目还没有 Trace 系统，可以先写一个 `OncePerRequestFilter`：

```java
String requestId = Optional.ofNullable(request.getHeader("X-Request-Id"))
        .orElse(UUID.randomUUID().toString());
MDC.put("requestId", requestId);
try {
    filterChain.doFilter(request, response);
} finally {
    MDC.remove("requestId");
}
```

在 `logback-spring.xml` 的 pattern 中加入：

```text
[%X{requestId}]
```

这样同一个请求经过 Controller、Service、异常处理器时，可以用一个 ID 搜索日志。不要把 MDC 放到异步线程后就假设它自动传播；RabbitMQ/线程池需要显式传递或使用 tracing 工具。

## 4. Step 4：用 EXPLAIN 检查真实 SQL

### 4.1 绑定文件查询

从日志拿到 SQL 后，在 MySQL 执行：

```sql
EXPLAIN
SELECT id, knowledge_base_id, file_id, created_at
FROM knowledge_base_document
WHERE knowledge_base_id = 1;
```

重点看：

- `key` 是否使用了 `knowledge_base_id` 相关索引。
- `rows` 是否远大于实际数据量。
- `type` 是否是 `ALL`（全表扫描）。

### 4.2 订单关单查询

```sql
EXPLAIN
UPDATE credit_order
SET status = 'CLOSED'
WHERE status = 'CREATED'
  AND expire_at < NOW();
```

当前表有 `(status, expire_at)` 联合索引，正好服务这个条件。不要只看“表有索引”，要看执行计划是否真的选择它。

### 4.3 慢 SQL 的处理顺序

```text
1. 记录原始 SQL 和参数
2. EXPLAIN
3. 检查索引列顺序
4. 检查是否返回了不需要的列
5. 检查是否 N+1 查询
6. 改动后再次 EXPLAIN 和压测
```

不要没有执行计划就盲目加索引；索引会增加写入成本和磁盘占用。

## 5. Step 5：安装和运行 k6

### 5.1 安装

Windows 可以使用 winget：

```powershell
winget install k6.k6
```

安装后验证：

```powershell
k6 version
```

如果公司电脑没有 winget，使用 k6 官方安装包；不要把 k6 二进制提交进仓库。

### 5.2 先压 health，不碰数据库业务

新建目录和文件：

```text
perf/k6/health.js
```

内容：

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 5,
  duration: '10s',
};

export default function () {
  const response = http.get('http://127.0.0.1:8080/actuator/health');
  check(response, {
    'status is 200': (r) => r.status === 200,
    'body is UP': (r) => r.body.includes('UP'),
  });
  sleep(1);
}
```

运行：

```powershell
k6 run perf/k6/health.js
```

第一轮只确认压测工具、端口和服务没有问题。不要一开始压 Chat。

### 5.3 压查询余额

新建 `perf/k6/credit-balance.js`：

```javascript
import http from 'k6/http';
import { check } from 'k6';

const token = __ENV.JWT_TOKEN;

export const options = {
  vus: 5,
  duration: '10s',
};

export default function () {
  const response = http.get(
    'http://127.0.0.1:8080/api/v1/credit/balance',
    { headers: { Authorization: `Bearer ${token}` } }
  );
  check(response, { 'status is 200': (r) => r.status === 200 });
}
```

PowerShell 运行：

```powershell
$env:JWT_TOKEN = '<新登录得到的token>'
k6 run perf/k6/credit-balance.js
```

记录输出里的 `http_req_duration p(95)`、`http_req_failed` 和请求总数。

### 5.4 为什么不能直接用 k6 测并发扣减

k6 能发 10 个请求，但它不会自动验证“余额最终是不是 0、流水是不是 1”。额度并发验收应该使用 Session B 的 Java 并发测试，或者压测完成后执行：

```sql
SELECT balance FROM credit_account WHERE user_id = 1;
SELECT COUNT(*) FROM credit_transaction
WHERE user_id = 1 AND type = 'CONSUME';
```

压测前先准备独立测试用户和足够明确的余额，避免污染日常开发数据。

### 5.5 Chat 压测的安全限制

Chat 会调用 Embedding 和聊天模型，可能消耗费用并受第三方限流。只在以下条件满足时压：

- 使用测试知识库。
- 用户额度明确。
- `vus` 从 1 开始。
- 持续时间很短。
- 能在日志中区分每次请求。

先压 `vus: 1, duration: '5s'`，确认流式连接正常，再逐步增加。看到外部 API `429` 或连接重置就停止，不要盲目加并发。

## 6. Step 6：Docker 和数据卷

### 6.1 日常启动

在 `D:\LearnHub\LearnHubBackend`：

```powershell
docker compose up -d
docker compose ps
```

看到 MySQL、Redis、RabbitMQ、MinIO、Qdrant 都在运行后，再启动 IDEA 中的 Spring Boot。

### 6.2 停止但保留数据

```powershell
docker compose down
```

它会删除容器和网络，通常保留命名卷。

### 6.3 危险命令

```powershell
docker compose down -v
docker volume prune
docker system prune --volumes
```

这些命令可能删除 MySQL、MinIO、Qdrant 的数据。开发机上执行前必须确认你真的有备份。

### 6.4 备份 MySQL

```powershell
docker compose exec -T mysql mysqldump `
  -ulearnhub -p1234 learnhub > .\backup\learnhub-$(Get-Date -Format yyyyMMdd-HHmmss).sql
```

先创建备份目录：

```powershell
New-Item -ItemType Directory -Force .\backup
```

恢复：

```powershell
Get-Content .\backup\learnhub-20260908-120000.sql |
  docker compose exec -T mysql mysql -ulearnhub -p1234 learnhub
```

### 6.5 启动后的检查顺序

```powershell
docker compose ps
docker compose exec -T redis redis-cli ping
docker compose exec -T rabbitmq rabbitmq-diagnostics -q ping
curl.exe --noproxy "*" http://127.0.0.1:6333/collections/learnhub_docs
curl.exe http://127.0.0.1:8080/actuator/health
```

注意：访问 Docker 暴露的本机端口时优先使用 `127.0.0.1`，避免 Windows 下 `localhost` 解析到 IPv6 `::1` 导致连接行为不一致。

## 7. Step 7：写部署 README

在仓库根目录的 README 增加：

### 7.1 环境要求

```text
JDK 21
Maven 3.9+
Docker Desktop
可访问 SiliconFlow 的网络
```

### 7.2 配置顺序

```text
1. 复制 .env.example -> .env
2. 填写 MYSQL、RabbitMQ、MinIO、JWT、API_KEY
3. 确认 .env 不提交 Git
4. docker compose up -d
5. 等依赖 healthy
6. mvn clean package -DskipTests
7. 使用 dev profile 启动 learnhub-application
```

### 7.3 验收地址

```text
http://127.0.0.1:8080/actuator/health
http://127.0.0.1:8080/swagger-ui/index.html
http://127.0.0.1:8080/chat-test.html
```

### 7.4 故障排查表

| 现象 | 先查什么 |
|---|---|
| Access denied for MySQL | `.env`、IDEA 环境变量、数据库用户密码 |
| Flyway 不迁移 | migration 版本、资源目录、`flyway_schema_history` |
| Swagger 白屏 | `/v3/api-docs` 是否 200、浏览器控制台 |
| Chat 返回 200 但 Swagger Error | Swagger UI 不解析 SSE，用 chat-test/curl |
| Qdrant hits=0 | payload 的 `fileId` 类型和过滤器字符串是否一致 |
| Docker 数据消失 | 是否执行过 `down -v`、命名卷是否还在 |
| Testcontainers 启动失败 | Docker Desktop、镜像拉取、代理 |

## 8. Step 8：记录一次性能结果

在 `docs/performance/part-07-baseline.md` 记录：

```markdown
# Part 7 性能基线

日期：2026-09-08
环境：Windows / JDK 21 / Docker Desktop
接口：GET /actuator/health
并发：5 VU，10 秒
请求总数：
失败率：
p50：
p95：
p99：
备注：
```

然后再记录余额接口和额度并发测试。不要只写“很快”；用数字、环境、命令和结果说话。

## 9. 完成标准

- [ ] `/actuator/health`、`/actuator/metrics`、`/actuator/prometheus` 按配置可访问。
- [ ] 至少一个额度业务指标能在 Prometheus 输出中找到。
- [ ] 至少一个 SQL 有 EXPLAIN 结果和索引解释。
- [ ] k6 health 和余额脚本各成功运行一次。
- [ ] Chat 压测从 1 VU 小规模开始，并记录第三方 API 限流情况。
- [ ] MySQL 备份和恢复命令实际执行过一次。
- [ ] README 能让新机器启动 Docker 和应用。