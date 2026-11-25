-- V009__knowledge.sql ------------------------------------------------------
-- 文档知识库：retrieval 节点检索的真实数据源。
--
-- 设计取舍：切片（chunk）在写入时生成、按租户与知识库下推过滤，评分在应用层做
-- BM25（纯 JDK，不引入向量库/外部依赖，保持离线可跑）。文档与知识库软删时
-- 同步物理清除其切片，让检索 SQL 不需要 JOIN 软删状态。
CREATE TABLE knowledge_base (
  id CHAR(36) NOT NULL,
  tenant_id CHAR(36) NOT NULL,
  name VARCHAR(128) NOT NULL,
  description VARCHAR(512) NULL,
  created_by CHAR(36) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  KEY idx_kb_tenant (tenant_id, deleted_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE knowledge_document (
  id CHAR(36) NOT NULL,
  tenant_id CHAR(36) NOT NULL,
  kb_id CHAR(36) NOT NULL,
  title VARCHAR(256) NOT NULL,
  source VARCHAR(32) NOT NULL DEFAULT 'TEXT',
  char_count INT NOT NULL DEFAULT 0,
  chunk_count INT NOT NULL DEFAULT 0,
  created_by CHAR(36) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  KEY idx_kd_kb (tenant_id, kb_id, deleted_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE knowledge_chunk (
  id CHAR(36) NOT NULL,
  tenant_id CHAR(36) NOT NULL,
  kb_id CHAR(36) NOT NULL,
  document_id CHAR(36) NOT NULL,
  ordinal INT NOT NULL,
  document_title VARCHAR(256) NOT NULL,
  content TEXT NOT NULL,
  token_count INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_kc_kb (tenant_id, kb_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
