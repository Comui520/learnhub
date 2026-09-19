# LearnHub 性能基线

这些脚本用于产生**可复现的本地开发环境基线**，不是生产容量承诺。

## 推荐运行方式

### 方式一：本机安装 k6

安装 k6 后，在仓库根目录执行：

```powershell
k6 run perf/k6/health.js
k6 run perf/k6/credit-balance.js
$env:KNOWLEDGE_BASE_ID = "1"
k6 run perf/k6/study-library.js
```

可以通过环境变量调整测试：

```powershell
$env:BASE_URL = "http://127.0.0.1:8088"
$env:VUS = "10"
$env:DURATION = "30s"
k6 run perf/k6/credit-balance.js
```

### 方式二：使用 Docker 运行 k6

不需要在宿主机安装 k6。当前 Compose 将前端绑定到 `127.0.0.1:8088`，因此推荐让 k6 加入 LearnHub 的 Docker 网络，直接访问 Compose 服务名：

```powershell
docker run --rm -i --network learnhub_learnhub `
  -e BASE_URL=http://frontend `
  -v "${PWD}/perf/k6:/scripts" `
  grafana/k6 run /scripts/health.js
```

认证接口：

```powershell
docker run --rm -i --network learnhub_learnhub `
  -e BASE_URL=http://frontend `
  -e LEARNHUB_USERNAME=learnhub `
  -e LEARNHUB_PASSWORD=password123 `
  -v "${PWD}/perf/k6:/scripts" `
  grafana/k6 run /scripts/credit-balance.js
```

## 当前脚本覆盖范围

| 脚本 | 类型 | 是否默认安全 | 覆盖内容 |
|---|---|---|---|
| `health.js` | 纯读取 | 是 | Actuator 健康检查 |
| `auth-login.js` | 认证读取 / 写入登录日志 | 是，默认仅 20 次 | 登录、JWT 生成、登录限制链路 |
| `credit-balance.js` | 读取 | 是 | JWT、Redis / 数据库额度查询 |
| `read-journey.js` | 混合读取 | 是 | 用户信息、额度、知识库、文档、任务、题库分页、题目详情 |
| `study-library.js` | 读取 | 是 | Study 题库分页 |
| `document-upload-smoke.js` | 写入 | 否 | 单次文档上传冒烟测试，会产生测试文档 |
| `credit-idempotency.js` | 写入 | 否 | 创建订单并重复发送 5 次支付回调，会改变测试账号额度 |
| `chat-sse.js` | 外部 AI | 否 | 一次真实 SSE Chat，会消耗额度并调用模型供应商 |
| `chat-load.js` | 外部 AI / 限流 | 否 | 多 VU Chat 阶梯测试；同一用户会触发 5 次 / 10 秒限流 |
| `chat-multi-user.js` | 外部 AI / 多用户 | 否 | 多账号、多知识库 Chat 压测；验证真实并发和每用户限流 |
| `study-generate-once.js` | 外部 AI | 否 | 只生成 1 道单选题，会调用模型并落库 |

`read-journey.js` 是最适合面试展示的综合读取场景；它不是单接口 QPS，而是模拟一次登录用户打开工作台后连续读取多个模块。

## 第一轮安全范围

第一轮只测：

- `/actuator/health`；
- `/api/v1/credit/balance`；
- Study 题库分页接口。

这些请求不会调用外部大模型，也不会修改业务数据。

不要直接压以下接口：

- AI Chat；
- AI 生成题目；
- 文档上传与解析；
- 额度扣减；
- 支付回调。

这些接口会产生外部 API 成本、异步任务、数据库写入或业务数据污染，应单独准备测试账号、测试知识库和清理方案。

## 推荐执行顺序

```text
1. health.js
2. auth-login.js
3. credit-balance.js
4. study-library.js
5. read-journey.js
6. document-upload-smoke.js（准备测试数据后）
7. credit-idempotency.js（准备测试账号和 SQL 校验后）
8. chat-sse.js（确认模型费用和额度后）
9. study-generate-once.js（确认模型费用和测试题库后）
```

前 5 个脚本不会调用大模型，也不会主动写入知识库、题目或订单；后 3 个脚本必须人工确认影响范围后再执行。

## 需要记录的指标

每次测试至少记录：

- 测试日期；
- Git commit；
- 操作系统、CPU、内存；
- Docker Desktop 版本；
- 服务入口（8080 直连还是 8088 Nginx）；
- VU 数量和持续时间；
- 请求总数；
- 吞吐量（requests/s）；
- `http_req_failed`；
- p50、p95、p99 延迟；
- Docker CPU / 内存峰值；
- 是否存在数据库、Redis、连接池或外部 API 错误。

## 结果解释

可以说：

> 在 Windows + Docker Desktop、5 VU、10 秒、测试数据规模为 N 的本地基线中，LearnHub 的题库分页接口 p95 为 X ms，错误率为 Y%，吞吐量为 Z req/s。

不要说：

> LearnHub 可以稳定支撑 Z 个用户。

因为 VU、真实用户行为、数据量、网络、AI 供应商和服务器规格都不同；本地压测结果只能作为当前环境的工程基线。
