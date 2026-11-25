-- AgentFlow 事件库（PostgreSQL 16）—— architecture.md §3.2
-- 引擎独占写入；execution_event 对应用账号仅 SELECT/INSERT（DB 层 append-only）

CREATE TABLE execution_event (
  seq BIGSERIAL PRIMARY KEY,
  execution_id UUID NOT NULL,
  event_type TEXT NOT NULL,
  node_key TEXT NULL,
  payload JSONB NOT NULL,
  aggregate_version INT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_ee_version ON execution_event(execution_id, aggregate_version);
CREATE INDEX idx_ee_exec ON execution_event(execution_id, seq);

CREATE TABLE execution_tracker (
  execution_id UUID PRIMARY KEY,
  status TEXT NOT NULL CHECK (status IN ('OPEN','TERMINAL')),
  last_seq BIGINT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_et_open ON execution_tracker(updated_at) WHERE status = 'OPEN';

-- 崩溃恢复自足：派发请求快照（含不可变 DSL），引擎重启后凭它续跑（D4）
CREATE TABLE execution_spec (
  execution_id UUID PRIMARY KEY,
  spec JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE node_io (
  execution_id UUID NOT NULL,
  node_key TEXT NOT NULL,
  direction TEXT NOT NULL CHECK (direction IN ('IN','OUT')),
  payload JSONB NOT NULL,
  content_hash TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (execution_id, node_key, direction)
);
