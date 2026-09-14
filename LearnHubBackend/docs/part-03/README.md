# Part 3 课程：知识库与文件管理

> 前置要求：Part 2 已完成（JWT 鉴权、RBAC、测试套路都熟），MySQL 和 Docker Compose 正常运行。
>
> 目标：把“用户的资料”真正管理起来——知识库 CRUD、MinIO 文件上传/下载/删除、文档状态机、用户数据隔离。这是第一个“CRUD 密集”的 Part，正好用上我们约定的三档模式。

## 1. 本 Part 新增的技术

| 技术 | 干嘛的 | 依赖 |
|---|---|---|
| MinIO | 对象存储，存上传的原始文件 | `io.minio:minio`（infrastructure 模块已预置，版本由父 POM 管） |
| Multipart | HTTP 文件上传 | `spring-boot-starter-web` 自带，不用加依赖 |
| SHA-256 | 文件去重 | JDK 自带，不用加依赖 |

这是**第一次真正用到 `learnhub-infrastructure` 模块**——Part 1 我们按需接入，把它的依赖去掉了；现在要接 MinIO，就把它加回来（这就是当初埋的伏笔）。

## 2. 三个 Session 目录

| Session | 主题 | 档位 |
|---|---|---|
| [A](session-a-minio-and-upload.md) | MinIO 接入 + 建表 + 知识库创建 + 文件上传 | 🧑‍🏫 我带 |
| [B](session-b-knowledge-base-crud.md) | 知识库剩余 CRUD（列表/详情/改/删）+ 数据隔离 | 🤝 各写一半 + 🏃 你自己做 |
| [C](session-c-document-lifecycle.md) | 文档状态机、下载/删除、去重与一致性 | 🧑‍🏫 带概念 + 🏃 自己做 |
| [D](session-d-model-refactor-to-many-to-many.md) | **模型重构**：document 拆分 document_file + knowledge_base_document | 🧑‍🏫 助手代工 |

## 3. 固定约定

- API 前缀：`/api/v1/knowledge-bases`、`/api/v1/documents`。
- 所有“按 ID 查”的接口，SQL 必须带 `user_id` 条件（数据隔离铁律，Session B 会反复练）。
- 迁移文件放 `learnhub-knowledge/src/main/resources/db/migration/`，版本号从当前全局最大 +1 开始（先 `SELECT MAX(version) FROM flyway_schema_history` 确认）。
- 实体（含 MinIO object_name）不直接返回，统一用 DTO 脱敏。
- 上传的文件名/类型来自客户端，**永远不要信任**，只做展示和类型判断，不作为路径拼接。

## 4. 最终验收清单

- [ ] 知识库 CRUD 全部可用，且只能操作自己的数据。
- [ ] 上传文件成功：MinIO 里有对象、`document` 表有记录、状态 UPLOADED。
- [ ] 同一用户重复上传同一文件返回 409（去重生效）。
- [ ] 上传超过限制大小的文件返回明确的 4xx。
- [ ] 未登录访问全部 401；访问别人的知识库返回 404（不暴露存在性）。
- [ ] 删除知识库时，其下文档记录和 MinIO 对象的处理有明确设计（Session C）。
- [ ] `mvn clean verify` 全绿。

> **2026-08-18 更新**：数据模型已重构为“文件本体 + 多对多关联”两表结构（[Session D](session-d-model-refactor-to-many-to-many.md)）。验收清单里涉及 `document` 的表述，现在对应 `document_file`（文件本体）与 `knowledge_base_document`（归属关联）两张表。
>
> **2026-08-19 更新**：上传与绑定已分离——`POST /api/v1/document` 只建文件，`bind-document` / `unbind-document` 负责知识库归属（解绑 = 全部解绑后重绑保留的），删除是文件级级联。**最终模型以 [Session D](session-d-model-refactor-to-many-to-many.md) 为准**，Session A/B/C 保留为历史教学记录。

## 5. 答辩题预告（Session C 结束前能答）

1. 为什么文件要存对象存储而不是本地磁盘？
2. MinIO 的 bucket / object 是什么？和文件系统有什么区别？
3. SHA-256 去重怎么实现的？为什么不直接比文件名？
4. 上传文件时“先查重 → 传 MinIO → 建记录”中间任何一步失败，怎么保证一致性？
5. 用户数据隔离为什么要在 SQL 层做，而不是只靠 Controller 判断？
6. 删除知识库时，数据库记录和 MinIO 对象怎么保持一致？（重构后：删关联 → 无引用才删文件 + 对象）

> 开始前或做到 Session B 时，先读 [前置教学：MyBatis-Plus 高级用法与 XML Mapper](primer-mybatis-plus-and-xml.md)——列表分页、条件查询、XML 动态 SQL 都会用到。

从 [Session A](session-a-minio-and-upload.md) 开始。
