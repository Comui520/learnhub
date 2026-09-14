# LearnHub
一个简单的, 用于存储知识, 融合ai技术的知识管理系统.

LearnHubBackend 是 LearnHub 学习基础项目的后端目录，定位是学习 Spring Boot、Spring AI 和 RAG，而不是已经完成的生产级平台。

当前后端采用 Maven 多模块模块化单体，包含 user、knowledge、ai、study、credit、infrastructure 和 application 模块。详细的分阶段教学位于 docs/part-01 至 docs/part-08。

运行前请复制 .env.example 为 .env，并在本机填写 Docker 服务和模型 provider 配置。真实凭据不能提交到 Git。
