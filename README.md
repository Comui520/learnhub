# LearnHub

LearnHub is a monorepo for an AI knowledge-base and learning platform.

## Layout

- LearnHubBackend/: Java 21 and Spring Boot backend.
- LEARNHUB_PLAN.md: project and learning plan.
- frontend/: reserved for the future frontend application.

See LearnHubBackend/README.md for backend-specific instructions.

## Project positioning

LearnHub is intentionally a learning foundation for Spring Boot, Spring AI, and Retrieval-Augmented Generation (RAG). It is not presented as a finished production platform or a guaranteed-profit system. The code is meant to be read, run, changed, and used as a base for future experiments.

## Actual technology stack

| Technology | Version or form | How it is used in this project |
|---|---|---|
| Java | 21 | Main language and runtime; records, streams, pattern matching, and modern Java syntax are used in the modules. |
| Maven | Multi-module reactor | Builds the eight backend modules and centralizes dependency versions in the parent POM. |
| Spring Boot | 3.5.16 | Application bootstrap, auto-configuration, embedded server, configuration binding, and module composition. |
| Spring MVC | spring-boot-starter-web | Handles ordinary REST endpoints, JSON validation, multipart upload, file download, and unified HTTP responses. |
| Reactor / WebFlux | spring-boot-starter-webflux in ai | Supplies Flux and reactive stream types for the Chat SSE endpoint; the application still keeps MVC for ordinary APIs. |
| Spring Validation | Jakarta Validation | Validates request DTOs such as registration, knowledge-base, chat, binding, and answer requests. |
| Spring Security | Spring Security 6 through Boot 3 | Stateless authentication, SecurityFilterChain, JWT filter, method security, permissions, 401 and 403 JSON handlers. |
| JJWT | 0.13.0 | Creates and parses the access token used by the custom JWT filter. |
| MySQL | 8.4 in Docker | Stores users, roles, permissions, knowledge bases, document files, relations, parse tasks, study questions, attempts, wrong questions, credit accounts, orders, and transactions. |
| Flyway | V1 to V12 migrations | Evolves the relational schema for user, knowledge, document, credit, and study domains. |
| MyBatis-Plus | 3.5.17 | Provides BaseMapper, ServiceImpl, wrappers, pagination, and common CRUD operations. |
| MyBatis XML | Mapper XML files | Holds joins, paged task queries, relation queries, and atomic credit deduction SQL where annotations would be unclear. |
| Redis | Redis 7.4 in Docker | Caches knowledge-base details, bound file IDs, study options, and login-attempt counters. |
| Redisson | 3.52.0 | Provides the distributed rate limiter used by the Chat endpoint. |
| RabbitMQ | 4.1 in Docker + Spring AMQP | Asynchronously parses uploaded documents, retries failed work, supports dead-letter/recovery flows, and keeps the HTTP upload path short. |
| MinIO | Java SDK 9.0.3; server in Docker | Stores original document objects and creates presigned download URLs. |
| Spring AI | 1.1.8 | Uses ChatClient for model calls, document readers/splitters for ingestion, and VectorStore for semantic retrieval. |
| Qdrant | 1.14.1 in Docker | Stores document chunk embeddings and filters retrieval by the bound fileId metadata. |
| OpenAI-compatible provider | Environment-configured | Supplies chat and embedding APIs; the provider URL and key are intentionally not hard-coded. |
| Jackson | Spring Boot managed | Parses model JSON, serializes cached objects, and safely serializes RAG reference events. |
| Docker Compose | compose.yaml | Starts MySQL, Redis, RabbitMQ, MinIO, and Qdrant for local development. |
| OpenAPI / Swagger UI | springdoc 2.8.17 | Documents REST request/response schemas and allows manual endpoint exploration. SSE is tested with curl because Swagger is not a stream viewer. |
| Actuator | Spring Boot starter | Currently used primarily for health checks such as /actuator/health. Prometheus/Grafana are not claimed as a completed feature. |
| Logback / SLF4J | Spring Boot logging | Writes structured application logs and rolling local log files; secrets and full prompts are not logged. |
| JUnit 5, Mockito, AssertJ | Test scope | Covers selected high-risk and regression paths. New feature development does not require a test suite for every endpoint. |

## Backend modules

| Module | Responsibility |
|---|---|
| learnhub-common | ApiResponse, common error codes, BusinessException, and shared pagination types. |
| learnhub-user | Users, registration/login, BCrypt passwords, JWT, SecurityFilterChain, roles, permissions, and login audit/attempt control. |
| learnhub-knowledge | Knowledge bases, document files, many-to-many bindings, MinIO operations, parsing tasks, RabbitMQ consumer, and vector indexing. |
| learnhub-ai | RAG retrieval, ChatClient calls, SSE responses, AI question generation, and provider-facing configuration. |
| learnhub-study | Generated questions, options, answer attempts, wrong-question records, Redis question-option cache, and answer checking. |
| learnhub-credit | Credit accounts, atomic deduction, orders, simulated payment callbacks, transaction records, and idempotency. |
| learnhub-infrastructure | Shared MinIO, RabbitMQ, Redis, and Qdrant client configuration. |
| learnhub-application | Spring Boot main class, module assembly, global exception handling, OpenAPI configuration, and web-level tests. |

## Current boundaries

- The application is a modular monolith, not a microservice system.
- Chat uses RAG with metadata filtering and SSE; persistent multi-turn chat memory is not currently wired into the service even though a related dependency is present.
- Actuator health is used; a full Prometheus/Grafana monitoring stack is not part of the current completed implementation.
- The OpenAI-compatible provider is configured by environment variables, so the same code can point to different providers without changing source files.
- The project is a learning foundation and intentionally leaves room for later experiments such as Tool Calling, Agent loops, reranking, and a separate frontend.

## Learning path

The detailed lessons are under LearnHubBackend/docs/part-01 through part-08. Part 8 records the limited optimization pass and the reasons behind each change.

The public main branch is the monorepo snapshot. The legacy backend commit history is preserved separately in the backend-history branch for reference.
