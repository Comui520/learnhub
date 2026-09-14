# Part 1：工程骨架与 Web 基础（已完成）

> Part 1 的课程资料沿用 `docs/week-01/`（当时按“周”命名），从本目录开始统一使用 Part 命名。`week-01` 文件夹保留为历史课程资料，不再新建 Week 系列文档。

## 状态

- [x] Maven 多模块工程骨架（8 个模块，依赖方向单向）
- [x] Java 21 / Spring Boot 3.5.16，父 POM 统一管理版本
- [x] 应用可启动，`/actuator/health` 健康检查
- [x] 统一响应 `ApiResponse<T>`、错误码、全局异常处理
- [x] 示例接口 `POST /api/v1/demo/greetings`（Validation + OpenAPI 注解）
- [x] 单元测试、Web 切片测试、OpenAPI/Swagger
- [x] Docker Compose 基础设施（MySQL、Redis、RabbitMQ、MinIO、Qdrant）
- [x] 后端仓库 README、`.gitignore`（Git 由自己维护，根目录刻意不做仓库，后续拆三个仓库）

## 文档索引

- 主计划：[LEARNHUB_PLAN.md](../../LEARNHUB_PLAN.md)（已改为 Part 1 ~ Part 8 结构）
- 课程资料：`docs/week-01/`（Day 1 ~ Day 7）
- 回顾教学：[review-testing-and-openapi.md](review-testing-and-openapi.md)
- 下一阶段：[Part 2 用户、登录与权限](../part-02/README.md)

