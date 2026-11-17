-- docker-entrypoint-initdb.d：库与账号骨架（表由 Flyway 迁移创建；授权在 grants 服务落地）
CREATE DATABASE IF NOT EXISTS agentflow CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'af_app'@'%' IDENTIFIED BY 'af_app_dev';
CREATE USER IF NOT EXISTS 'af_audit'@'%' IDENTIFIED BY 'af_audit_dev';
FLUSH PRIVILEGES;
