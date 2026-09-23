# CosyAgent 多阶段构建：Stage 1 编译打包，Stage 2 精简运行镜像
# 构建：docker build -t cosy-agent:0.1.0 .
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline -q || true
COPY src ./src
RUN mvn -B -ntp package -DskipTests -q

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/cosy-agent-0.1.0-SNAPSHOT.jar app.jar
# 运行配置全部经环境变量注入（见 docker-compose.yml）
ENV SERVER_PORT=8080 \
    OPENAI_BASE_URL=https://api.openai.com \
    OPENAI_CHAT_MODEL=gpt-4o-mini \
    REDIS_HOST=localhost REDIS_PORT=6379 \
    COSY_AGENT_VECTOR_STORE=memory COSY_AGENT_TASK_STORE=memory \
    COSY_AGENT_MOCK_ENABLED=false
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --retries=5 --start-period=20s \
  CMD curl -fs http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
