# LearnHub

LearnHub is a monorepo for an AI knowledge-base and learning platform.

## Layout

- LearnHubBackend/: Java 21 and Spring Boot backend.
- LEARNHUB_PLAN.md: project and learning plan.
- frontend/: reserved for the future frontend application.

See LearnHubBackend/README.md for backend-specific instructions.

## Project positioning

LearnHub is intentionally a learning foundation for Spring Boot, Spring AI, and Retrieval-Augmented Generation (RAG). It is not presented as a finished production platform or a guaranteed-profit system. The code is meant to be read, run, changed, and used as a base for future experiments.

The backend demonstrates REST APIs, validation, unified errors, Spring Security, JWT, RBAC and permissions, MySQL and Flyway, MyBatis-Plus and XML, Redis caching and rate limiting, RabbitMQ document parsing, MinIO storage, Qdrant retrieval, SSE streaming, AI question generation, and credit idempotency.

## Learning path

The detailed lessons are under LearnHubBackend/docs/part-01 through part-08. Part 8 records the limited optimization pass and the reasons behind each change.

The public main branch is the monorepo snapshot. The legacy backend commit history is preserved separately in the backend-history branch for reference.
