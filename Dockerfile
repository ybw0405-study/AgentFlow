# syntax=docker/dockerfile:1
# python 基础镜像按 digest 固定。默认值是 python:3.13-slim 的真实 RepoDigest
# （本机 docker image inspect 解析），本地构建开箱即用；CI/release 仍会在构建期
# 用 imagetools inspect 解析最新真实值并经 --build-arg 覆盖。全局 ARG 必须声明在
# 第一个 FROM 之前，否则 FROM 行读不到它（stage 内的 ARG 作用域不覆盖 FROM 指令）。
ARG PYTHON_BASE_DIGEST=sha256:e48f59cdba69a3cbd9ed2cf0f348d90d45a8d350d98134cf1070bc106ee9ed90
# AgentFlow 多阶段制品。构建 context = 仓库根目录，.dockerignore 已排除
# .venv / node_modules / target / .git 等。用法示例：
#   docker build --target java-service --build-arg JAR_PATH=/out/workflow-service.jar -t af-workflow .
#   docker build --target engine-api -t af-engine .
#   docker build --target frontend   -t af-frontend .
#
# 所有运行阶段均以非 root UID/GID 10001 运行；HEALTHCHECK 指向 readiness 端点：
#   Java 服务 -> GET /health（common/HealthController，依赖 down 返回 503）
#   Python 引擎 -> GET /internal/health/ready（真探 PG + Redis）
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
FROM node:22-alpine@sha256:1314fb30ec37592d5845e5a160d522c0e680972aa534549940b8d73bde88481a AS frontend-build
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

# ---------- 4. Python 引擎（锁文件 + --require-hashes） ----------
# 基础镜像按真实 digest 固定，杜绝 tag 漂移。默认 digest 见文件顶部全局 ARG；
# CI/release 构建时用 `docker buildx imagetools inspect python:3.13-slim` 解析
# 最新真实值并以 --build-arg PYTHON_BASE_DIGEST=sha256:... 覆盖。不要在这里重复
# 声明 ARG（stage 内 ARG 不作用于 FROM）。
FROM python:3.13-slim@${PYTHON_BASE_DIGEST} AS python-base
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
# 启动期 run_migrations 读 pg_migrations_path；默认相对路径 ../db/postgres/migration
# 在容器内不存在，必须随镜像携带并固定绝对路径。
COPY db/postgres/migration /db/postgres/migration
ENV AF_PG_MIGRATIONS_PATH=/db/postgres/migration
RUN pip install --no-cache-dir --require-hashes -r requirements.lock.txt \
 && pip install --no-cache-dir --no-deps -e .
USER 10001:10001
ENTRYPOINT ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]

# ---------- 5. 前端静态站 ----------
FROM nginx:1.27-alpine@sha256:65645c7bb6a0661892a8b03b89d0743208a18dd2f3f17a54ef4b76fb8e2f2a10 AS frontend
COPY --from=frontend-build /src/frontend/dist /usr/share/nginx/html
# SPA history fallback + /api 反代 + SSE 关缓冲；缺了 fallback 深链接刷新会 404。
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
USER root
RUN chown -R 10001:10001 /usr/share/nginx/html /var/cache/nginx /var/log/nginx /var/run /tmp
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=5 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/ || exit 1
ENTRYPOINT ["nginx", "-g", "daemon off; pid /tmp/nginx.pid;"]
