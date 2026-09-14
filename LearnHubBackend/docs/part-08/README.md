# Part 8：项目收尾与有限优化

> 目标：把已经能运行的 LearnHub 整理成一个可维护、可解释、可继续扩展的 Spring Boot + Spring AI + RAG 学习基础项目。
>
> 这一 Part 由助手直接完成主要代码优化。你不需要逐行照着重写，但仍然要读懂每个改动解决了什么问题、带来了什么代价，以及如何验证。

## 0. 先明确优化的边界

Part 8 不做大规模重构，也不把项目改成微服务。当前项目定位是学习基础：

- 用 Spring Boot 学习模块化单体、REST API、事务和安全；
- 用 Spring AI 学习 ChatClient、Embedding、VectorStore 和 RAG；
- 用 RabbitMQ、Redis、MinIO 和 Qdrant 认识真实项目中的基础设施；
- 保留清晰的代码和教学文档，方便以后继续试验。

优化只处理已经看见的真实问题：安全边界、缓存一致性、事务占用、资源释放、并发初始化和流式错误。

不在本 Part 追求：

- 把所有方法都改成响应式；
- 为每个接口补一整套测试；
- 引入新的 MQ、缓存或微服务；
- 为了性能猜测性地增加线程池和复杂抽象。

## 1. Session 顺序

| 文档 | 重点 | 你需要掌握的概念 |
|---|---|---|
| 前置教学 | 如何判断优化是否值得做 | 基线、风险、收益、回归 |
| Session A | 配置、环境变量和公开仓库 | application.yml、profile、密钥边界 |
| Session B | Redis 缓存一致性 | key 隔离、TTL、失效、空值缓存 |
| Session C | 事务、资源和并发 | TransactionTemplate、数据库唯一键、try-with-resources |
| Session D | RAG 与 SSE 健壮性 | metadata 类型、JSON 序列化、Accept、error 事件 |
| Session E | 交付和后续演进 | README、Git 历史、可继续学习的边界 |

## 2. 本 Part 已经完成的代码优化

### 2.1 知识库缓存

- 知识库详情 key 从“知识库 ID”改为“用户 ID + 知识库 ID”，避免用户 B 命中用户 A 的缓存；
- 文件 ID 列表增加 TTL；
- 空列表使用哨兵值缓存，避免空知识库每次都查数据库；
- 缓存数据损坏时删除坏值并回源数据库；
- 更新、删除、绑定和解绑时主动删除对应缓存。

### 2.2 文档解绑和解析

- 解绑只修改 knowledge_base_document 关联表；
- 不再因为保留某个文件而重复发送 RabbitMQ 解析消息；
- MinIO 读取流使用 try-with-resources，避免连接泄漏；
- 任务成功和重试重新排队时清除旧的 lastError。

### 2.3 额度和学习题目

- 额度账户初始化使用数据库唯一键和幂等插入，降低并发首次访问时的重复插入风险；
- AI 生成题目时，模型调用放在事务外，只有题目和选项写库进入短事务；
- 生成题目请求体的 RequestBody 注解放回 Controller，OpenAPI 能正确描述 JSON body。

### 2.4 RAG 和 SSE

- 引用列表使用 Jackson 序列化，避免文件名中的引号破坏 JSON；
- chunkIndex 兼容 Integer、Long、Double 等 Number 子类以及字符串；
- Chat 在流开始前的业务错误根据 Accept 返回 JSON 或 SSE error 事件；
- 正常流仍然保持 references 在前、content 在后的顺序。

## 3. 这一 Part 的学习方式

每个 Session 都按以下问题阅读：

1. 原代码的风险是什么？
2. 改动改变了哪个边界？
3. 为什么选择这个简单方案？
4. 这个方案还有什么限制？
5. 用什么命令或日志证明它工作了？

优化不是“代码越多越好”。如果一次改动不能明确说明风险、收益和验证方式，就不应该合并。

## 4. Part 8 验收标准

- 应用可以正常编译和启动；
- 现有主链路仍然可用：登录、知识库、文档、RAG、AI 出题、额度和 SSE；
- 不同用户不能通过缓存读取别人的知识库数据；
- 空知识库不会无限制造数据库查询；
- 解绑不会为已经解析的文件重复投递解析消息；
- AI 网络调用不会长时间占用数据库事务；
- Chat 额度不足时返回稳定的 402；
- 公开仓库不包含 .env、真实密钥、日志和构建产物；
- README 能说明项目定位、启动方式和学习边界。

## 5. 完成 Part 8 后怎么办

Part 8 完成后，项目进入“学习基础已成形”的状态。以后可以从这个基础继续选择专题：

- 深入 Spring Security；
- 把 MyBatis-Plus 查询迁移为 XML 和更复杂的 SQL；
- 学习 Spring AI 的 Tool Calling 和 Agent；
- 学习异步题目生成和任务编排；
- 增加前端和独立的前后端部署；
- 用真实数据做性能和可靠性实验。

这些是后续学习方向，不是当前项目必须一次完成的内容。
