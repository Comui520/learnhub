# LearnHub 文档导航

<p align="center">
  <img src="../frontend/public/learnhub-mark.svg" width="58" alt="LearnHub mark" />
</p>

<p align="center">
  <a href="../README.md">项目首页</a> ·
  <a href="../README_en.md">English</a>
</p>

这里是 LearnHub 的文档导航入口。

> **重点说明：**完整的工程学习文档位于 [`../LearnHubBackend/docs/`](../LearnHubBackend/docs/)，按阶段记录如何从零构建这个项目。根目录的 `docs/` 还保存 README 使用的真实界面截图。

## 部署与运行

- [`docker-deployment.md`](docker-deployment.md)：使用 Docker Compose 一次启动前端、后端和全部基础设施，并说明日志、更新、数据卷与服务器部署注意事项。

## 推荐学习顺序

| 顺序 | 内容 | 入口 |
| --- | --- | --- |
| 0 | 总体计划与阶段目标 | [`LEARNHUB_PLAN.md`](../LEARNHUB_PLAN.md) |
| 1 | 第一周：基线、Spring Boot、MVC、OpenAPI、Docker | [`week-01`](../LearnHubBackend/docs/week-01/README.md) |
| 2 | Part 01：工程骨架与 Web 基础 | [`part-01`](../LearnHubBackend/docs/part-01/README.md) |
| 3 | Part 02：数据库、认证、JWT 与权限 | [`part-02`](../LearnHubBackend/docs/part-02/README.md) |
| 4 | Part 03：MyBatis、MinIO、知识库、文档 | [`part-03`](../LearnHubBackend/docs/part-03/README.md) |
| 5 | Part 04：RabbitMQ 与异步解析 | [`part-04`](../LearnHubBackend/docs/part-04/README.md) |
| 6 | Part 05：Spring AI、RAG、向量检索与 SSE | [`part-05`](../LearnHubBackend/docs/part-05/README.md) |
| 7 | Part 06：Redis、限流、额度、订单 | [`part-06`](../LearnHubBackend/docs/part-06/README.md) |
| 8 | Part 07：Study 题库、测试、性能与部署 | [`part-07`](../LearnHubBackend/docs/part-07/README.md) |
| 9 | Part 08：配置、缓存一致性、SSE 加固、发布整理 | [`part-08`](../LearnHubBackend/docs/part-08/README.md) |

## 界面截图

- [`overview.png`](images/overview.png)：总览
- [`knowledge-base.png`](images/knowledge-base.png)：知识库
- [`study-library.png`](images/study-library.png)：已有题库
- [`study-generate.png`](images/study-generate.png)：生成新题
- [`study-practice.png`](images/study-practice.png)：练习与答案反馈

## 文档使用方式

1. 先阅读对应阶段的 `README.md`，了解目标与验收标准；
2. 按 session 文档推进实现；
3. 对照源码、Swagger 和测试验证行为；
4. 每完成一个阶段，再阅读 review / primer 文档进行复盘；
5. 将自己的配置和密钥保持在本地 `.env`，不要提交到仓库。
