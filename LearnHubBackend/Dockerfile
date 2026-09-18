# syntax=docker/dockerfile:1.7

FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY learnhub-application/pom.xml learnhub-application/pom.xml
COPY learnhub-common/pom.xml learnhub-common/pom.xml
COPY learnhub-user/pom.xml learnhub-user/pom.xml
COPY learnhub-knowledge/pom.xml learnhub-knowledge/pom.xml
COPY learnhub-ai/pom.xml learnhub-ai/pom.xml
COPY learnhub-study/pom.xml learnhub-study/pom.xml
COPY learnhub-credit/pom.xml learnhub-credit/pom.xml
COPY learnhub-infrastructure/pom.xml learnhub-infrastructure/pom.xml

COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl learnhub-application -am -DskipTests package

FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S learnhub && adduser -S -G learnhub -h /app learnhub

WORKDIR /app
COPY --from=build /workspace/learnhub-application/target/learnhub-application-*.jar /app/learnhub.jar
RUN mkdir -p /app/logs && chown -R learnhub:learnhub /app

USER learnhub
EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC" \
    SPRING_PROFILES_ACTIVE=docker

HEALTHCHECK --interval=20s --timeout=5s --start-period=60s --retries=5 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/learnhub.jar"]
