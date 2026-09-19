# LearnHub 性能基线记录

> 状态：已完成第一轮本地基线。脚本位于 `perf/k6/`。

## 测试环境

- 日期：2026-09-19
- Git commit：待提交（本次 Docker / 压测改动）
- 操作系统：Windows + Docker Desktop / WSL2
- 压测工具：Docker `grafana/k6`，k6 v2.2.0
- 压测入口：Docker 网络内的 `http://frontend`，包含 Nginx 与 Spring Boot，不包含宿主机端口转发耗时
- 并发模型：5 VU，10 秒
- 数据：本地演示数据，不包含 AI 生成压测

## 结果摘要

| 场景 | VU | 时长 | 请求数 | 吞吐量 | 错误率 | p50 | p95 | p99 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Health | 5 | 10s | 20,184 | 2,018.09 req/s | 0% | 1.87 ms | 4.32 ms | 7.47 ms |
| Credit balance | 5 | 10s | 17,045* | 1,659.31 req/s* | 0% | 2.27 ms | 5.41 ms | 8.46 ms |
| Study library page | 5 | 10s | 9,149* | 906.91 req/s* | 0% | 4.22 ms | 9.66 ms | 15.06 ms |

## 运行命令

```powershell
k6 run perf/k6/health.js
k6 run perf/k6/credit-balance.js
$env:KNOWLEDGE_BASE_ID = "1"
k6 run perf/k6/study-library.js
```

* Credit / Study 脚本的请求总数包含 1 次 setup 登录请求；业务接口请求数分别约为 17,044 和 9,148。

## Docker 资源快照

测试结束后执行 `docker stats --no-stream`，得到一次瞬时快照：

| 容器 | CPU | 内存 |
|---|---:|---:|
| backend | 14.55% | 682.1 MiB |
| frontend | 0.00% | 18.15 MiB |
| mysql | 0.69% | 446.6 MiB |
| redis | 1.01% | 6.9 MiB |
| rabbitmq | 0.26% | 109.9 MiB |
| minio | 1.94% | 234.7 MiB |
| qdrant | 0.39% | 97.7 MiB |

这不是峰值监控，只是压测结束时的资源快照；正式报告应使用持续采样或 Prometheus。

## 结论

在本次 Windows + Docker Desktop、5 VU、10 秒、本地演示数据的基线中，三个非 AI 场景均为 0% HTTP 错误率，p95 分别为 4.32 ms、5.41 ms 和 9.66 ms。结果说明当前演示数据规模下，基础 Web、额度读取和题库分页链路运行正常；它不代表公网生产容量，也不能外推 AI Chat 的吞吐量。
