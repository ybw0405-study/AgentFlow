-- 知识库向量索引（engine 侧 PG，控制面 MySQL 仍是切片权威源）。
-- 懒索引：retrieval 节点 vector/hybrid 模式首次查询时按 content_hash 增量嵌入；
-- 模型名入谓词，换模型即换空间，不混召回。
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS knowledge_chunk_embedding (
  chunk_id TEXT NOT NULL,
  tenant_id TEXT NOT NULL,
  kb_id TEXT NOT NULL,
  document_id TEXT NOT NULL,
  document_title VARCHAR(256) NOT NULL,
  ordinal INT NOT NULL,
  content TEXT NOT NULL,
  content_hash TEXT NOT NULL,
  model VARCHAR(64) NOT NULL,
  embedding vector(1024) NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (chunk_id, model)
);

CREATE INDEX IF NOT EXISTS idx_kce_scope
  ON knowledge_chunk_embedding (tenant_id, kb_id, model);

CREATE INDEX IF NOT EXISTS idx_kce_embedding
  ON knowledge_chunk_embedding USING hnsw (embedding vector_cosine_ops);
