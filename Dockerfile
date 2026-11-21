# syntax=docker/dockerfile:1
# AgentFlow 多阶段制品（G17）。构建 context = 仓库根目录，.dockerignore 已排除
# .venv / node_modules / target / .git 等。用法示例：
#   docker build --target java-service --build-arg JAR_PATH=/out/workflow-service.jar -t af-workflow .
#   docker build --target engine-api -t af-engine .
#   docker build --target frontend   -t af-frontend .
#
# 所有运行阶段均以非 root UID/GID 10001 运行；HEALTHCHECK 指向 readiness 端点：
#   Java 服务 -> GET /health（common/HealthController，依赖 down 返回 503）
#   Python 引擎 -> GET /internal/health/ready（AF-8，真探 PG + Redis）
#   前端静态站 -> GET /（入口 HTML 可返回即 ready）
# 基础镜像带 digest 的取值来自本机 docker image inspect 的真实 RepoDigests。

# ---------- 1. Java 构建 ----------
FROM maven:3.9-eclipse-temurin-17@sha256:1a352420f7aba21f5ad08df31bab55f74c013fb491f1ae8ab1dd7ff9ed698584 AS java-build
WORKDIR /src
COPY . .
RUN mkdir -p /out \
 && mvn -B -ntp -DskipTests -Dcheckstyle.skip package \
 && for m in workflow-service agent-gateway audit-service connector-service; do \
      cp "$(ls "$m"/target/"$m"-*.jar | grep -v '\.original$' | head -1)" "/out/$m.jar"; \
    done

# ---------- 2. 前端构建 ----------
FROM node:20-alpine@sha256:fb4cd12c85ee03686f6af5362a0b0d56d50c58a04632e6c0fb8363f609372293 AS frontend-build
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# ---------- 3. Java 运行时公共层（非 root + readiness 探针） ----------
FROM eclipse-temurin:17-jre-jammy@sha256:b3054a20e1a851574cdb1692dd0db5310c647e62214a2a7abecbec1b9e3ae8f1 AS java-base
USER root
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
  CMD curl -fsS http://127.0.0.1:8080/health || exit 1

# 单个 Java 服务镜像：用 --build-arg JAR_PATH 复用同一运行层。
FROM java-base AS java-service
ARG JAR_PATH=/out/workflow-service.jar
COPY --from=java-build --chown=10001:10001 ${JAR_PATH} /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

# ---------- 4. Python 引擎（G15 锁文件 + --require-hashes） ----------
FROM python:3.15-slim AS python-base
USER root
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
EXPOSE 8000
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=5 \
  CMD curl -fsS http://127.0.0.1:8000/internal/health/ready || exit 1

FROM python-base AS engine-api
COPY engine-api/ ./
RUN pip install --no-cache-dir --require-hashes -r requirements.lock.txt \
 && pip install --no-cache-dir --no-deps -e .
USER 10001:10001
ENTRYPOINT ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]

# ---------- 5. 前端静态站 ----------
FROM nginx:1.27-alpine@sha256:65645c7bb6a0661892a8b03b89d0743208a18dd2f3f17a54ef4b76fb8e2f2a10 AS frontend
COPY --from=frontend-build /src/frontend/dist /usr/share/nginx/html
USER root
RUN chown -R 10001:10001 /usr/share/nginx/html /var/cache/nginx /var/log/nginx /var/run /tmp \
 && sed -i 's/listen  *80;/listen 8080;/' /etc/nginx/conf.d/default.conf
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=5 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/ || exit 1
ENTRYPOINT ["nginx", "-g", "daemon off; pid /tmp/nginx.pid;"]
